/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.AudioQuality
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The half of a native provider that both services share: one song at a time, resolved by a
 * service-specific [resolve], played by the platform's [AudioPlayer], reported as the app's
 * [PlayerSnapshot] and [ProviderEvent]s.
 *
 * The queue stays the app's: [play] replaces whatever was playing, and the end of a stream is
 * reported once per song so the coordinator can move on.
 */
class NativePlayback(
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
    private val resolve: suspend (id: String, quality: AudioQuality) -> ResolvedStream,
) {

    private val _snapshot = MutableStateFlow<PlayerSnapshot?>(null)
    val snapshot: StateFlow<PlayerSnapshot?> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ProviderEvent> = _events.asSharedFlow()

    @Volatile private var current: Track? = null
    @Volatile private var quality: AudioQuality = AudioQuality.AUTO
    @Volatile private var volume: Float = 1f

    /** The song whose end was already reported; [play] arms it again. */
    private var endedFor: String? = null
    private var lastError: String? = null

    init {
        scope.launch {
            player.state.collect { s ->
                val track = current ?: return@collect
                if (!s.hasStream) return@collect
                if (s.error != null && s.error != lastError) {
                    lastError = s.error
                    // A stream FFmpeg could not open is a failure the app should say out loud.
                    _events.tryEmit(ProviderEvent.Failed(s.error))
                }
                if (s.ended && endedFor != track.key) {
                    endedFor = track.key
                    _events.tryEmit(ProviderEvent.TrackEnded)
                }
                _snapshot.value = PlayerSnapshot(
                    track = track,
                    isPlaying = s.playing,
                    isBuffering = s.buffering,
                    positionMs = s.positionMs,
                    durationMs = if (s.durationMs > 0) s.durationMs else track.durationMs,
                    canSeek = player.canSeek,
                    canSkip = true,
                    volume = volume,
                    error = if (s.ended) null else (s.error ?: lastError),
                )
            }
        }
    }

    suspend fun play(track: Track, startMs: Long) {
        endedFor = null
        lastError = null
        current = track
        val at = startMs.coerceAtLeast(0L)
        _snapshot.value = PlayerSnapshot(
            track = track,
            isPlaying = false,
            isBuffering = true,
            positionMs = at,
            durationMs = track.durationMs,
            canSeek = player.canSeek,
            canSkip = true,
            volume = volume,
        )
        val id = track.uri?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: track.id
        val stream = try {
            resolve(id, quality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(track, at, e.message ?: "${track.provider.label} could not play this song")
            throw e
        }
        try {
            player.play(stream, at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(track, at, e.message ?: "${track.provider.label} could not play this song")
            throw e
        }
    }

    suspend fun pause() = player.pause()

    suspend fun resume() = player.resume()

    suspend fun seek(positionMs: Long) {
        if (player.canSeek) player.seek(positionMs.coerceAtLeast(0L))
    }

    suspend fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        player.setVolume(volume)
    }

    /** Applies to the next song: a stream is resolved at the quality the app asks for. */
    suspend fun setQuality(value: AudioQuality) {
        quality = value
    }

    suspend fun stop() {
        current = null
        lastError = null
        endedFor = null
        _snapshot.value = null
        player.stop()
    }

    suspend fun release() {
        current = null
        _snapshot.value = null
        player.release()
    }

    private fun failed(track: Track, at: Long, message: String) {
        lastError = message
        _snapshot.value = PlayerSnapshot(
            track = track,
            isPlaying = false,
            isBuffering = false,
            positionMs = at,
            durationMs = track.durationMs,
            canSeek = player.canSeek,
            canSkip = true,
            volume = volume,
            error = message,
        )
        _events.tryEmit(ProviderEvent.Failed(message))
    }
}
