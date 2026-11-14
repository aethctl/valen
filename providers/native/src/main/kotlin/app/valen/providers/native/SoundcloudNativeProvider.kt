/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.valen.audio.NativePlayback
import app.valen.audio.SoundcloudAudio
import app.valen.core.AudioQuality
import app.valen.core.MusicProvider
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.ProviderSignIn
import app.valen.core.SessionState
import app.valen.core.Track
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SoundCloud on the desktop with no browser: its own API ([SoundcloudAudio]) for search and the
 * stream, FFmpeg for the sound ([NativePlayback]).
 *
 * The API's `client_id` is read from SoundCloud's page at runtime, never stored in the app.
 * Signing in, when the user asks for it, still goes through the private Chrome; playback does not.
 */
class SoundcloudNativeProvider(
    private val signIn: ProviderSignIn,
    private val playback: NativePlayback,
    private val audio: SoundcloudAudio,
) : MusicProvider {

    override val id: ProviderId = ProviderId.SOUNDCLOUD

    override val session: StateFlow<SessionState> get() = signIn.state

    override val player: StateFlow<PlayerSnapshot?> get() = playback.snapshot

    override val events: SharedFlow<ProviderEvent> get() = playback.events

    override suspend fun connect() = signIn.connect()

    override suspend fun disconnect() = signIn.disconnect()

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return audio.search(query.trim(), limit).map { row ->
            Track(
                provider = ProviderId.SOUNDCLOUD,
                id = row.id,
                title = row.title,
                artist = row.artist,
                artworkUrl = row.artworkUrl,
                durationMs = row.durationMs,
                uri = "soundcloud:track:${row.id}",
            )
        }
    }

    override suspend fun play(track: Track, startMs: Long) = playback.play(track, startMs)

    override suspend fun pause() = playback.pause()

    override suspend fun resume() = playback.resume()

    override suspend fun seek(positionMs: Long) = playback.seek(positionMs)

    override suspend fun setVolume(volume: Float) = playback.setVolume(volume)

    override suspend fun setQuality(quality: AudioQuality) = playback.setQuality(quality)

    override suspend fun close() = playback.release()
}
