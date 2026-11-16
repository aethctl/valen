/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Whole songs in a folder, for a player that plays files (ffplay on the desktop): a song is
 * downloaded beside the stream that plays it, or ahead when it is likely next, and played from
 * the file from then on — no lookup, no network, an instant start and an instant seek.
 *
 * - A file appears under its key only once it is complete (written as `.part`, then moved), so a
 *   half-fetched song is never played as if it were the whole.
 * - Progressive streams are fetched in [CHUNK_BYTES] ranges (YouTube's servers cut long single
 *   requests); HLS (SoundCloud's newer tracks) is fetched segment by segment into one file, the
 *   init segment first. A copy-protected playlist (an `EXT-X-KEY`) is never fetched.
 * - At most [parallel] songs are fetched at once; a song longer than [MAX_FILE_BYTES] (an hour of
 *   audio, a long mix) is played but not kept.
 * - The folder stays under [maxBytes]: the songs played longest ago go first.
 */
class DiskAudioCache(
    private val dir: File,
    private val maxBytes: Long,
    private val http: OkHttpClient = defaultHttp(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    parallel: Int = 2,
) : StreamCache {

    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val slots = Semaphore(parallel)
    private val trimming = Any()

    init {
        // Leftovers of a fetch that a quit cut short — only those older than this cache: a fetch
        // that starts at once must not lose its file to the clean-up running beside it.
        val born = System.currentTimeMillis() - 1_000L
        scope.launch {
            runCatching { dir.listFiles()?.filter { it.name.contains(PART) && it.lastModified() < born }?.forEach { it.delete() } }
        }
    }

    /** Where [key]'s complete song lives (whether or not it is there). */
    fun file(key: String): File = File(dir, safe(key) + SUFFIX)

    override fun offline(key: String): ResolvedStream? {
        val f = file(key)
        if (!f.isFile || f.length() < MIN_BYTES) return null
        // Played now: the last to go when the folder is trimmed.
        f.setLastModified(System.currentTimeMillis())
        return ResolvedStream(url = f.absolutePath, cacheKey = key)
    }

    override fun has(key: String): Boolean = file(key).let { it.isFile && it.length() >= MIN_BYTES }

    override fun keep(key: String, stream: ResolvedStream, playing: Boolean) {
        if (file(key).isFile || !busy.add(key)) return
        scope.launch {
            try {
                slots.withPermit { fetch(key, stream) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A song that could not be kept is streamed again next time; nothing to say.
            } finally {
                busy.remove(key)
            }
        }
    }

    override fun forget(key: String) {
        runCatching { file(key).delete() }
    }

    /** Fetches [stream] into [key]'s file now (what [keep] does in the background). */
    fun fetch(key: String, stream: ResolvedStream) {
        dir.mkdirs()
        val part = File(dir, safe(key) + PART + System.nanoTime())
        try {
            part.outputStream().buffered().use { out ->
                if (isHls(stream.url)) segmented(stream, out) else progressive(stream, out)
            }
            if (part.length() < MIN_BYTES) throw IOException("the song came back empty")
            Files.move(part.toPath(), file(key).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            trim()
        } finally {
            part.delete()
        }
    }

    /** One file in ranges; a server that ignores ranges sends it whole, which is kept as it comes. */
    private fun progressive(stream: ResolvedStream, out: OutputStream) {
        var from = 0L
        var total = -1L
        while (total < 0 || from < total) {
            val request = Request.Builder().url(stream.url).apply {
                stream.headers.forEach { (k, v) -> header(k, v) }
                header("Range", "bytes=$from-${from + CHUNK_BYTES - 1}")
            }.build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("the server answered ${response.code}")
                val body = response.body ?: throw IOException("no body")
                if (response.code == 200) {
                    // The whole file in one answer.
                    val length = body.contentLength()
                    if (length > MAX_FILE_BYTES) throw IOException("too long to keep")
                    val copied = copyCapped(body.byteStream(), out, MAX_FILE_BYTES)
                    if (length > 0 && copied != length) throw IOException("cut short")
                    return
                }
                total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: throw IOException("no length")
                if (total > MAX_FILE_BYTES) throw IOException("too long to keep")
                val copied = body.byteStream().copyTo(out)
                if (copied <= 0) throw IOException("cut short")
                from += copied
            }
        }
    }

    /** An HLS playlist as one file: its variant, its init segment, then every segment in order. */
    private fun segmented(stream: ResolvedStream, out: OutputStream) {
        var url = stream.url
        var playlist = text(url, stream.headers)
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val variant = playlist.lines().dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }.drop(1)
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: throw IOException("no variant")
            url = resolve(url, variant.trim())
            playlist = text(url, stream.headers)
        }
        val lines = playlist.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) {
            throw IOException("copy-protected")
        }
        var written = 0L
        lines.firstOrNull { it.startsWith("#EXT-X-MAP") }?.let { map ->
            val init = Regex("URI=\"([^\"]+)\"").find(map)?.groupValues?.get(1) ?: throw IOException("no init segment")
            written += segment(resolve(url, init), stream.headers, out, MAX_FILE_BYTES - written)
        }
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#")) continue
            written += segment(resolve(url, line), stream.headers, out, MAX_FILE_BYTES - written)
        }
        if (written <= 0) throw IOException("no segments")
    }

    private fun text(url: String, headers: Map<String, String>): String {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("the server answered ${response.code}")
            return response.body?.string() ?: throw IOException("no body")
        }
    }

    private fun segment(url: String, headers: Map<String, String>, out: OutputStream, room: Long): Long {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("a segment answered ${response.code}")
            val body = response.body ?: throw IOException("no body")
            return copyCapped(body.byteStream(), out, room)
        }
    }

    private fun copyCapped(from: java.io.InputStream, to: OutputStream, room: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val n = from.read(buffer)
            if (n < 0) return copied
            copied += n
            if (copied > room) throw IOException("too long to keep")
            to.write(buffer, 0, n)
        }
    }

    private fun resolve(base: String, ref: String): String =
        base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref

    /** Under [maxBytes]: the songs played longest ago are dropped until a tenth is free. */
    fun trim() {
        synchronized(trimming) {
            val songs = dir.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) } ?: return
            var total = songs.sumOf { it.length() }
            if (total <= maxBytes) return
            for (f in songs.sortedBy { it.lastModified() }) {
                if (total <= maxBytes * 9 / 10) break
                val size = f.length()
                if (f.delete()) total -= size
            }
        }
    }

    /** How much the folder holds now. */
    fun size(): Long = dir.listFiles()?.filter { it.name.endsWith(SUFFIX) }?.sumOf { it.length() } ?: 0L

    companion object {
        const val SUFFIX = ".song"
        private const val PART = ".part-"
        const val CHUNK_BYTES = 4L * 1024 * 1024
        /** About an hour of audio at 128 kb/s; longer mixes are streamed every time. */
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        /** Below this a "song" is an error page, not audio. */
        const val MIN_BYTES = 16L * 1024

        fun isHls(url: String): Boolean = url.substringBefore('?').let { it.endsWith(".m3u8") || it.contains("/playlist") && url.contains("m3u8") }

        fun safe(key: String): String = key.replace(Regex("[^A-Za-z0-9_.-]"), "_")

        /** [preferred] bytes, but never more than a quarter of the free space on [dir]'s disk. */
        fun budget(dir: File, preferred: Long): Long {
            dir.mkdirs()
            val free = runCatching { dir.usableSpace }.getOrDefault(0L)
            return if (free <= 0L) preferred else minOf(preferred, free / 4)
        }

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
