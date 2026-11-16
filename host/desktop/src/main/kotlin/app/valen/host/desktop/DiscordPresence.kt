/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.LyricsState
import app.valen.core.LyricsTiming
import app.valen.core.PlaybackCoordinator
import app.valen.core.PresenceLink
import app.valen.core.PresenceSettings
import app.valen.core.PresenceStatus
import app.valen.core.ProviderId
import app.valen.core.Track
import app.valen.core.webLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * "Listening to Valen" on Discord: the song, its cover with Valen's mark, the time bar, where it
 * plays from (Spotify, YouTube Music, SoundCloud, this device — the one thing no single service's
 * card can say), and, when asked, the line being sung right now.
 *
 * Settings → Discord turns it on; the card is named after Valen's own Discord application
 * ([DEFAULT_APP_ID]), which every copy uses, so nothing has to be set up. `VALEN_DISCORD_APP_ID`
 * is the one override, for a developer's own application.
 * Discord takes at most five activity changes in twenty seconds, so changes are sent at most every
 * [MIN_GAP_MS], the latest winning; a seek is sent only when it moves the bar noticeably. Discord
 * not running is a quiet state that is looked at again every [LOOK_AGAIN_MS]; it never shows as an
 * error in the app.
 */
class DiscordPresence(
    private val scope: CoroutineScope,
    /** Valen's page, for the second button; `VALEN_HOMEPAGE` may name another. */
    private val homepage: String? = System.getenv("VALEN_HOMEPAGE")?.takeIf { it.startsWith("https://") } ?: HOMEPAGE,
    private val envAppId: String? = System.getenv("VALEN_DISCORD_APP_ID")?.trim()?.takeIf { it.isNotEmpty() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : PresenceLink {

    private val _status = MutableStateFlow<PresenceStatus>(PresenceStatus.Off)
    override val status: StateFlow<PresenceStatus> = _status.asStateFlow()

    private var loop: Job? = null
    private lateinit var coordinator: PlaybackCoordinator

    /** Starts following [player]: the link waits for Settings, then for Discord. */
    fun start(player: PlaybackCoordinator) {
        if (loop != null) return
        coordinator = player
        loop = scope.launch(Dispatchers.IO) { run() }
    }

    private suspend fun run() {
        while (scope.isActive) {
            val settings = coordinator.settings.value.presence
            val id = appId(envAppId)
            when {
                !settings.enabled -> {
                    _status.value = PresenceStatus.Off
                    awaitChange(settings, Long.MAX_VALUE)
                }
                !id.all { it.isDigit() } -> {
                    _status.value = PresenceStatus.NeedsAppId
                    awaitChange(settings, Long.MAX_VALUE)
                }
                else -> {
                    _status.value = PresenceStatus.Connecting
                    val ipc = try {
                        DiscordIpc.open(id)
                    } catch (e: DiscordIpc.Refused) {
                        _status.value = PresenceStatus.Failed(refusal(e.message))
                        awaitChange(settings, REFUSED_AGAIN_MS)
                        continue
                    }
                    if (ipc == null) {
                        _status.value = PresenceStatus.NoDiscord
                        awaitChange(settings, LOOK_AGAIN_MS)
                        continue
                    }
                    _status.value = PresenceStatus.Connected(ipc.user)
                    try {
                        session(ipc, id)
                    } catch (e: CancellationException) {
                        runCatching { ipc.setActivity(null) }
                        ipc.close()
                        throw e
                    } catch (_: IOException) {
                        // Discord quit or restarted: look for it again.
                    }
                    ipc.close()
                }
            }
        }
    }

    /** Waits until the presence settings differ from [was], or [ms] passes. */
    private suspend fun awaitChange(was: PresenceSettings, ms: Long) {
        withTimeoutOrNull(ms) { coordinator.settings.first { it.presence != was } }
    }

    /** One connection: reads Discord's answers beside it, sends what changes, ends when it breaks. */
    private suspend fun session(ipc: DiscordIpc, id: String) {
        val reader = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val answer = ipc.next()
                    if (answer["evt"]?.toString()?.contains("ERROR") == true) {
                        val message = (answer["data"] as? JsonObject)?.get("message")?.toString()?.trim('"')
                        System.err.println("valen: Discord did not take the activity: $message")
                    }
                }
            } catch (_: Exception) {
                // The sender notices on its next write.
            }
        }
        var sent: Sent? = null
        var lastSendAt = 0L
        var pausedSince = 0L
        var askedLyricsFor: String? = null
        try {
            while (scope.isActive && reader.isActive) {
                val settings = coordinator.settings.value.presence
                if (!settings.enabled || appId(envAppId) != id) {
                    runCatching { ipc.setActivity(null) }
                    return
                }
                val snap = coordinator.snapshot.value
                val track = snap?.track
                val now = clock()
                val playing = snap?.isPlaying == true || snap?.isBuffering == true
                pausedSince = if (track != null && !playing) (if (pausedSince == 0L) now else pausedSince) else 0L
                val expired = settings.pausedClearMinutes > 0 && pausedSince > 0 && now - pausedSince > settings.pausedClearMinutes * 60_000L
                val line = if (track != null && settings.lyricLine && playing) {
                    if (askedLyricsFor != track.key && coordinator.lyrics.known(track) == null) {
                        askedLyricsFor = track.key
                        scope.launch { runCatching { coordinator.lyrics.lyricsFor(track) } }
                    }
                    lineAt(coordinator.lyrics.known(track), snap.positionMs)
                } else {
                    null
                }
                val want = if (track == null || expired) {
                    null
                } else {
                    val queue = coordinator.queue.value
                    PresenceInput(
                        track = track,
                        playing = playing,
                        positionMs = snap.positionMs,
                        durationMs = snap.durationMs.takeIf { it > 0 } ?: track.durationMs,
                        line = line,
                        kept = coordinator.isKept(track),
                        place = coordinator.index.value + 1,
                        of = queue.size,
                    )
                }
                val next = want?.let { Sent(it.copy(positionMs = 0), now - it.positionMs) }
                val changed = when {
                    next == null -> sent != null
                    sent == null -> true
                    next.input != sent.input -> true
                    // The same song, moved by a seek (or the clock drifting): only a visible move.
                    next.input.playing && kotlin.math.abs(next.startedAt - sent.startedAt) > DRIFT_MS -> true
                    else -> false
                }
                if (changed && now - lastSendAt >= MIN_GAP_MS) {
                    ipc.setActivity(want?.let { activity(it, settings, homepage, now) })
                    sent = next
                    lastSendAt = now
                }
                delay(TICK_MS)
            }
        } finally {
            reader.cancel()
        }
    }

    private fun refusal(message: String?): String = when {
        message == null -> "Discord refused the link"
        message.contains("client id", ignoreCase = true) || message.contains("4000") ->
            "Discord does not know that application id"
        else -> message
    }

    private data class Sent(val input: PresenceInput, val startedAt: Long)

    /** Everything the card is made from, at one moment. */
    data class PresenceInput(
        val track: Track,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        /** The line being sung, when that is shown and known. */
        val line: String? = null,
        /** Downloaded: it plays from this device whatever service it is on. */
        val kept: Boolean = false,
        val place: Int = 0,
        val of: Int = 0,
    )

    companion object {
        const val MIN_GAP_MS = 4_100L
        const val DRIFT_MS = 2_500L
        const val TICK_MS = 1_000L
        const val LOOK_AGAIN_MS = 15_000L
        const val REFUSED_AGAIN_MS = 120_000L

        /** The art asset every Valen application carries: its mark. */
        const val MARK = "valen"

        /** Valen's page: the card's second button ("Get Valen"). */
        const val HOMEPAGE = "https://github.com/dodo986/valen"

        /**
         * The Discord application every card is named after: Valen's own, created once and shipped
         * with the app. An application id is a public identifier, not a token — any Discord client
         * may use it, and nothing about the person's account reaches it.
         */
        const val DEFAULT_APP_ID = "1571814573663584338"

        /**
         * The application every card is named after: Valen's own, unless `VALEN_DISCORD_APP_ID`
         * names another. Never empty, so a session always has an id to hand over.
         */
        fun appId(env: String?): String = env?.trim().orEmpty().ifEmpty { DEFAULT_APP_ID }

        /** The line sung at [positionMs], from timed lyrics already found. */
        fun lineAt(state: LyricsState?, positionMs: Long): String? {
            val lyrics = (state as? LyricsState.Found)?.lyrics ?: return null
            if (lyrics.timing == LyricsTiming.None) return null
            val line = lyrics.lines.lastOrNull { !it.aside && it.startMs <= positionMs } ?: return null
            if (positionMs > line.endMs + 1_500) return null
            return line.text.trim().takeIf { it.isNotEmpty() }
        }

        /**
         * The activity Discord is sent: "Listening to Valen" in the member list, the song on the
         * card. Every text is cut to Discord's 128 characters (and given its two at least).
         *
         * The mark rides in `small_image`, which Discord draws as a badge over the cover's corner —
         * RPC has no compositing, and a local file or `data:` URL never reaches another client.
         * `buttons` is only drawn on other people's screens: Discord hides buttons from the account
         * that sets them, so the card on one's own profile is no evidence either way.
         */
        fun activity(input: PresenceInput, settings: PresenceSettings, homepage: String?, now: Long): JsonObject {
            val t = input.track
            val link = t.webLink()?.takeIf { it.startsWith("https://") }
            val cover = t.artworkUrl?.takeIf { settings.cover && it.startsWith("https://") && it.length <= 300 }
            val from = when {
                t.provider == ProviderId.LOCAL -> "from this device"
                input.kept -> "from ${t.provider.label}, kept on this device"
                else -> "from ${t.provider.label}"
            }
            val album = t.album?.takeIf { it.isNotBlank() && !it.equals(t.title, ignoreCase = true) }
            val largeText = listOfNotNull(album, from.takeIf { settings.service }).joinToString(" · ").ifEmpty { t.title }
            val smallText = when {
                !input.playing -> "Paused in Valen"
                input.of > 1 -> "Valen · ${input.place} of ${input.of} in the queue"
                else -> "Valen · one queue for every service"
            }
            val state = when {
                input.line != null -> "♪ ${input.line}"
                !input.playing -> "Paused · ${t.artist}"
                else -> t.artist
            }
            return buildJsonObject {
                put("type", 2) // Listening
                put("status_display_type", 0) // the member list says "Listening to Valen"
                put("details", fit(t.title))
                link?.let { put("details_url", it) }
                put("state", fit(state.ifBlank { "Valen" }))
                if (input.playing && settings.progress && input.durationMs > 0) {
                    val start = now - input.positionMs.coerceAtLeast(0)
                    putJsonObject("timestamps") {
                        put("start", start)
                        put("end", start + input.durationMs)
                    }
                }
                putJsonObject("assets") {
                    put("large_image", cover ?: MARK)
                    put("large_text", fit(largeText))
                    if (cover != null) {
                        put("small_image", MARK)
                        put("small_text", fit(smallText))
                    }
                }
                val buttons = buildList {
                    if (settings.buttons && link != null) add(buttonLabel(t) to link)
                    if (settings.buttons && homepage != null) add("Get Valen" to homepage)
                }
                if (buttons.isNotEmpty()) {
                    putJsonArray("buttons") {
                        buttons.forEach { (label, url) -> addJsonObject { put("label", label); put("url", url) } }
                    }
                }
            }
        }

        private fun buttonLabel(t: Track): String = when (t.provider) {
            ProviderId.SPOTIFY -> "Play on Spotify"
            ProviderId.YOUTUBE_MUSIC -> "Play on YouTube Music"
            ProviderId.SOUNDCLOUD -> "Play on SoundCloud"
            ProviderId.LOCAL -> "Open"
        }

        private fun fit(text: String): String {
            val clean = text.replace('\n', ' ').trim()
            val cut = if (clean.length > 128) clean.take(127) + "…" else clean
            return cut.padEnd(2, ' ')
        }
    }
}
