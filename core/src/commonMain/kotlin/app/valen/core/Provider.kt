/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

/** The three services Valen speaks to. The UI never switches on these for playback. */
enum class ProviderId(val label: String, val short: String) {
    SPOTIFY("Spotify", "Spotify"),
    YOUTUBE_MUSIC("YouTube Music", "YT Music"),
    SOUNDCLOUD("SoundCloud", "SoundCloud"),
}

/** One playable song, independent of the service it came from. */
data class Track(
    val provider: ProviderId,
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val explicit: Boolean = false,
    /** Provider-specific opaque uri/token (Spotify uri, YTM video id, SoundCloud id). */
    val uri: String? = null,
) {
    val key: String get() = "${provider.name}:$id"
}

sealed interface SessionState {
    data object SignedOut : SessionState
    data object Connecting : SessionState
    data class Connected(val accountName: String? = null) : SessionState
    data class Unavailable(val reason: String) : SessionState
}

data class PlayerSnapshot(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val canSeek: Boolean = true,
    val canSkip: Boolean = true,
    val volume: Float = 1f,
    val error: String? = null,
)

sealed interface ProviderEvent {
    data object TrackEnded : ProviderEvent
    data class Failed(val message: String) : ProviderEvent
    data object SessionLost : ProviderEvent
}

/**
 * Sign-in lives on the platform (a WebView on Android, a Chrome tab on the desktop) and is handed
 * to an adapter as this small contract, so an adapter never knows how a page is shown.
 */
interface ProviderSignIn {
    val state: kotlinx.coroutines.flow.StateFlow<SessionState>
    suspend fun connect()
    suspend fun disconnect()
}

/**
 * One adapter per service. Everything the UI and the queue need is expressed here; the service,
 * its sign-in and its transport stay behind it.
 */
interface MusicProvider {
    val id: ProviderId
    val session: kotlinx.coroutines.flow.StateFlow<SessionState>
    val player: kotlinx.coroutines.flow.StateFlow<PlayerSnapshot?>
    val events: kotlinx.coroutines.flow.SharedFlow<ProviderEvent>

    /** Opens the service's own sign-in page. Never takes credentials itself. */
    suspend fun connect()

    suspend fun disconnect()

    /** Searches the service. Signed-out services may still answer; throw only when truly unavailable. */
    suspend fun search(query: String, limit: Int = 25): List<Track>

    /** Plays exactly this song, replacing whatever the adapter was playing. */
    suspend fun play(track: Track, startMs: Long = 0L)

    suspend fun pause()
    suspend fun resume()
    suspend fun seek(positionMs: Long)
    suspend fun setVolume(volume: Float)

    /** Audio quality is a preference; an adapter that cannot honour it keeps its own. */
    suspend fun setQuality(quality: AudioQuality) {}

    suspend fun close()
}

enum class AudioQuality(val label: String) {
    AUTO("Auto"),
    STANDARD("Standard"),
    HIGH("High"),
}

@kotlinx.serialization.Serializable
data class AppSettings(
    val quality: AudioQuality = AudioQuality.AUTO,
    val volume: Float = 0.85f,
    val lastProvider: ProviderId? = null,
    /**
     * The order services are preferred in, for search results and for which copy of a song plays
     * when more than one service has it. Missing services are appended in their usual order.
     */
    val priority: List<ProviderId> = ProviderId.entries.toList(),
)
