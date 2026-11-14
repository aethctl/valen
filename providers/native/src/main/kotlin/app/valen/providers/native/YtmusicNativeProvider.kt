/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.myspoti.api.Track as CatalogTrack
import app.myspoti.api.best
import app.myspoti.ytmusic.InnerTube
import app.myspoti.ytmusic.Ytm
import app.myspoti.ytmusic.YtmParse
import app.valen.audio.NativePlayback
import app.valen.core.AudioQuality
import app.valen.core.MusicProvider
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.ProviderSignIn
import app.valen.core.SessionState
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * YouTube Music on the desktop with no browser: the web app's search API ([InnerTube]) for
 * looking, NewPipeExtractor for the stream ([YoutubeAudio]), and FFmpeg for the sound
 * ([NativePlayback]).
 *
 * Signing in is still the browser's job, and only when asked: [signIn] opens the private Chrome on
 * its own page on `connect()` and never on a play, so a machine with no browser at all still plays,
 * searches and skips.
 */
class YtmusicNativeProvider(
    private val signIn: ProviderSignIn,
    private val playback: NativePlayback,
    scope: CoroutineScope,
    private val api: InnerTube = InnerTube(OkHttpClient(), cookies = { null }),
) : MusicProvider {

    override val id: ProviderId = ProviderId.YOUTUBE_MUSIC

    override val session: StateFlow<SessionState> get() = signIn.state

    override val player: StateFlow<PlayerSnapshot?> get() = playback.snapshot

    override val events: SharedFlow<ProviderEvent> get() = playback.events

    override suspend fun connect() = signIn.connect()

    override suspend fun disconnect() = signIn.disconnect()

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return YtmParse.search(api.search(query.trim()))
            .filterIsInstance<YtmParse.Item.Song>()
            .mapNotNull { it.track.toCore() }
            .distinctBy { it.id }
            .take(limit)
    }

    override suspend fun play(track: Track, startMs: Long) = playback.play(track, startMs)

    override suspend fun pause() = playback.pause()

    override suspend fun resume() = playback.resume()

    override suspend fun seek(positionMs: Long) = playback.seek(positionMs)

    override suspend fun setVolume(volume: Float) = playback.setVolume(volume)

    /** The next song is resolved at the new quality; the current one keeps its stream. */
    override suspend fun setQuality(quality: AudioQuality) = playback.setQuality(quality)

    override suspend fun close() = playback.release()

    /** One of the web app's rows as Valen's track, with the YouTube Music cover as its artwork. */
    private fun CatalogTrack.toCore(): Track? {
        val videoId = uri.substringAfterLast(':').takeIf { Ytm.isVideoId(it) } ?: return null
        return Track(
            provider = ProviderId.YOUTUBE_MUSIC,
            id = videoId,
            title = name,
            artist = artists.joinToString(", ") { it.name }.ifBlank { "YouTube Music" },
            album = album?.name?.takeIf { it.isNotBlank() },
            artworkUrl = album?.images?.best(300)?.url ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            durationMs = durationMs,
            explicit = explicit,
            uri = "${Ytm.PROVIDER}:track:$videoId",
        )
    }
}
