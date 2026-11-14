/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One of SoundCloud's tracks, as its API describes it. */
data class SoundcloudTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val durationMs: Long,
    val permalink: String?,
)

/**
 * SoundCloud without a browser: its public API, called the way its own player calls it.
 *
 * The API wants a `client_id`, which the site hands out at runtime. It is read from the
 * `soundcloud.com` document (the same `hydratable` block the web app boots from) and kept here for
 * a while; it is never written into the app, and a rejected id is re-read once.
 *
 * Streams come in two shapes. Older tracks expose a progressive MP3; newer ones only a segmented
 * (HLS) AAC stream. Both are handed to FFmpeg as they are, so a track the old page-based adapter
 * refused as "HLS-only" now plays.
 */
class SoundcloudAudio(
    private val http: OkHttpClient = defaultHttp(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    @Volatile private var clientId: String? = null
    @Volatile private var clientIdAt: Long = 0L

    /** Search results, in SoundCloud's own order. */
    suspend fun search(query: String, limit: Int): List<SoundcloudTrack> = withContext(Dispatchers.IO) {
        val json = api(
            "https://api-v2.soundcloud.com/search/tracks" +
                "?q=${encode(query)}&limit=${limit.coerceIn(1, 50)}&offset=0&linked_partitioning=0",
        )
        (json["collection"] as? JsonArray).orEmpty().mapNotNull { row(it) }
    }

    /** One track, ready to play at [quality]. */
    suspend fun resolve(trackId: String, quality: AudioQuality): ResolvedStream = withContext(Dispatchers.IO) {
        val track = api("https://api-v2.soundcloud.com/tracks/${encode(trackId)}")
        val duration = track["duration"]?.jsonPrimitive?.longOrNull ?: 0L
        if (track["policy"]?.jsonPrimitive?.contentOrNull == "BLOCK") {
            throw IllegalStateException("SoundCloud does not allow this track to be streamed")
        }
        val authorization = track["track_authorization"]?.jsonPrimitive?.contentOrNull
        val transcodings = (track["media"] as? JsonObject)?.get("transcodings")?.jsonArray
            ?: throw IllegalStateException("SoundCloud has no stream for this track")
        val chosen = pick(transcodings, quality)
            ?: throw IllegalStateException("SoundCloud has no playable stream for this track")
        val base = chosen["url"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("SoundCloud sent a stream without an address")
        val separator = if (base.contains('?')) '&' else '?'
        val url = buildString {
            append(base).append(separator).append("client_id=").append(clientId())
            if (!authorization.isNullOrBlank()) append("&track_authorization=").append(encode(authorization))
        }
        val answer = get(url, accept = "application/json")
        val streamUrl = runCatching {
            Json.parseToJsonElement(answer).jsonObject["url"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: throw IllegalStateException("SoundCloud did not answer with a stream address")
        ResolvedStream(url = streamUrl, durationMs = duration)
    }

    // ── The API's client id ──

    private fun clientId(force: Boolean = false): String {
        val cached = clientId
        val fresh = cached != null && now() - clientIdAt < CLIENT_ID_TTL_MS
        if (!force && fresh) return cached!!
        val html = get(HOME, accept = "text/html")
        val id = CLIENT_ID.find(html)?.groupValues?.get(1)
            ?: throw IllegalStateException("SoundCloud did not hand out a client id")
        clientId = id
        clientIdAt = now()
        return id
    }

    private fun api(path: String): JsonObject {
        val separator = if (path.contains('?')) '&' else '?'
        val url = "$path${separator}client_id=${clientId()}"
        val body = get(url, accept = "application/json")
        val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (parsed == null) {
            // A stale id answers 401/403; get() throws for those, so this is a malformed answer.
            throw IllegalStateException("SoundCloud sent an answer Valen could not read")
        }
        return parsed
    }

    private fun get(url: String, accept: String): String {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) return body
                val expired = response.code == 401 || response.code == 403
                if (expired && attempt == 0) {
                    clientId(force = true)
                    attempt++
                    // The caller rebuilds the URL with the new id; try the same path once more.
                    return get(withClientId(url), accept)
                }
                if (expired) throw IllegalStateException("SoundCloud refused the request (${response.code})")
                throw IllegalStateException("SoundCloud answered ${response.code}")
            }
        }
    }

    private fun withClientId(url: String): String =
        url.replace(CLIENT_ID_IN_URL, "$1client_id=${clientId()}")

    /** The progressive MP3 when there is one, the AAC 160 stream otherwise. */
    private fun pick(transcodings: JsonArray, quality: AudioQuality): JsonObject? {
        val all = transcodings.mapNotNull { it as? JsonObject }
        fun protocol(t: JsonObject) = (t["format"] as? JsonObject)?.get("protocol")?.jsonPrimitive?.contentOrNull
        fun preset(t: JsonObject) = t["preset"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val progressive = all.filter { protocol(it) == "progressive" }
        val segmented = all.filter { protocol(it) == "hls" }
        return when (quality) {
            AudioQuality.STANDARD -> segmented.firstOrNull { preset(it).contains("96") }
                ?: progressive.firstOrNull() ?: segmented.firstOrNull()
            AudioQuality.HIGH -> segmented.firstOrNull { preset(it).contains("160") }
                ?: progressive.firstOrNull() ?: segmented.firstOrNull()
            AudioQuality.AUTO -> progressive.firstOrNull()
                ?: segmented.firstOrNull { preset(it).contains("160") } ?: segmented.firstOrNull()
        }
    }

    /** A SoundCloud row as the app's track; rows that are not tracks are skipped. */
    private fun row(element: kotlinx.serialization.json.JsonElement): SoundcloudTrack? {
        val object0 = element as? JsonObject ?: return null
        val id = object0["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val kind = object0["kind"]?.jsonPrimitive?.contentOrNull
        if (kind != null && kind != "track") return null
        val title = object0["title"]?.jsonPrimitive?.contentOrNull ?: return null
        val user = (object0["user"] as? JsonObject)?.get("username")?.jsonPrimitive?.contentOrNull
        val artwork = object0["artwork_url"]?.jsonPrimitive?.contentOrNull
        return SoundcloudTrack(
            id = id,
            title = title,
            artist = user ?: "SoundCloud",
            artworkUrl = artwork?.replace("-large.", "-t500x500."),
            durationMs = object0["duration"]?.jsonPrimitive?.longOrNull ?: 0L,
            permalink = object0["permalink_url"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val HOME = "https://soundcloud.com/"
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private const val CLIENT_ID_TTL_MS = 45 * 60_000L
        private val CLIENT_ID_IN_URL = Regex("([?&])client_id=[^&]*")

        /** The web app's own boot data: {"hydratable":"apiClient","data":{"id":"…","isExpiring":true}}. */
        internal val CLIENT_ID = Regex("\"hydratable\":\"apiClient\"\\s*,\\s*\"data\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*\"([A-Za-z0-9]{16,40})\"")

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
