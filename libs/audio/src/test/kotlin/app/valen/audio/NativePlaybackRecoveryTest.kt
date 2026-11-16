/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shared half of a native provider when streams misbehave: overtaken plays, dropped
 * connections, stalls, expired addresses, long pauses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativePlaybackRecoveryTest {

    private fun song(id: String) = Track(ProviderId.SOUNDCLOUD, id, "Song $id", "Artist", durationMs = 200_000, uri = "soundcloud:track:$id")

    /** A sink whose every call is recorded; [refuse] makes the next plays throw. */
    private class Sink : AudioPlayer {
        private val _state = MutableStateFlow(AudioState())
        override val state: StateFlow<AudioState> = _state.asStateFlow()
        override val canSeek = true
        val plays = mutableListOf<Pair<String, Long>>()
        var refuse = 0
        var resumed = 0

        fun emit(s: AudioState) { _state.value = s }

        override suspend fun play(stream: ResolvedStream, startMs: Long) {
            if (refuse > 0) {
                refuse--
                error("403 Forbidden")
            }
            plays += stream.url to startMs
            _state.value = AudioState(hasStream = true, playing = true, buffering = true, positionMs = startMs, durationMs = stream.durationMs)
        }
        override suspend fun pause() { _state.value = _state.value.copy(playing = false) }
        override suspend fun resume() { resumed++; _state.value = _state.value.copy(playing = true) }
        override suspend fun seek(positionMs: Long) {}
        override suspend fun setVolume(volume: Float) {}
        override suspend fun stop() { _state.value = AudioState() }
        override suspend fun release() = stop()
    }

    private class World(val test: TestScope) {
        val sink = Sink()
        val scope = CoroutineScope(UnconfinedTestDispatcher(test.testScheduler))
        var now = 1_000_000L
        val resolved = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val events = mutableListOf<ProviderEvent>()
        var failLookups = 0
        val playback = NativePlayback(sink, scope, clock = { now }) { id, _ ->
            gates[id]?.await()
            if (failLookups > 0) {
                failLookups--
                error("Unable to resolve host")
            }
            resolved += id
            ResolvedStream("https://cdn.test/$id/${resolved.count { it == id }}", durationMs = 200_000)
        }

        init {
            scope.launch { playback.events.collect { events += it } }
        }

        /** The sink says the stream is sounding at [at]. */
        fun sounding(at: Long) = sink.emit(AudioState(hasStream = true, playing = true, started = true, positionMs = at, durationMs = 200_000))
    }

    @Test
    fun `a play overtaken while its address is found never reaches the player`() = runTest {
        val w = World(this)
        w.gates["1"] = CompletableDeferred()
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { w.playback.play(song("1"), 0) }
        w.playback.play(song("2"), 0)
        w.gates["1"]!!.complete(Unit)
        first.join()
        assertEquals(listOf("https://cdn.test/2/1"), w.sink.plays.map { it.first })
        assertEquals("2", w.playback.snapshot.value?.track?.id)
        w.scope.cancel()
    }

    @Test
    fun `an early end, a failure after the sound began and a stall each open the song again where it was`() = runTest {
        val w = World(this)
        w.playback.play(song("1"), 0)

        w.sink.emit(AudioState(hasStream = true, ended = true, started = true, positionMs = 61_000, durationMs = 200_000))
        advanceTimeBy(2_000)
        assertEquals("https://cdn.test/1/2" to 61_000L, w.sink.plays.last(), "an early end is a drop")

        w.sink.emit(AudioState(hasStream = true, error = "Connection reset by peer", started = true, positionMs = 90_000, durationMs = 200_000))
        advanceTimeBy(3_000)
        assertEquals("https://cdn.test/1/3" to 90_000L, w.sink.plays.last(), "a failure mid-song is a drop")

        w.sink.emit(AudioState(hasStream = true, playing = true, stalled = true, started = true, positionMs = 120_000, durationMs = 200_000))
        advanceTimeBy(3_000)
        assertEquals("https://cdn.test/1/4" to 120_000L, w.sink.plays.last(), "a stall is a drop")
        assertTrue(w.events.none { it is ProviderEvent.TrackEnded || it is ProviderEvent.Failed }, "nothing was reported meanwhile: ${w.events}")

        // A fourth drop is one too many: the failure is said.
        w.sink.emit(AudioState(hasStream = true, error = "Connection reset by peer", started = true, positionMs = 130_000, durationMs = 200_000))
        advanceTimeBy(3_000)
        assertTrue(w.events.any { it is ProviderEvent.Failed }, "gives up after a few tries")
        w.scope.cancel()
    }

    @Test
    fun `a real end is an end, and a stream that never sounded is a failure at once`() = runTest {
        val w = World(this)
        w.playback.play(song("1"), 0)
        w.sink.emit(AudioState(hasStream = true, ended = true, started = true, positionMs = 199_000, durationMs = 200_000))
        assertEquals(listOf(ProviderEvent.TrackEnded(song("1").key)), w.events)

        w.playback.play(song("2"), 0)
        w.sink.emit(AudioState(hasStream = true, error = "No such file or directory", positionMs = 0, durationMs = 200_000))
        assertTrue(w.events.last() is ProviderEvent.Failed)
        assertEquals(2, w.sink.plays.size, "no second try for a stream that never opened")
        w.scope.cancel()
    }

    @Test
    fun `a prepared song is not resolved again, and a kept address that will not open is resolved fresh`() = runTest {
        val w = World(this)
        w.playback.prepare(song("7"))
        w.playback.play(song("7"), 0)
        assertEquals(listOf("7"), w.resolved, "the prepared address was used")

        // Later the kept address has expired at the server: one fresh try, and it plays.
        w.sink.refuse = 1
        w.playback.play(song("7"), 0)
        assertEquals(listOf("7", "7"), w.resolved)
        assertEquals("https://cdn.test/7/2", w.sink.plays.last().first)
        w.scope.cancel()
    }

    @Test
    fun `a long pause resumes by opening the song again where it was`() = runTest {
        val w = World(this)
        w.playback.play(song("1"), 0)
        w.sounding(42_000)
        w.playback.pause()
        w.sink.emit(AudioState(hasStream = true, playing = false, started = true, positionMs = 42_000, durationMs = 200_000))

        w.now += 30_000
        w.playback.resume()
        assertEquals(1, w.sink.resumed, "a short pause just goes on")

        w.playback.pause()
        w.now += NativePlayback.LONG_PAUSE_MS + 1
        w.playback.resume()
        assertEquals(1, w.sink.resumed)
        assertEquals(42_000L, w.sink.plays.last().second, "opened again at the same place")
        w.scope.cancel()
    }

    @Test
    fun `Play after a song failed opens it again instead of doing nothing`() = runTest {
        val w = World(this)
        w.sink.refuse = 1
        runCatching { w.playback.play(song("1"), 30_000) }
        assertTrue(w.events.last() is ProviderEvent.Failed)
        assertTrue(w.sink.plays.isEmpty())

        w.playback.resume()
        assertEquals(1, w.sink.plays.size, "opened again")
        assertEquals(30_000L, w.sink.plays.last().second, "at the same place")
        w.scope.cancel()
    }

    @Test
    fun `a reopen that breaks before any sound is tried again, as the drop it is`() = runTest {
        val w = World(this)
        w.playback.play(song("1"), 0)
        w.sounding(10_000)
        w.sink.emit(AudioState(hasStream = true, error = "Connection reset by peer", started = true, positionMs = 20_000, durationMs = 200_000))
        advanceTimeBy(1_000)
        assertEquals(2, w.sink.plays.size)
        // The reopened stream fails before its own first sound: still the same song's drop.
        w.sink.emit(AudioState(hasStream = true, error = "Connection reset by peer", started = false, positionMs = 20_000, durationMs = 200_000))
        advanceTimeBy(2_000)
        assertEquals(3, w.sink.plays.size, "tried again")
        assertTrue(w.events.none { it is ProviderEvent.Failed }, "${w.events}")
        w.scope.cancel()
    }

    @Test
    fun `a lookup that fails during a recovery is tried again, and the last failure moves the app on`() = runTest {
        val w = World(this)
        w.playback.play(song("1"), 0)
        w.sounding(30_000)
        w.failLookups = 1
        w.sink.emit(AudioState(hasStream = true, error = "Connection reset by peer", started = true, positionMs = 30_000, durationMs = 200_000))
        advanceTimeBy(5_000)
        assertEquals(30_000L, w.sink.plays.last().second, "reopened once the network answered")
        assertTrue(w.events.none { it is ProviderEvent.Failed })

        // Out of tries: one failure, named for its song, so the coordinator plays the next one.
        w.failLookups = 10
        repeat(4) {
            w.sink.emit(AudioState(hasStream = true, error = "Connection reset by peer", started = true, positionMs = 40_000, durationMs = 200_000))
            advanceTimeBy(5_000)
        }
        val failures = w.events.filterIsInstance<ProviderEvent.Failed>()
        assertEquals(1, failures.size, "${w.events}")
        assertEquals(song("1").key, failures.single().key)
        w.scope.cancel()
    }

    @Test
    fun `a stall after the last try is a failure, not buffering forever`() = runTest {
        val w = World(this)
        w.playback.play(song("1"), 0)
        w.sounding(5_000)
        repeat(NativePlayback.MAX_RECOVERIES + 1) {
            w.sink.emit(AudioState(hasStream = true, playing = true, stalled = true, started = true, positionMs = 50_000L + it, durationMs = 200_000))
            advanceTimeBy(5_000)
        }
        assertTrue(w.events.any { it is ProviderEvent.Failed }, "${w.events}")
        w.scope.cancel()
    }

    @Test
    fun `steady sound forgets earlier drops, so a long song can drop again later`() = runTest {
        val w = World(this)
        w.playback.play(song("1"), 0)
        repeat(NativePlayback.MAX_RECOVERIES + 2) { i ->
            val at = 10_000L + i * 20_000L
            w.sounding(at)
            w.now += NativePlayback.STEADY_MS + 1
            w.sounding(at + 1_000)
            w.sink.emit(AudioState(hasStream = true, error = "Connection reset by peer", started = true, positionMs = at + 2_000, durationMs = 200_000))
            advanceTimeBy(5_000)
        }
        assertTrue(w.events.none { it is ProviderEvent.Failed }, "${w.events}")
        assertEquals(NativePlayback.MAX_RECOVERIES + 3, w.sink.plays.size)
        w.scope.cancel()
    }

    @Test
    fun `a failure or a silent reopen at the very end is the end of the song`() = runTest {
        val w = World(this)
        w.playback.play(song("1"), 0)
        w.sounding(190_000)
        w.sink.emit(AudioState(hasStream = true, error = "End of file", started = true, positionMs = 197_500, durationMs = 200_000))
        assertEquals(listOf(ProviderEvent.TrackEnded(song("1").key)), w.events)

        // A song that runs out a few seconds before its stated length: the reopen near the end
        // makes no sound, and that is the end rather than a broken stream.
        w.playback.play(song("2"), 0)
        w.sink.emit(AudioState(hasStream = true, playing = true, started = true, positionMs = 185_000, durationMs = 200_000))
        w.sink.emit(AudioState(hasStream = true, ended = true, started = true, positionMs = 190_000, durationMs = 200_000))
        advanceTimeBy(2_000)
        w.sink.emit(AudioState(hasStream = true, error = "the stream would not open", started = false, positionMs = 190_000, durationMs = 200_000))
        assertEquals(ProviderEvent.TrackEnded(song("2").key), w.events.last())
        assertTrue(w.events.none { it is ProviderEvent.Failed })
        w.scope.cancel()
    }

    @Test
    fun `Play after the song ended starts it again from the top`() = runTest {
        val w = World(this)
        w.playback.play(song("1"), 0)
        w.sink.emit(AudioState(hasStream = true, ended = true, started = true, positionMs = 199_500, durationMs = 200_000))
        w.playback.resume()
        assertEquals(0L, w.sink.plays.last().second)
        assertEquals(2, w.sink.plays.size)
        w.scope.cancel()
    }
}
