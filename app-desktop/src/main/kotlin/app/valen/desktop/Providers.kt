/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import app.valen.core.AudioQuality
import app.valen.core.MusicProvider
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.audio.FfplayPlayer
import app.valen.audio.NativePlayback
import app.valen.audio.SoundcloudAudio
import app.valen.audio.YoutubeAudio
import app.valen.core.Track
import app.valen.host.desktop.DesktopHosts
import app.valen.providers.native.SoundcloudNativeProvider
import app.valen.providers.native.YtmusicNativeProvider
import app.valen.providers.spotify.SpotifyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The adapters this machine could build, and why the others could not. */
class ProviderSet(val providers: List<MusicProvider>, val failures: Map<ProviderId, String>) {
    fun reason(id: ProviderId): String? = failures[id]
}

/**
 * The three adapters. Spotify plays through the Web Player in Valen's private Chrome; YouTube
 * Music and SoundCloud play natively, resolved by [YoutubeAudio] and [SoundcloudAudio] and played
 * by FFmpeg, with no browser involved. Chrome is therefore started by Spotify alone (and by a
 * sign-in button), never at boot.
 *
 * A provider that cannot be built (no Chrome for Spotify, a page that will not open) leaves the
 * app running with the others and a sentence explaining it. A failed provider is kept as
 * [RetryingProvider]: pressing Retry starts Chrome again and, when that works, builds the real
 * adapter in place, so the button is not decorative.
 */
suspend fun buildProviders(hosts: DesktopHosts, scope: CoroutineScope): ProviderSet {
    val providers = ArrayList<MusicProvider>(3)
    val failures = LinkedHashMap<ProviderId, String>()
    val youtube = YoutubeAudio()
    val soundcloud = SoundcloudAudio()
    val builders = listOf(
        Builder(
            id = ProviderId.SPOTIFY,
            // Chrome is only ever started here, and only when Spotify is actually used.
            ensureHost = { hosts.start(); hosts.startError },
            browser = hosts.generation,
            alive = { hosts.browserAlive() },
            unavailable = "Spotify did not start",
            // Its browser is headless and invisible, so there is nothing alarming to show while
            // it comes up; the tile reads "Not connected" for the moment it takes.
            quiet = true,
            build = { buildSpotify(hosts, scope) },
        ),
        Builder(
            id = ProviderId.YOUTUBE_MUSIC,
            ensureHost = { null },
            unavailable = "YouTube Music did not start",
            build = {
                val playback = NativePlayback(FfplayPlayer(scope), scope) { id, quality -> youtube.resolve(id, quality) }
                YtmusicNativeProvider(hosts.ytMusicSignIn, playback, scope)
            },
        ),
        Builder(
            id = ProviderId.SOUNDCLOUD,
            ensureHost = { null },
            unavailable = "SoundCloud did not start",
            build = {
                val playback = NativePlayback(FfplayPlayer(scope), scope) { id, quality -> soundcloud.resolve(id, quality) }
                SoundcloudNativeProvider(hosts.soundCloudSignIn, playback, soundcloud)
            },
        ),
    )

    for (builder in builders) {
        val id = builder.id
        val result = runCatching { builder.build() }
        val provider = result.getOrNull()
        val chosen: MusicProvider
        if (provider != null) {
            providers += provider
            chosen = provider
        } else {
            // A page-level exception ("Failed to connect to /127.0.0.1:9333") is noise before
            // Chrome was even asked to start; Spotify says what will happen instead.
            val technical = result.exceptionOrNull()?.message
            val reason = if (id == ProviderId.SPOTIFY && hosts.startError == null) {
                builder.unavailable
            } else {
                hosts.startError ?: technical ?: builder.unavailable
            }
            failures[id] = reason
            chosen = RetryingProvider(
                id = id,
                scope = scope,
                ensureHost = builder.ensureHost,
                build = builder.build,
                reason = reason,
                browser = builder.browser,
                alive = builder.alive,
                quiet = builder.quiet,
            )
            providers += chosen
        }
        // A song that will not play says why on stderr too, so a run from a terminal is
        // diagnosable without the window (a placeholder that retries included).
        scope.launch {
            chosen.events.collect { event ->
                if (event is ProviderEvent.Failed) System.err.println("valen: ${id.label}: ${event.message}")
            }
        }
    }

    // Spotify's engine is headless, so warming it costs nothing on screen: the service is ready
    // (and its account shown) by the time the window has drawn. A machine without a browser just
    // leaves the tile saying so.
    scope.launch { (providers.firstOrNull { it.id == ProviderId.SPOTIFY } as? RetryingProvider)?.warm() }

    return ProviderSet(providers, failures)
}

