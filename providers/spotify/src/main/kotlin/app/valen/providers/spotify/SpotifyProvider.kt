/* SPDX-License-Identifier: GPL-3.0-only */
// Valen, GPL-3.0. Playback goes through the Orpheon Web Player implementation vendored in
// :libs:spotify (https://github.com/kota-zo/Orpheon); see NOTICE.
package app.valen.providers.spotify

import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerState
import app.myspoti.api.Track as SpotifyTrack
import app.myspoti.spotify.SpotifyApi
import app.myspoti.spotify.bridge.BridgeHost
import app.valen.core.AudioQuality
import app.valen.core.MusicProvider
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.ProviderSignIn
import app.valen.core.SessionState
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Spotify behind [MusicProvider], on the vendored Web Player implementation in `:libs:spotify`.
 *
 * [host] runs the official Web Player page (Android WebView or desktop Chrome); sign-in belongs to
 * the platform's [ProviderSignIn]. The adapter plays one track at a time by using its uri as the
 * play context: Valen owns the queue, Spotify never gets one.
 */
class SpotifyProvider(
    private val host: BridgeHost,
    private val signIn: ProviderSignIn,
    scope: CoroutineScope,
    http: OkHttpClient = OkHttpClient(),
) : MusicProvider {

    override val id: ProviderId = ProviderId.SPOTIFY

    /** The vendored API. Hosts call [SpotifyApi.onPageLoaded] after each page load and [SpotifyApi.onPageStarting] when a load starts. */
    val api: SpotifyApi = SpotifyApi(host, scope, http)

    override val session: StateFlow<SessionState> = signIn.state

    /** Child of the caller's scope, so [close] stops this adapter's watchers and snapshot flow. */
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + job)

    private val _events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ProviderEvent> = _events

    @Volatile private var volume: Float = 1f

    /** When the page's own volume was last set: a play right after it does not ask again. */
    @Volatile private var volumeSetAt = 0L

    /** Set by [pause] and cleared by [play]/[resume]: a pause the user asked for is never a track end. */
    @Volatile private var pausedByUser = false

    override val player: StateFlow<PlayerSnapshot?> = api.player.state
        .map { it?.toSnapshot() }
        .stateIn(ownScope, SharingStarted.Eagerly, null)

    init {
        ownScope.launch { watchPlayback() }
        ownScope.launch { watchSession() }
        // Spotify's own answer when it will not play: Orpheon's wording, shown as it is.
        ownScope.launch {
            api.player.refusedAll.collect { _events.emit(ProviderEvent.Failed("Spotify refused to play this")) }
        }
        ownScope.launch {
            api.player.licencesRefused.collect { _events.emit(ProviderEvent.Failed("Spotify refused to play this")) }
        }
    }

    override suspend fun connect() = signIn.connect()

    override suspend fun disconnect() = signIn.disconnect()

    override suspend fun search(query: String, limit: Int): List<Track> {
        // A search at startup often beat the Web Player's own start: give it a moment instead of
        // refusing with "not ready".
        if (!api.ready.value) withTimeoutOrNull(8_000) { api.ready.first { it } }
        if (!api.ready.value) throw IllegalStateException("Spotify is still starting; try again in a moment")
        return api.search.tracks(query, 0, limit).items.map { it.toCore() }
    }

    override suspend fun play(track: Track, startMs: Long) {
        val uri = track.uri ?: "spotify:track:${track.id}"
        // A page that was just reopened (its browser was closed and is being restarted) is not
        // ready for a play yet. Waiting here is better than losing the song the user asked for.
        if (!api.ready.value) withTimeoutOrNull(15_000) { api.ready.first { it } }
        // A hidden Web Player tab never loads the media the play asks for.
        runCatching { host.activate() }
        pausedByUser = false
        // Before the play: the page starts every song at its own level otherwise. Its own answer is
        // slow on an idle page (30 s, the first play of a session), its level is not: never wait,
        // and not twice in a row (the queue sets the volume just before it plays).
        if (System.currentTimeMillis() - volumeSetAt > VOLUME_FRESH_MS) {
            api.player.setVolumeQuickly(volume.toDouble())
            volumeSetAt = System.currentTimeMillis()
        }
        // A track uri is a valid one-song context for the Web Player, which reuses its play call.
        api.player.play(uri, positionMs = startMs.takeIf { it > 0 })
    }

    override suspend fun pause() {
        pausedByUser = true
        api.player.pause()
    }

    override suspend fun resume() {
        pausedByUser = false
        api.player.resume()
    }

    override suspend fun seek(positionMs: Long) = api.player.seek(positionMs)

    override suspend fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        this.volume = v
        // A drag sends these one after another: the page's slow answer is not worth waiting for.
        api.player.setVolumeQuickly(v.toDouble())
        volumeSetAt = System.currentTimeMillis()
    }

    /** Spotify picks the stream's quality itself; the Web Player keeps its own setting. */
    override suspend fun setQuality(quality: AudioQuality) {}

    override suspend fun close() {
        // The vendored API has no close of its own: its work runs in the caller's scope. Stopping this
        // adapter's child scope ends the snapshot flow and both watchers.
        ownScope.cancel()
    }

    // ---- mapping -------------------------------------------------------------------------

    private fun SpotifyTrack.toCore(): Track = Track(
        provider = ProviderId.SPOTIFY,
        id = uri.substringAfterLast(':'),
        title = name,
        artist = artists.joinToString(", ") { it.name },
        album = album?.name?.takeIf { it.isNotBlank() },
        // The largest cover Spotify offers, as Orpheon's album images carry it.
        artworkUrl = album?.images?.maxByOrNull { it.width ?: 0 }?.url,
        durationMs = durationMs,
        explicit = explicit,
        uri = uri,
    )

    private fun NowPlaying.toCore(): Track = Track(
        provider = ProviderId.SPOTIFY,
        id = uri.substringAfterLast(':'),
        title = name,
        artist = artists.joinToString(", ") { it.name },
        album = album?.name?.takeIf { it.isNotBlank() },
        artworkUrl = imageUrl,
        durationMs = durationMs,
        explicit = explicit,
        uri = uri,
    )

    private fun PlayerState.toSnapshot(): PlayerSnapshot {
        val item = item?.takeIf { it.kind != ItemKind.AD }
        return PlayerSnapshot(
            track = item?.toCore(),
            isPlaying = item != null && !isPaused,
            isBuffering = isBuffering,
            positionMs = item?.let { positionAt(System.currentTimeMillis()) } ?: 0L,
            durationMs = item?.durationMs ?: 0L,
            canSeek = restrictions.canSeek,
            // The adapter plays a one-song context; the queue is Valen's, so a skip is always
            // possible: the coordinator plays the next entry, whatever the page allows itself.
            canSkip = true,
            volume = volume,
        )
    }

    // ---- events --------------------------------------------------------------------------

    /**
     * [ProviderEvent.TrackEnded] when a song that was playing stops at (or past) its end, or gives way
     * to the page's next song. A pause the user asked for is not an end.
     */
    private suspend fun watchPlayback() {
        var previous: PlayerState? = null
        api.player.state.collect { state ->
            val before = previous
            previous = state
            if (before == null || before.isPaused) return@collect
            val item = before.item?.takeIf { it.kind != ItemKind.AD } ?: return@collect
            val stopped = state == null || state.isPaused || state.item?.uri != item.uri
            if (!stopped || pausedByUser) return@collect
            val at = before.positionAt(System.currentTimeMillis())
            val nearEnd = item.durationMs <= 0L || at >= item.durationMs - END_TOLERANCE_MS
            if (nearEnd) _events.emit(ProviderEvent.TrackEnded)
        }
    }

    /**
     * [ProviderEvent.SessionLost] when the page stops being ready for good. A quick page reload also
     * drops readiness, so the loss is only reported once it lasts [LOST_AFTER_MS].
     */
    private suspend fun watchSession() {
        var wasReady = false
        api.ready.collect { ready ->
            if (ready) {
                wasReady = true
                return@collect
            }
            if (!wasReady) return@collect
            delay(LOST_AFTER_MS)
            if (!api.ready.value) {
                wasReady = false
                _events.emit(ProviderEvent.SessionLost)
            }
        }
    }

    private companion object {
        /** A song that stops this close to its end (or past it) is over, not paused. */
        const val END_TOLERANCE_MS = 1_500L
        /** How long the page may stay not-ready (a reload) before the session counts as lost. */
        const val LOST_AFTER_MS = 10_000L
        /** A volume set this recent is not asked for again before a play. */
        const val VOLUME_FRESH_MS = 1_000L
    }
}
