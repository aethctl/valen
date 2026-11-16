/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/** The server asked Valen to slow down (429), or refused an address that has likely expired (403). */
class Throttled(val code: Int, val retryAfterMs: Long?) : IOException("the server answered $code")

/**
 * One whole song from a resolved stream into a file, for keeping: progressive streams in ranges
 * (YouTube cuts long single requests and slows them to playing speed), HLS segment by segment,
 * never a copy-protected playlist. A range that fails for the network is asked again a few times;
 * a 429 or 403 is handed back as [Throttled] for the caller to rest, then resolve again.
 *
 * A progressive download that was cut short continues from the bytes it already has.
 */
class StreamFetcher(
    private val http: OkHttpClient = DiskAudioCache.defaultHttp(),
    /** How much one range asks for. YouTube slows a single request past about 10 MB to playing speed. */
    private val rangeBytes: Long = DiskAudioCache.CHUNK_BYTES,
) {

    /** Writes [stream] to [target] (appending to what a cut-short try left), reporting 0..1. */
    fun fetch(stream: ResolvedStream, target: File, progress: (Float) -> Unit = {}) {
        target.parentFile?.mkdirs()
        if (DiskAudioCache.isHls(stream.url)) {
            target.delete()
            target.outputStream().buffered().use { segmented(stream, it, progress) }
        } else {
            progressive(stream, target, progress)
        }
        if (target.length() < DiskAudioCache.MIN_BYTES) throw IOException("the song came back empty")
    }

    private fun progressive(stream: ResolvedStream, target: File, progress: (Float) -> Unit) {
        RandomAccessFile(target, "rw").use { file ->
            var from = file.length()
            var total = -1L
            var misses = 0
            while (total < 0 || from < total) {
                val request = Request.Builder().url(stream.url).apply {
                    stream.headers.forEach { (k, v) -> header(k, v) }
                    header("Range", "bytes=$from-${from + rangeBytes - 1}")
                }.build()
                try {
                    http.newCall(request).execute().use { response ->
                        if (response.code == 429 || response.code == 403) {
                            throw Throttled(response.code, response.header("Retry-After")?.toLongOrNull()?.times(1000))
                        }
                        if (response.code == 416 && from > 0) {
                            // Asked past the end: what is here is the whole file.
                            total = from
                            return@use
                        }
                        if (!response.isSuccessful) throw IOException("the server answered ${response.code}")
                        val body = response.body ?: throw IOException("no body")
                        file.seek(from)
                        if (response.code == 200) {
                            // The whole file in one answer, from the start.
                            file.setLength(0)
                            file.seek(0)
                            val length = body.contentLength()
                            val copied = copy(body.byteStream(), file) { n -> if (length > 0) progress(n.toFloat() / length) }
                            if (length > 0 && copied != length) throw IOException("cut short")
                            total = copied
                            from = copied
                            return@use
                        }
                        total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: throw IOException("no length")
                        val start = from
                        val copied = copy(body.byteStream(), file) { n -> progress((start + n).toFloat() / total) }
                        if (copied <= 0) throw IOException("cut short")
                        from += copied
                        misses = 0
                    }
                } catch (e: Throttled) {
                    throw e
                } catch (e: IOException) {
                    // The network dropped mid-range: the same range again, a little later.
                    if (++misses > RANGE_RETRIES) throw e
                    Thread.sleep(RANGE_RETRY_MS * misses)
                }
            }
            file.setLength(total)
        }
    }

    private fun segmented(stream: ResolvedStream, out: OutputStream, progress: (Float) -> Unit) {
        var url = stream.url
        var playlist = text(url, stream.headers)
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val variant = playlist.lines().dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }.drop(1)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: throw IOException("no variant")
            url = resolve(url, variant.trim())
            playlist = text(url, stream.headers)
        }
        val lines = playlist.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) throw IOException("copy-protected")
        lines.firstOrNull { it.startsWith("#EXT-X-MAP") }?.let { map ->
            val init = Regex("URI=\"([^\"]+)\"").find(map)?.groupValues?.get(1) ?: throw IOException("no init segment")
            segment(resolve(url, init), stream.headers, out)
        }
        val segments = lines.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segments.isEmpty()) throw IOException("no segments")
        segments.forEachIndexed { i, line ->
            segment(resolve(url, line), stream.headers, out)
            progress((i + 1).toFloat() / segments.size)
        }
    }

    private fun text(url: String, headers: Map<String, String>): String {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(request).execute().use { response ->
            if (response.code == 429 || response.code == 403) throw Throttled(response.code, response.header("Retry-After")?.toLongOrNull()?.times(1000))
            if (!response.isSuccessful) throw IOException("the server answered ${response.code}")
            return response.body?.string() ?: throw IOException("no body")
        }
    }

    private fun segment(url: String, headers: Map<String, String>, out: OutputStream) {
        var misses = 0
        while (true) {
            val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            try {
                http.newCall(request).execute().use { response ->
                    if (response.code == 429 || response.code == 403) throw Throttled(response.code, response.header("Retry-After")?.toLongOrNull()?.times(1000))
                    if (!response.isSuccessful) throw IOException("a segment answered ${response.code}")
                    // A segment is small: read whole before writing, so a retry never doubles bytes.
                    out.write(response.body?.bytes() ?: throw IOException("no body"))
                }
                return
            } catch (e: Throttled) {
                throw e
            } catch (e: IOException) {
                if (++misses > RANGE_RETRIES) throw e
                Thread.sleep(RANGE_RETRY_MS * misses)
            }
        }
    }

    private fun copy(from: java.io.InputStream, to: RandomAccessFile, progress: (Long) -> Unit): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var reported = 0L
        while (true) {
            val n = from.read(buffer)
            if (n < 0) return copied
            to.write(buffer, 0, n)
            copied += n
            if (copied - reported > 256 * 1024) {
                reported = copied
                progress(copied)
            }
        }
    }

    private fun resolve(base: String, ref: String): String = base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    private companion object {
        const val RANGE_RETRIES = 4
        const val RANGE_RETRY_MS = 1_500L
    }
}