/** One adapter to build, and how to get its host back if the first attempt failed. */
private class Builder(
    val id: ProviderId,
    val ensureHost: suspend () -> String?,
    val unavailable: String,
    val build: suspend () -> MusicProvider,
    /** Set when the adapter holds the browser: a replacement makes it stale. */
    val browser: kotlinx.coroutines.flow.StateFlow<Int>? = null,
    /** Whether the host behind the adapter still answers; false makes the next use rebuild it. */
    val alive: suspend () -> Boolean = { true },
    /** Start from "not connected" rather than an error, for a host that is started silently. */
    val quiet: Boolean = false,
)

/** The Spotify Web Player tab and the bridge the adapter expects after every page load. */
private suspend fun buildSpotify(hosts: DesktopHosts, scope: CoroutineScope): MusicProvider {
    val host = hosts.spotify()
    val provider = SpotifyProvider(host, hosts.spotifySignIn, scope)
    // `VALEN_SPOTIFY_LOG=1` prints the Web Player bridge's own lines: a refusal, a device that
    // never joined, a call that timed out. Off in a normal run.
    if (System.getenv("VALEN_SPOTIFY_LOG") != null) {
        provider.api.bridge.logger = { line -> System.err.println("spotify: $line") }
    }
    scope.launch {
        // A browser that was just started (or restarted after a crash) may still be loading the
        // Web Player when the first injection runs, and a script injected into a page that then
        // navigates is wiped. Keep injecting until the page announces itself; a page that never
        // does (sign-in refused, no network) stops after a minute and waits for load events.
        var tries = 0
        while (isActive && !provider.api.ready.value && tries < 40) {
            if (tries > 0) delay(1_500)
            tries++
            runCatching { provider.api.onPageLoaded() }
            withTimeoutOrNull(3_000) { provider.api.ready.first { it } }
        }
        while (isActive) {
            runCatching { host.awaitLoad() }.onFailure { break }
            runCatching { provider.api.onPageLoaded() }
        }
    }
    return provider
}

/**
 * A provider whose adapter could not be built yet. Its session says why; a search or a play asks it
 * to try again, and a success replaces the placeholder with the real adapter (which then drives the
 * same flows the coordinator already collects).
 */
