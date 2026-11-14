/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.AudioQuality
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The shared half of a native provider: what the app sees when a sink plays, ends or fails. */
@OptIn(ExperimentalCoroutinesApi::class)
class NativePlaybackTest {

    private val track = Track(
        provider = ProviderId.SOUNDCLOUD,
        id = "293",
        title = "Flickermood",
        artist = "Forss",
        durationMs = 213_886,
        uri = "soundcloud:track:293",
    )

    /** A player with no process behind it: the tests drive its state directly. */
    private class FakePlayer : AudioPlayer {
        private val _state = MutableStateFlow(AudioState())
        override val state: StateFlow<AudioState> = _state.asStateFlow()
        override val canSeek: Boolean = true

        var played: ResolvedStream? = null
        var startedAt: Long = -1
        var volume: Float = 1f

        fun emit(state: AudioState) {
            _state.value = state
        }

        override suspend fun play(stream: ResolvedStream, startMs: Long) {
            played = stream
            startedAt = startMs
            _state.value = AudioState(hasStream = true, playing = true, durationMs = stream.durationMs)
        }

        override suspend fun pause() {
            _state.value = _state.value.copy(playing = false)
        }

        override suspend fun resume() {
            _state.value = _state.value.copy(playing = true)
        }

        override suspend fun seek(positionMs: Long) {
            _state.value = _state.value.copy(positionMs = positionMs)
        }

        override suspend fun setVolume(volume: Float) {
            this.volume = volume
            _state.value = _state.value.copy(volume = volume)
        }

        override suspend fun stop() {
            _state.value = AudioState()
        }

        override suspend fun release() = stop()
    }

    @Test
    fun `a stream is resolved from the track's own id and played at the asked position`() = runTest {
        val player = FakePlayer()
        var askedId: String? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playback = NativePlayback(player, scope) { id, _ ->
            askedId = id
            ResolvedStream("https://example.test/stream", durationMs = 213_886)
        }

        playback.play(track, startMs = 42_000)

        assertEquals("293", askedId)
        assertEquals("https://example.test/stream", player.played?.url)
        assertEquals(42_000, player.startedAt)
        val snapshot = playback.snapshot.value
        assertNotNull(snapshot)
        assertEquals(track, snapshot!!.track)
        scope.cancel()
    }

    @Test
    fun `the end of a stream is reported once, and a new song arms it again`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playback = NativePlayback(player, scope) { _, _ -> ResolvedStream("https://example.test/a") }
        val events = mutableListOf<ProviderEvent>()
        val listener = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        listener.launch { playback.events.collect { events += it } }

        playback.play(track, 0)
        player.emit(AudioState(hasStream = true, playing = true, durationMs = 213_886))
        player.emit(AudioState(hasStream = true, ended = true, positionMs = 213_886, durationMs = 213_886))
        assertEquals(1, events.count { it is ProviderEvent.TrackEnded })

        // A player that repeats its ended state must not advance the queue twice.
        player.emit(AudioState(hasStream = true, ended = true, positionMs = 213_886, durationMs = 213_886))
        assertEquals(1, events.count { it is ProviderEvent.TrackEnded })

        playback.play(track, 0)
        player.emit(AudioState(hasStream = true, playing = true, durationMs = 213_886))
        player.emit(AudioState(hasStream = true, ended = true, positionMs = 213_886, durationMs = 213_886))
        assertEquals(2, events.count { it is ProviderEvent.TrackEnded })
        scope.cancel()
        listener.cancel()
    }

    @Test
    fun `a stream that cannot be resolved leaves the song in place with the reason`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playback = NativePlayback(player, scope) { _, _ ->
            throw IllegalStateException("SoundCloud has no playable stream for this track")
        }
        val events = mutableListOf<ProviderEvent>()
        val listener = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        listener.launch { playback.events.collect { events += it } }

        val thrown = runCatching { playback.play(track, 5_000) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertNull(player.played)
        val snapshot: PlayerSnapshot = playback.snapshot.value!!
        assertEquals(track, snapshot.track)
        assertEquals("SoundCloud has no playable stream for this track", snapshot.error)
        assertTrue(events.any { it is ProviderEvent.Failed })
        scope.cancel()
        listener.cancel()
    }

    @Test
    fun `volume reaches the sink and the snapshot`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playback = NativePlayback(player, scope) { _, _ -> ResolvedStream("https://example.test/a") }

        playback.play(track, 0)
        playback.setVolume(0.35f)
        playback.setQuality(AudioQuality.HIGH)

        assertEquals(0.35f, player.volume)
        assertEquals(0.35f, playback.snapshot.value?.volume)
        scope.cancel()
    }
}
