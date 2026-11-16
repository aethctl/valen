/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.soundcloud

import app.myspoti.web.PageBridge
import app.myspoti.web.PageException
import app.myspoti.web.PageHost
import app.valen.core.AudioQuality
import app.valen.core.MusicProvider
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.SoundWebAudio
import app.valen.core.SoundEffects
import app.valen.core.EffectSupport
import app.valen.core.ProviderSignIn
import app.valen.core.SessionState
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SoundCloud, played by the official site in the hidden page ([host]).
 *
 * The page script (`soundcloud-page.js`) resolves a track through SoundCloud's own API with the
 * browser's session, picks its progressive stream for [AudioQuality] and plays that one stream in
 * its own `<audio>` element; the adapter never holds a track URL, a client id or a token. Search and
 * play both go through the same page, so the requests look like the web app's own.
 *
 * Only one page is asked for: [ORIGIN] is loaded once and [PageBridge] injects the script after
 * every load of it. A track with no progressive stream fails with "HLS-only track" and is reported
 * as [ProviderEvent.Failed].
 */
class SoundcloudProvider(
    private val host: PageHost,
    private val signIn: ProviderSignIn,
    scope: CoroutineScope,
) : MusicProvider {

    override val id: ProviderId = ProviderId.SOUNDCLOUD

    /** Sign-in lives on the platform's browser; the adapter only reports it. */
    override val session: StateFlow<SessionState> = signIn.state

    // The page's script, and Valen's sound engine beside it (injected with it after every load).
    private val bridge = PageBridge(host, SoundcloudScript.SCRIPT + "\n;" + SoundWebAudio.SCRIPT, scope)

    /** The effects last asked for, put on again whenever a page comes up. */
    @Volatile private var effects: SoundEffects = SoundEffects.FLAT

    private val _player = MutableStateFlow<PlayerSnapshot?>(null)
    override val player: StateFlow<PlayerSnapshot?> = _player.asStateFlow()

    private val _events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ProviderEvent> = _events.asSharedFlow()

    /** The song the caller asked for; the page only has its id, this fills title and artwork. */
    @Volatile private var current: Track? = null
    @Volatile private var quality: AudioQuality = AudioQuality.AUTO
    @Volatile private var volume: Float = 1f

    @Volatile private var endedSeen = false
    @Volatile private var errorSeen: String? = null

    init {
        scope.launch {
            bridge.events.collect { (type, value) ->
                if (type == "state") onState(value)
            }
        }
        // A page gone took its audio with it: the snapshot it left is not current any more.
        scope.launch {
            bridge.ready.collect { ready ->
                if (ready) applyEffects()
                if (!ready) {
                    endedSeen = false
                    errorSeen = null
                    _player.value = null
                }
            }
        }
        // The site, once; PageBridge injects the script after each load of it.
        scope.launch { runCatching { host.navigate(ORIGIN) } }
    }

    override suspend fun connect() = signIn.connect()

    /** The page's own audio element is routed through Web Audio ([SoundWebAudio]). */
    override val effectSupport: EffectSupport get() = EffectSupport.Full

    override suspend fun setEffects(effects: SoundEffects) {
        this.effects = effects
        applyEffects()
    }

    private suspend fun applyEffects() {
        if (!bridge.ready.value) return
        runCatching { host.evaluate(SoundWebAudio.call(effects)) }
    }

    override suspend fun disconnect() {
        runCatching { if (bridge.ready.value) bridge.call("stop") }
        current = null
        endedSeen = false
        errorSeen = null
        _player.value = null
        signIn.disconnect()
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank()) return emptyList()
        awaitReady()
        val args = buildJsonObject {
            put("q", query)
            put("limit", limit.coerceIn(1, MAX_LIMIT))
        }
        return SoundcloudApi.parseSearch(bridge.call("search", args, timeoutMs = 25_000), limit)
    }

    override suspend fun play(track: Track, startMs: Long) {
        require(track.provider == id) { "not a SoundCloud track: ${track.provider.label}" }
        awaitReady()
        val at = startMs.coerceAtLeast(0L)
        current = track
        endedSeen = false
        errorSeen = null
        _player.value = PlayerSnapshot(
            track = track,
            isPlaying = false,
            isBuffering = true,
            positionMs = at,
            durationMs = track.durationMs,
            volume = volume,
        )
        val args = buildJsonObject {
            put("id", track.uri ?: track.id)
            put("startMs", at)
            put("quality", quality.name)
            put("volume", volume)
        }
        try {
            // A hidden tab defers media loading; the play would never start.
            runCatching { host.activate() }
            bridge.call("play", args, timeoutMs = PLAY_TIMEOUT_MS)
        } catch (e: PageException) {
            val message = e.message ?: "SoundCloud could not play this"
            _player.value = PlayerSnapshot(
                track = track,
                isPlaying = false,
                isBuffering = false,
                positionMs = at,
                durationMs = track.durationMs,
                volume = volume,
                error = message,
            )
            if (errorSeen != message) {
                errorSeen = message
                _events.tryEmit(ProviderEvent.Failed(message))
            }
            throw e
        }
    }

    override suspend fun pause() {
        if (bridge.ready.value) bridge.call("pause")
    }

    override suspend fun resume() {
        if (bridge.ready.value) bridge.call("resume")
    }

    override suspend fun seek(positionMs: Long) {
        if (bridge.ready.value) {
            bridge.call("seek", buildJsonObject { put("ms", positionMs.coerceAtLeast(0L)) })
        }
    }

    override suspend fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        this.volume = v
        if (bridge.ready.value) runCatching { bridge.call("volume", buildJsonObject { put("v", v) }) }
    }

    /** Kept for the next [play]: STANDARD asks for 128 kbps progressive, HIGH for the highest. */
    override suspend fun setQuality(quality: AudioQuality) {
        this.quality = quality
        if (bridge.ready.value) runCatching { bridge.call("quality", buildJsonObject { put("quality", quality.name) }) }
    }

    override suspend fun close() {
        runCatching { if (bridge.ready.value) bridge.call("stop") }
        current = null
        endedSeen = false
        errorSeen = null
        _player.value = null
    }

    private suspend fun awaitReady(timeoutMs: Long = READY_TIMEOUT_MS) {
        if (bridge.ready.value) return
        withTimeoutOrNull(timeoutMs) { bridge.ready.first { it } }
            ?: throw PageException("the SoundCloud page is not ready")
    }

    /** One state of the page script, as the player and the events see it. */
    private fun onState(value: JsonElement?) {
        val s = SoundcloudApi.parseState(value) ?: return
        val track = current
        val duration = if (s.durationMs > 0) s.durationMs else track?.durationMs ?: 0L
        _player.value = PlayerSnapshot(
            track = track,
            isPlaying = !s.paused && !s.ended,
            isBuffering = s.buffering,
            positionMs = s.positionMs,
            durationMs = duration,
            volume = volume,
            error = s.error,
        )
        // Ended is a transition, not a level: one TrackEnded per song, and only for the song asked.
        if (s.ended && track != null && (s.trackId == null || s.trackId == track.id)) {
            if (!endedSeen) {
                endedSeen = true
                _events.tryEmit(ProviderEvent.TrackEnded(track.key))
            }
        } else {
            endedSeen = false
        }
        // A media error while playing is the script's error text; the same one is not said twice.
        val error = s.error
        if (error != null && error != errorSeen) {
            errorSeen = error
            _events.tryEmit(ProviderEvent.Failed(error))
        } else if (error == null) {
            errorSeen = null
        }
    }

    companion object {
        /** The official site: the only page the adapter loads. */
        const val ORIGIN: String = "https://soundcloud.com"

        private const val MAX_LIMIT = 50
        private const val READY_TIMEOUT_MS = 20_000L
        private const val PLAY_TIMEOUT_MS = 60_000L
    }
}

/** The page script, bundled beside this file as a module resource (see build.gradle.kts). */
internal object SoundcloudScript {
    val SCRIPT: String by lazy {
        SoundcloudScript::class.java.getResourceAsStream("soundcloud-page.js")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("soundcloud-page.js is missing from the module resources")
    }
}