private class RetryingProvider(
    override val id: ProviderId,
    private val scope: CoroutineScope,
    private val ensureHost: suspend () -> String?,
    private val build: suspend () -> MusicProvider,
    reason: String,
    private val browser: StateFlow<Int>? = null,
    private val alive: suspend () -> Boolean = { true },
    private val quiet: Boolean = false,
) : MusicProvider {

    private val _session = MutableStateFlow<SessionState>(
        if (quiet) SessionState.SignedOut else SessionState.Unavailable(reason),
    )
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _player = MutableStateFlow<PlayerSnapshot?>(null)
    override val player: StateFlow<PlayerSnapshot?> = _player.asStateFlow()

    private val _events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<ProviderEvent> = _events

    /** Builds the real adapter now; used to warm a silent host at start-up. */
    suspend fun warm() {
        ready()
    }

    @Volatile private var delegate: MusicProvider? = null

    /** The browser generation [delegate] was built against; null when nothing is attached. */
    @Volatile private var attachedTo: Int? = null

    /** What to restart if the browser dies under a song: pressing play brings it back. */
    @Volatile private var lastTrack: Track? = null
    @Volatile private var lastPosition: Long = 0L

    private val lock = Mutex()

    init {
        // A browser that was replaced (a headless engine made visible for a sign-in) leaves the
        // attached adapter talking to a dead page: drop it so the next use builds a fresh one.
        if (browser != null) {
            scope.launch {
                var seen = browser.value
                browser.collect { now ->
                    if (now == seen) return@collect
                    seen = now
                    lock.withLock {
                        // A delegate built after the change already belongs to the new browser.
                        if (attachedTo == now) return@withLock
                        delegate?.let { runCatching { it.close() } }
                        delegate = null
                        attachedTo = null
                        _player.value = _player.value?.copy(isPlaying = false, isBuffering = false)
                        _session.value = if (quiet) {
                            SessionState.SignedOut
                        } else {
                            SessionState.Unavailable("The browser was restarted")
                        }
                    }
                }
            }
            // A browser can also die without a replacement: the window closed, the engine
            // crashed. Without this the app would keep showing a song that stopped minutes ago.
            scope.launch {
                var misses = 0
                while (isActive) {
                    delay(3_000)
                    val current = delegate
                    if (current == null) {
                        misses = 0
                        continue
                    }
                    if (alive()) {
                        misses = 0
                        continue
                    }
                    // One failed probe can be a hiccup; two in a row is a browser that is gone.
                    if (++misses < 2) continue
                    misses = 0
                    lock.withLock {
                        if (delegate !== current) return@withLock
                        runCatching { current.close() }
                        delegate = null
                        attachedTo = null
                        _player.value = _player.value?.copy(isPlaying = false, isBuffering = false)
                        _session.value = if (quiet) {
                            SessionState.SignedOut
                        } else {
                            SessionState.Unavailable("${id.label}'s browser was closed")
                        }
                        _events.tryEmit(
                            ProviderEvent.Failed("${id.label}'s browser was closed. Press play to reopen it."),
                        )
                    }
                }
            }
        }
    }

    private fun reasonNow(): String =
        (_session.value as? SessionState.Unavailable)?.reason ?: "${id.label} is not available"

    /** The adapter, built on first use; null (with [session] saying why) when it still cannot be. */
    private suspend fun ready(): MusicProvider? {
        val current = delegate
        if (current != null) {
            if (alive()) return current
            // The browser behind Spotify was closed or crashed: forget the adapter and start it
            // again (headless) rather than failing every later play.
            lock.withLock {
                if (delegate === current) {
                    runCatching { current.close() }
                    delegate = null
                    attachedTo = null
                    _player.value = null
                    _session.value = SessionState.SignedOut
                }
            }
        }
        return lock.withLock {
            delegate?.let { return it }
            ensureHost()?.let { hostError ->
                _session.value = SessionState.Unavailable(hostError)
                return null
            }
            val built = runCatching { build() }.getOrElse {
                _session.value = SessionState.Unavailable(it.message ?: "${id.label} did not start")
                return null
            }
            attach(built)
            built
        }
    }

    private fun attach(built: MusicProvider) {
        delegate = built
        attachedTo = browser?.value
        _session.value = built.session.value
        scope.launch { built.session.collect { _session.value = it } }
        scope.launch {
            built.player.collect { snapshot ->
                _player.value = snapshot
                val track = snapshot?.track
                if (track != null) {
                    lastTrack = track
                    lastPosition = snapshot.positionMs
                }
            }
        }
        scope.launch { built.events.collect { _events.emit(it) } }
    }

    override suspend fun connect() {
        ready()?.connect()
    }

    override suspend fun disconnect() {
        delegate?.disconnect()
    }

    override suspend fun search(query: String, limit: Int): List<Track> =
        ready()?.search(query, limit) ?: throw IllegalStateException(reasonNow())

    override suspend fun play(track: Track, startMs: Long) {
        val provider = ready() ?: throw IllegalStateException(reasonNow())
        provider.play(track, startMs)
    }

    override suspend fun pause() {
        delegate?.pause()
    }

    override suspend fun resume() {
        val before = delegate
        val provider = ready() ?: return
        val track = lastTrack
        // `ready()` had to build a new adapter (its browser died): the new page has no song, so
        // "resume" means starting the last one again where it stopped. Its own state still names
        // the old song, which is why this is decided by identity and not by what it reports.
        if (track != null && provider !== before) {
            runCatching { provider.play(track, lastPosition) }
                .onFailure {
                    _events.tryEmit(ProviderEvent.Failed(it.message ?: "${id.label} could not resume"))
                }
            return
        }
        provider.resume()
    }

    override suspend fun seek(positionMs: Long) {
        delegate?.seek(positionMs)
    }

    override suspend fun setVolume(volume: Float) {
        delegate?.setVolume(volume)
    }

    override suspend fun setQuality(quality: AudioQuality) {
        delegate?.setQuality(quality)
    }

    override suspend fun close() {
        delegate?.close()
    }
}
