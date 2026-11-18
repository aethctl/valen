/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class PresenceSettings(
    val enabled: Boolean = false,
    val display: PresenceDisplay = PresenceDisplay.NAME,
    val details: String = DEFAULT_DETAILS,
    val state: String = DEFAULT_STATE,
    val cover: Boolean = true,
    val mark: Boolean = true,
    val largeText: String = DEFAULT_LARGE_TEXT,
    val smallText: String = DEFAULT_SMALL_TEXT,
    val time: PresenceTime = PresenceTime.PROGRESS,
    val pause: PresencePause = PresencePause.FIVE_MINUTES,
    val songButton: Boolean = true,
    val homeButton: Boolean = true,
) {
    companion object {
        const val DEFAULT_DETAILS = "{title}"
        const val DEFAULT_STATE = "{status} · {artist}"
        const val DEFAULT_LARGE_TEXT = "{album} · {source}"
        const val DEFAULT_SMALL_TEXT = "Valen · {queue}"
    }
}

@Serializable
enum class PresenceDisplay(val label: String) {
    NAME("Valen"),
    FIRST("First line"),
    SECOND("Second line"),
}

@Serializable
enum class PresenceTime(val label: String) {
    PROGRESS("Bar"),
    ELAPSED("Count up"),
    REMAINING("Count down"),
    OFF("Off"),
}

@Serializable
enum class PresencePause(val label: String, val minutes: Int) {
    KEEP("Keep it", 0),
    FIVE_MINUTES("5 min", 5),
    FIFTEEN_MINUTES("15 min", 15),
    AT_ONCE("Hide now", -1),
    ;

    fun gone(pausedMs: Long): Boolean = when {
        this == AT_ONCE -> true
        minutes == 0 -> false
        else -> pausedMs > minutes * 60_000L
    }
}

/** Where the link to Discord stands, said in Settings. */
sealed interface PresenceStatus {
    data object Off : PresenceStatus
    data object NeedsAppId : PresenceStatus

    /** Discord is not running (no socket to talk to); Valen keeps looking. */
    data object NoDiscord : PresenceStatus
    data object Connecting : PresenceStatus
    data class Connected(val user: String?) : PresenceStatus
    data class Failed(val reason: String) : PresenceStatus
}

/** The platform's link to Discord; only the desktop has one. */
interface PresenceLink {
    val status: StateFlow<PresenceStatus>
}

data class PresenceNow(
    val track: Track,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val line: String? = null,
    val kept: Boolean = false,
    val place: Int = 0,
    val of: Int = 0,
)

data class PresenceButton(val label: String, val url: String)

