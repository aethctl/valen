/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.lyrics

import app.valen.core.Lyrics
import app.valen.core.LyricsFormats
import app.valen.core.LyricsQuery
import app.valen.core.LyricsSource
import app.valen.core.LyricsTiming
import app.valen.core.LyricsUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Where Lyprio answers and the key its requests are signed with. Neither is in Valen's source:
 * both come from the person's own setup (see [LyricsSetup]).
 */
class LyprioEndpoint internal constructor(val baseUrl: HttpUrl, internal val key: ByteArray) {
    companion object {
        /**
         * The endpoint for [url] and a base64 [key] of 32 bytes, or null when either is not one.
         * Only HTTPS on its own port is taken; [loopbackHttp] lets tests talk to a local server.
         */
        fun of(url: String, key: String, loopbackHttp: Boolean = false): LyprioEndpoint? {
            val parsed = url.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            val loopback = parsed.host == "127.0.0.1" || parsed.host == "localhost"
            val secure = parsed.isHttps && parsed.port == 443
            if (!secure && !(loopbackHttp && loopback)) return null
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
            val bytes = runCatching { Base64.getDecoder().decode(key.trim()) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
            return LyprioEndpoint(parsed, bytes)
        }
    }
}

/**
 * Lyprio: one search that asks many lyrics sources at once and answers with the best it found,
 * often timed word by word. This is a port of Metrolist's client (GPL-3.0, see NOTICE), protocol
 * 2 with its fall back to 1:
 *
 * - A search is a signed POST (`X-Lyprio-Timestamp`, and `X-Lyprio-Signature`: HMAC-SHA256 of
 *   "timestamp\nbody" with the key, in hex). Protocol 2 answers carry `X-Lyprio-Protocol: 2`.
 * - 200 "ready" brings results (more may follow while `complete` is false), 202 "pending" and
 *   429/503 say when to ask again, 404 "not_found" is a miss. A 503 that is complete means every
 *   source failed: that is not a miss and is not remembered as one.
 * - Polling stops after 20 seconds with what arrived. A server without protocol 2 (404 with no
 *   marker, or 405) is asked through protocol 1 for ten minutes.
 * - A search that finds nothing is asked once more with every source (`allSources`).
 */
class LyprioLyrics internal constructor(
    private val http: OkHttpClient,
    private val endpoint: LyprioEndpoint,
    private val nowSeconds: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    private val jitter: () -> Long,
) : LyricsSource {

    constructor(http: OkHttpClient, endpoint: LyprioEndpoint) : this(
        http, endpoint,
        nowSeconds = { System.currentTimeMillis() / 1000 },
        sleep = { delay(it) },
        jitter = { Random.nextLong(1, MAX_JITTER_MS + 1) },
    )

    override val name = "Lyprio"

    @Volatile private var v1Until = 0L
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun find(query: LyricsQuery): Lyrics? = withTimeout(SEARCH_TIMEOUT_MS) {
        var found = search(query, allSources = false)
        if (found.complete && found.lyrics.isEmpty()) found = search(query, allSources = true)
        found.lyrics.firstOrNull()
    }

    private class Found(val lyrics: List<Lyrics>, val complete: Boolean)

    private suspend fun search(query: LyricsQuery, allSources: Boolean): Found {
        val body = json.encodeToString(
            SearchBody(
                title = query.title,
                artist = query.artist,
                album = query.album?.takeIf { it.isNotBlank() },
                duration = query.durationMs?.takeIf { it > 0 }?.div(1000),
                videoId = query.videoId?.takeIf { it.isNotBlank() },
                allSources = allSources,
            ),
        )
        return if (nowSeconds() < v1Until) Found(searchV1(body, query), complete = true) else searchV2(body, query)
    }

    private suspend fun searchV2(body: String, query: LyricsQuery): Found {
        // One result per sub-source, the best timed of what each sent.
        val bySource = linkedMapOf<String, Lyrics>()
        fun sorted() = bySource.values.sortedByDescending { it.quality() }
        val started = TimeSource.Monotonic.markNow()
        var answer = send(V2_PATH, body)
        while (true) {
            val protocol = answer.header(PROTOCOL_HEADER)
            if ((answer.code == 404 && protocol == null) || answer.code == 405) {
                v1Until = nowSeconds() + V1_FOR_SECONDS
                return Found(searchV1(body, query), complete = true)
            }
            if (protocol != PROTOCOL) throw LyricsUnavailable("it answered in a form Valen does not read")
            val reply = answer.body?.let { runCatching { json.decodeFromString<Reply>(it) }.getOrNull() }
                ?: throw LyricsUnavailable("its answer could not be read")
            when (answer.code) {
                200 -> {
                    for (result in reply.results) {
                        val lyrics = result.toLyrics(query) ?: continue
                        val before = bySource[result.subprovider]
                        if (before == null || lyrics.quality() >= before.quality()) bySource[result.subprovider] = lyrics
                    }
                    if (reply.complete == true) return Found(sorted(), complete = true)
                }
                404 -> return Found(sorted(), complete = true)
                202, 429 -> Unit
                503 -> if (reply.complete == true) {
                    // Every source failed or was skipped: not a miss.
                    if (bySource.isEmpty()) throw LyricsUnavailable("none of its sources answered")
                    return Found(sorted(), complete = false)
                }
                else -> throw LyricsUnavailable("it answered ${answer.code}")
            }
            val wait = waitMs(reply.retryAfterMs, answer.header("Retry-After"), ready = answer.code == 200)
            if (started.elapsedNow().inWholeMilliseconds + wait > MAX_POLL_MS) break
            sleep(wait)
            answer = send(V2_PATH, body)
        }
        if (bySource.isEmpty()) throw LyricsUnavailable("it is still looking; try again in a moment")
        return Found(sorted(), complete = false)
    }

    private suspend fun searchV1(body: String, query: LyricsQuery): List<Lyrics> {
        val answer = send(V1_PATH, body, romanization = true)
        if (answer.code == 404) return emptyList()
        if (answer.code != 200) throw LyricsUnavailable("it answered ${answer.code}")
        val reply = answer.body?.let { runCatching { json.decodeFromString<Reply>(it) }.getOrNull() }
            ?: throw LyricsUnavailable("its answer could not be read")
        return reply.results.mapNotNull { it.toLyrics(query) }.sortedByDescending { it.quality() }
    }

    private fun waitMs(bodyMs: Long?, header: String?, ready: Boolean): Long {
        val fromHeader = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { minOf(it, 60L) * 1000 }
        val fromBody = bodyMs?.takeIf { it >= 0 }
        val base = listOfNotNull(fromHeader, fromBody).maxOrNull() ?: if (ready) READY_POLL_MS else PENDING_POLL_MS
        return base + jitter().coerceIn(1, MAX_JITTER_MS)
    }

    private suspend fun send(path: String, body: String, romanization: Boolean = false): Answer {
        val stamp = nowSeconds().toString()
        val url = endpoint.baseUrl.newBuilder().addPathSegments(path.trimStart('/')).build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-Lyprio-Timestamp", stamp)
            .header("X-Lyprio-Signature", signature(endpoint.key, stamp, body))
            .apply { if (romanization) header(ROMANIZATION_HEADER, "client") }
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            http.answer(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LyricsUnavailable("it could not be reached", e)
        }
    }

    private fun Result.toLyrics(query: LyricsQuery): Lyrics? {
        if (lyrics.isBlank()) return null
        if (!namesArtist(query.artist)) return null
        val source = "Lyprio · ${sourceName(subprovider)}"
        val parsed = LyricsFormats.parse(lyrics, format.lowercase().takeIf { it in FORMATS }, source, query.durationMs) ?: return null
        return LyricsFormats.withoutHeader(parsed, query.title, query.artist)
    }

    /** An MLX result names its artist: one for someone else is not this song's. */
    private fun Result.namesArtist(artist: String): Boolean {
        if (!format.equals("mlx", ignoreCase = true)) return true
        val named = MLX_JSON_ARTIST.find(lyrics)?.groupValues?.get(1) ?: MLX_TEXT_ARTIST.find(lyrics)?.groupValues?.get(1) ?: return true
        return LyricsFormats.artistsMatch(artist, named)
    }

    @Serializable
    private data class SearchBody(
        val title: String,
        val artist: String,
        val album: String? = null,
        val duration: Long? = null,
        val videoId: String? = null,
        val allSources: Boolean = false,
    )

    @Serializable
    private data class Reply(
        val results: List<Result> = emptyList(),
        val status: String? = null,
        val complete: Boolean? = null,
        val retryAfterMs: Long? = null,
    )

    @Serializable
    private data class Result(val subprovider: String = "", val format: String = "", val syncType: String = "", val lyrics: String = "")

    companion object {
        private const val V2_PATH = "/v2/lyrics/search"
        private const val V1_PATH = "/v1/lyrics/search"
        private const val PROTOCOL_HEADER = "X-Lyprio-Protocol"
        private const val PROTOCOL = "2"
        private const val ROMANIZATION_HEADER = "X-Lyprio-Romanization"
        internal const val SEARCH_TIMEOUT_MS = 50_000L
        private const val MAX_POLL_MS = 20_000L
        private const val READY_POLL_MS = 2_000L
        private const val PENDING_POLL_MS = 1_500L
        private const val MAX_JITTER_MS = 1_000L
        private const val V1_FOR_SECONDS = 600L
        private val FORMATS = setOf("mlx", "ttml", "lrc", "plain")
        private val JSON = "application/json".toMediaType()
        private val MLX_JSON_ARTIST = Regex(""""song"\s*:\s*\{[^\}]*?"artist"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val MLX_TEXT_ARTIST = Regex("""(?m)^\s*artist\s*=\s*(.+?)\s*$""")

        internal fun signature(key: ByteArray, stamp: String, body: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            return mac.doFinal("$stamp\n$body".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /** "MUSIXMATCH" → "Musixmatch": the sub-source as the credit names it. */
        internal fun sourceName(raw: String): String = when (raw.uppercase().replace('-', '_')) {
            "" -> "unknown"
            "APPLE", "APPLE_MUSIC", "APPLEMUSIC" -> "Apple Music"
            "MUSIXMATCH", "MXM" -> "Musixmatch"
            "UNISON" -> "Unison"
            "KUGOU" -> "KuGou"
            "LRCLIB" -> "LRCLIB"
            "NETEASE" -> "NetEase"
            "QQ", "QQMUSIC", "QQ_MUSIC" -> "QQ Music"
            "SPOTIFY" -> "Spotify"
            "GENIUS" -> "Genius"
            "YOUTUBE", "YOUTUBE_MUSIC" -> "YouTube"
            "DEEZER" -> "Deezer"
            else -> raw.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }
}

/** Word timing is worth more than line timing, which is worth more than none. */
internal fun Lyrics.quality(): Int = when (timing) {
    LyricsTiming.Words -> 2
    LyricsTiming.Lines -> 1
    LyricsTiming.None -> 0
}
