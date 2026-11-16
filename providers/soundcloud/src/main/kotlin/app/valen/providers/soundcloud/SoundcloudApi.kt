/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.soundcloud

import app.valen.core.ProviderId
import app.valen.core.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The one state message the page script posts, as the adapter reads it. */
internal data class SoundcloudState(
    val trackId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val paused: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val signedIn: Boolean,
    val volume: Float,
    val error: String?,
)

/**
 * Reading the page script's JSON. Its search answer is already compact ([Track] fields only), so
 * this stays a handful of key lookups on [JsonObject] rather than a model of SoundCloud's API.
 */
internal object SoundcloudApi {

    /** The `value` of the script's `search`: `[{id,title,user,artwork,duration,permalink,streamable}]`. */
    fun parseSearch(value: JsonElement, limit: Int): List<Track> {
        val items = value as? JsonArray ?: return emptyList()
        return items.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val id = o.str("id") ?: return@mapNotNull null
                val title = o.str("title").orEmpty()
                if (title.isBlank()) return@mapNotNull null
                Track(
                    provider = ProviderId.SOUNDCLOUD,
                    id = id,
                    title = title,
                    artist = o.str("user").orEmpty(),
                    artworkUrl = artwork(o.str("artwork")),
                    durationMs = (o.long("duration") ?: 0L).coerceAtLeast(0L),
                    uri = id,
                    link = o.str("permalink"),
                )
            }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    /** The script's `state` message; null when there is none yet. */
    fun parseState(value: JsonElement?): SoundcloudState? {
        val o = value as? JsonObject ?: return null
        return SoundcloudState(
            trackId = o.str("trackId"),
            positionMs = (o.long("positionMs") ?: 0L).coerceAtLeast(0L),
            durationMs = (o.long("durationMs") ?: 0L).coerceAtLeast(0L),
            paused = o.bool("paused") ?: true,
            buffering = o.bool("buffering") ?: false,
            ended = o.bool("ended") ?: false,
            signedIn = o.bool("signedIn") ?: false,
            volume = (o.str("volume")?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f),
            error = o.str("error")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The same artwork at 500 px: SoundCloud serves one image under a size suffix
     * (`...-large.jpg`, `...-t300x300.jpg`), and `t500x500` is the largest the catalog uses.
     */
    fun artwork(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val cut = url.indexOf('?')
        val base = if (cut >= 0) url.substring(0, cut) else url
        val query = if (cut >= 0) url.substring(cut) else ""
        if (!SIZE.containsMatchIn(base)) return url
        return SIZE.replace(base, "-t500x500.\$1") + query
    }

    /** `-large`, `-t300x300`, `-crop`... before the extension. */
    private val SIZE = Regex("-(?:large|t\\d+x\\d+|crop|mini|small|badge|original)\\.(jpe?g|png|webp|gif)$", RegexOption.IGNORE_CASE)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