data class PresenceCard(
    val memberText: String,
    val details: String,
    val state: String,
    val detailsUrl: String?,
    val largeImage: String?,
    val largeText: String,
    val smallImage: String?,
    val smallText: String,
    val buttons: List<PresenceButton>,
    val startMs: Long?,
    val endMs: Long?,
) {
    fun activity(display: PresenceDisplay): JsonObject {
        val detailsFit = fit(details)
        val stateFit = fit(state)

        val status = when {
            display == PresenceDisplay.FIRST && detailsFit != null -> 2
            display == PresenceDisplay.SECOND && stateFit != null -> 1
            else -> 0
        }
        return buildJsonObject {
            put("type", 2)
            put("status_display_type", status)
            detailsFit?.let { put("details", it) }
            detailsUrl?.let { put("details_url", it) }
            stateFit?.let { put("state", it) }
            if (startMs != null || endMs != null) {
                putJsonObject("timestamps") {
                    startMs?.let { put("start", it) }
                    endMs?.let { put("end", it) }
                }
            }
            if (largeImage != null || smallImage != null) {
                putJsonObject("assets") {
                    if (largeImage != null) {
                        put("large_image", largeImage)
                        fit(largeText)?.let { put("large_text", it) }
                    }
                    if (smallImage != null) {
                        put("small_image", smallImage)
                        fit(smallText)?.let { put("small_text", it) }
                    }
                }
            }
            if (buttons.isNotEmpty()) {
                putJsonArray("buttons") {
                    buttons.forEach { button ->
                        addJsonObject {
                            put("label", fitButtonLabel(button.label))
                            put("url", button.url)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val APP_NAME = "Valen"
        const val MARK = "valen"
        const val HOMEPAGE = "https://github.com/dodo986/valen"
        const val DEFAULT_HOME_LABEL = "Get Valen"
        const val MAX_BUTTONS = 2

        fun of(
            now: PresenceNow,
            settings: PresenceSettings,
            homepage: String?,
            nowMs: Long,
        ): PresenceCard {
            val track = now.track
            val details = resolve(settings.details, now)
            val state = resolve(settings.state, now)
            val memberText = when (settings.display) {
                PresenceDisplay.NAME -> APP_NAME
                PresenceDisplay.FIRST -> details.ifBlank { APP_NAME }
                PresenceDisplay.SECOND -> state.ifBlank { details.ifBlank { APP_NAME } }
            }
            val link = track.webLink()?.takeIf { it.startsWith("https://") }
            val artwork = track.artworkUrl?.takeIf { it.startsWith("https://") && it.length <= ART_URL_LIMIT }
            val hasCover = settings.cover && artwork != null
            val largeImage = when {
                hasCover -> artwork
                settings.mark -> MARK
                else -> null
            }
            val smallImage = if (hasCover && settings.mark) MARK else null
            val buttons = buildList {
                if (settings.songButton && link != null) add(PresenceButton(buttonLabel(track), link))
                if (settings.homeButton && size < MAX_BUTTONS) {
                    val url = homepage?.takeIf { it.startsWith("https://") && it.length <= BUTTON_URL_LIMIT } ?: HOMEPAGE
                    add(PresenceButton(DEFAULT_HOME_LABEL, url))
                }
            }
            val (start, end) = timestamps(now, settings.time, nowMs)
            return PresenceCard(
                memberText = memberText,
                details = details,
                state = state,
                detailsUrl = link,
                largeImage = largeImage,
                largeText = resolve(settings.largeText, now),
                smallImage = smallImage,
                smallText = resolve(settings.smallText, now),
                buttons = buttons,
                startMs = start,
                endMs = end,
            )
        }

        fun needsLine(settings: PresenceSettings): Boolean =
            listOf(settings.details, settings.state, settings.largeText, settings.smallText).any { template ->
                TOKEN.findAll(template).any { match ->
                    match.groupValues[1].split('|').any { it.trim().equals("line", ignoreCase = true) }
                }
            }

        fun resolve(template: String, now: PresenceNow): String {
            val values = values(now)
            val filled = TOKEN.replace(template) { match ->
                val names = match.groupValues[1].split('|').map { it.trim().lowercase() }
                var unknown = false
                var found = ""
                for (name in names) {
                    val value = values[name]
                    if (value == null) {
                        unknown = true
                        continue
                    }
                    if (value.isNotBlank()) {
                        found = value
                        break
                    }
                }
                if (found.isNotEmpty()) found else if (unknown) match.value else ""
            }
            return clean(filled)
        }

        fun lineAt(state: LyricsState?, positionMs: Long): String? {
            val lyrics = (state as? LyricsState.Found)?.lyrics ?: return null
            if (lyrics.timing == LyricsTiming.None) return null
            val line = lyrics.lines.lastOrNull { !it.aside && it.startMs <= positionMs } ?: return null
            if (positionMs > line.endMs + LINE_GRACE_MS) return null
            return line.text.trim().takeIf { it.isNotEmpty() }
        }

        fun clockTime(ms: Long): String {
            val total = (ms / 1_000).coerceAtLeast(0)
            val hours = total / 3_600
            val minutes = (total % 3_600) / 60
            val seconds = total % 60
            val tail = "$minutes:${seconds.toString().padStart(2, '0')}"
            return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}" else tail
        }

        private const val LINE_GRACE_MS = 1_500L
        private const val ART_URL_LIMIT = 300
        private const val BUTTON_LABEL_LIMIT = 32
        private const val BUTTON_URL_LIMIT = 512
        private const val TEXT_LIMIT = 128

        private fun values(now: PresenceNow): Map<String, String> {
            val track = now.track
            val album = track.album?.takeIf { it.isNotBlank() && !it.equals(track.title, ignoreCase = true) }.orEmpty()
            val line = now.line?.trim()?.takeIf { it.isNotEmpty() && now.playing }
            val progress = now.positionMs.coerceIn(0, now.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
            return mapOf(
                "title" to track.title,
                "artist" to track.artist,
                "album" to album,
                "service" to track.provider.label,
                "source" to source(now),
                "queue" to queue(now),
                "place" to if (now.of > 1) now.place.toString() else "",
                "of" to if (now.of > 1) now.of.toString() else "",
                "kept" to if (now.kept && track.provider != ProviderId.LOCAL) "kept on this device" else "",
                "line" to line?.let { "♪ $it" }.orEmpty(),
                "status" to if (now.playing) "" else "Paused",
                "played" to if (now.positionMs > 0) clockTime(now.positionMs) else "",
                "elapsed" to if (now.positionMs > 0) clockTime(now.positionMs) else "",
                "left" to if (now.durationMs > 0) clockTime((now.durationMs - progress).coerceAtLeast(0)) else "",
                "length" to if (now.durationMs > 0) clockTime(now.durationMs) else "",
            )
        }

        private fun source(now: PresenceNow): String {
            val provider = now.track.provider
            return when {
                provider == ProviderId.LOCAL -> "from this device"
                now.kept -> "from ${provider.label}, kept on this device"
                else -> "from ${provider.label}"
            }
        }

        private fun queue(now: PresenceNow): String =
            if (now.of > 1 && now.place > 0) "${now.place} of ${now.of} in the queue" else "one queue for every service"

        private fun buttonLabel(track: Track): String = when (track.provider) {
            ProviderId.SPOTIFY -> "Play on Spotify"
            ProviderId.YOUTUBE_MUSIC -> "Play on YouTube Music"
            ProviderId.SOUNDCLOUD -> "Play on SoundCloud"
            ProviderId.LOCAL -> "Open"
        }

        private fun timestamps(now: PresenceNow, time: PresenceTime, nowMs: Long): Pair<Long?, Long?> {
            if (!now.playing) return null to null
            val duration = now.durationMs
            val position = now.positionMs.coerceAtLeast(0)
            val start = nowMs - position
            return when (time) {
                PresenceTime.OFF -> null to null
                PresenceTime.ELAPSED -> start to null
                PresenceTime.REMAINING ->
                    if (duration > 0) null to nowMs + (duration - position).coerceAtLeast(0) else null to null
                PresenceTime.PROGRESS ->
                    if (duration > 0) start to start + duration else null to null
            }
        }

        private val TOKEN = Regex("\\{([^{}]*)}")

        private const val SEPARATORS = "·•|/–—-"

        private val SEPARATOR_RUN = Regex("[·•|/–—-](?:\\s*[·•|/–—-])+")
        private val WHITESPACE = Regex("\\s+")

        private fun clean(text: String): String {
            var s = WHITESPACE.replace(text, " ").trim()
            if (s.isEmpty()) return ""

            s = SEPARATOR_RUN.replace(s, " · ")
            s = WHITESPACE.replace(s, " ").trim()
            while (s.isNotEmpty() && s.first() in SEPARATORS) s = s.drop(1).trimStart()
            while (s.isNotEmpty() && s.last() in SEPARATORS) s = s.dropLast(1).trimEnd()
            return WHITESPACE.replace(s, " ").trim()
        }

        private fun fit(text: String): String? {
            val clean = WHITESPACE.replace(text, " ").trim()
            if (clean.isEmpty()) return null
            val cut = if (clean.length > TEXT_LIMIT) clean.take(TEXT_LIMIT - 1) + "…" else clean
            return cut.padEnd(2, ' ')
        }

        private fun fitButtonLabel(text: String): String {
            val clean = WHITESPACE.replace(text, " ").trim().ifEmpty { DEFAULT_HOME_LABEL }
            return if (clean.length > BUTTON_LABEL_LIMIT) clean.take(BUTTON_LABEL_LIMIT - 1) + "…" else clean
        }
    }
}
