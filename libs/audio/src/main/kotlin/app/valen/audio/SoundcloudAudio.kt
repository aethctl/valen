/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

/** One of SoundCloud's sets, as its API describes it; [tracks] is empty in an account listing. */
data class SoundcloudPlaylist(
    val id: String,
    val name: String,
    val artworkUrl: String?,
    val trackCount: Int,
    val permalink: String?,
    val tracks: List<SoundcloudTrack> = emptyList(),
    /** The set says it holds more rows than SoundCloud sent: a private or region-locked part. */
    val truncated: Boolean = false,
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
        // A track SoundCloud streams only copy-protected cannot play here: it is left out of a
        // search, so the same song on another service is the copy offered instead.
        (json["collection"] as? JsonArray).orEmpty().filterNot { protectedOnly(it) }.mapNotNull { row(it) }
    }

    /**
     * One set by its page address. A public set needs nothing but the runtime client id; [token]
     * is the account's own OAuth token, which also reads a private set the account owns.
     *
     * SoundCloud sends a set's rows with the set itself, so this is two requests at most: the
     * resolve, then the set when the address was a short link.
     */
    suspend fun playlist(url: String, token: String? = null): SoundcloudPlaylist = withContext(Dispatchers.IO) {
        val resolved = api("https://api-v2.soundcloud.com/resolve?url=${encode(url)}", token)
        val kind = resolved.str("kind")
        if (kind == "track") throw IllegalStateException("That SoundCloud link is a track, not a set")
        if (kind != null && kind != "playlist") throw IllegalStateException("That SoundCloud link is not a set")
        val id = resolved.str("id") ?: throw IllegalStateException("SoundCloud did not recognise that set")
        val declared = resolved["track_count"]?.jsonPrimitive?.intOrNull ?: 0
        val tracks = (resolved["tracks"] as? JsonArray).orEmpty().mapNotNull { row(it) }
        SoundcloudPlaylist(
            id = id,
            name = resolved.str("title") ?: "SoundCloud set",
            artworkUrl = artwork(resolved.str("artwork_url")),
            trackCount = maxOf(declared, tracks.size),
            permalink = resolved.str("permalink_url"),
            tracks = tracks,
            truncated = declared > tracks.size,
        )
    }

    /**
     * The sets of the signed-in account, in its own order. SoundCloud only answers this with the
     * account's OAuth token (`/me/playlists`); the token is the browser's cookie, never stored.
     */
    suspend fun accountPlaylists(token: String, limit: Int = 100): List<SoundcloudPlaylist> = withContext(Dispatchers.IO) {
        val out = ArrayList<SoundcloudPlaylist>()
        var path: String? = "https://api-v2.soundcloud.com/me/playlists" +
            "?limit=${limit.coerceIn(1, 200)}&offset=0&linked_partitioning=1"
        while (path != null && out.size < limit) {
            val answer = api(withoutClientId(path), token)
            (answer["collection"] as? JsonArray).orEmpty().mapNotNullTo(out) { it.asSetOrNull() }
            path = answer["next_href"]?.jsonPrimitive?.contentOrNull
        }
        out.distinctBy { it.id }
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
            ?: throw IllegalStateException(unplayable(transcodings))
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

    private fun api(path: String, token: String? = null): JsonObject {
        val separator = if (path.contains('?')) '&' else '?'
        val url = "$path${separator}client_id=${clientId()}"
        val body = get(url, accept = "application/json", authorization = token?.takeIf { it.isNotBlank() }?.let { "OAuth $it" })
        val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (parsed == null) {
            // A stale id answers 401/403; get() throws for those, so this is a malformed answer.
            throw IllegalStateException("SoundCloud sent an answer Valen could not read")
        }
        return parsed
    }

    private fun get(url: String, accept: String, authorization: String? = null): String {
        var attempt = 0
        while (true) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .apply { if (authorization != null) header("Authorization", authorization) }
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) return body
                val expired = response.code == 401 || response.code == 403
                if (expired && authorization != null) {
                    // A bad token is the session's, not the client id's: asking for a new id would
                    // only repeat the same refusal.
                    throw IllegalStateException("SoundCloud refused the signed-in session (${response.code})")
                }
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
    /** Why none of [transcodings] plays, in words. */
    private fun unplayable(transcodings: JsonArray): String {
        val kinds = transcodings.mapNotNull { protocolOf(it) }.distinct()
        return if (kinds.isNotEmpty() && kinds.all { it.contains("encrypted") }) {
            "SoundCloud only streams this track copy-protected, which Valen cannot play"
        } else {
            "SoundCloud has no playable stream for this track (it offers: ${kinds.joinToString().ifEmpty { "nothing" }})"
        }
    }

    private fun protocolOf(transcoding: JsonElement): String? =
        ((transcoding as? JsonObject)?.get("format") as? JsonObject)?.get("protocol")?.jsonPrimitive?.contentOrNull

    /**
     * A row whose streams are all copy-protected (newer label releases come as encrypted HLS only).
     * A row that lists no streams at all is not judged here: it is resolved when played.
     */
    internal fun protectedOnly(row: JsonElement): Boolean {
        val transcodings = ((row as? JsonObject)?.get("media") as? JsonObject)?.get("transcodings") as? JsonArray
        if (transcodings.isNullOrEmpty()) return false
        return transcodings.none { protocolOf(it) in PLAYABLE_PROTOCOLS }
    }

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

    /** One set of an account listing, without its songs: a row only needs the address and the name. */
    private fun JsonElement.asSetOrNull(): SoundcloudPlaylist? {
        val row = this as? JsonObject ?: return null
        val kind = row.str("kind")
        if (kind != null && kind != "playlist") return null
        val id = row.str("id") ?: return null
        return SoundcloudPlaylist(
            id = id,
            name = row.str("title") ?: "SoundCloud set",
            artworkUrl = artwork(row.str("artwork_url")),
            trackCount = row["track_count"]?.jsonPrimitive?.intOrNull ?: 0,
            permalink = row.str("permalink_url"),
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    /** A set's cover in a size a screen can use; the API gives an artwork listed at 100px. */
    private fun artwork(url: String?): String? = url?.replace("-large.", "-t500x500.")

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** A `next_href` already names the client id it was built with; our own is appended. */
    private fun withoutClientId(url: String): String =
        url.replace(CLIENT_ID_IN_URL, "$1").replace("?&", "?").replace("&&", "&").trimEnd('?', '&')

    companion object {
        const val HOME = "https://soundcloud.com/"
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
        private const val CLIENT_ID_TTL_MS = 45 * 60_000L
        private val CLIENT_ID_IN_URL = Regex("([?&])client_id=[^&]*")
        private val PLAYABLE_PROTOCOLS = setOf("progressive", "hls")

        /** The web app's own boot data: {"hydratable":"apiClient","data":{"id":"…","isExpiring":true}}. */
        internal val CLIENT_ID = Regex("\"hydratable\":\"apiClient\"\\s*,\\s*\"data\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*\"([A-Za-z0-9]{16,40})\"")

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
