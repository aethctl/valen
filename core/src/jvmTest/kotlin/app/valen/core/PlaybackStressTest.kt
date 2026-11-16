/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The coordinator under a crowd: hundreds of commands at once, from several threads (MPRIS calls
 * in on its own), against services that are slow to start a song, sometimes fail, and report a
 * song's end late. Whatever the order, when it settles there is one song sounding, and it is the
 * one the queue says is playing.
 */
class PlaybackStressTest {

    /** A service that takes its time to start a song, fails now and then, and says what it sounds. */
    private class Slow(override val id: ProviderId, private val random: Random) : MusicProvider {
        override val session = MutableStateFlow<SessionState>(SessionState.Connected("x"))
        override val player = MutableStateFlow<PlayerSnapshot?>(null)
        override val events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 64)

        /** The song coming out of this service's speakers now, if any. */
        @Volatile var sounding: Track? = null
        @Volatile var paused = false
        val plays = AtomicInteger()

        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override suspend fun search(query: String, limit: Int): List<Track> = emptyList()

        override suspend fun play(track: Track, startMs: Long) {
            plays.incrementAndGet()
            // Resolving a stream takes a while, longer some times than others.
            delay(random.nextLong(0, 30))
            // A stream that will not open: the old one is stopped first, as the real adapters do.
            if (random.nextInt(12) == 0) {
                sounding = null
                player.value = null
                error("the stream would not open")
            }
            sounding = track
            paused = false
            player.value = PlayerSnapshot(track = track, isPlaying = true, durationMs = track.durationMs)
        }

        override suspend fun pause() {
            paused = true
            player.value = player.value?.copy(isPlaying = false)
        }

        override suspend fun resume() {
            if (sounding != null) paused = false
            player.value = player.value?.copy(isPlaying = sounding != null)
        }

        override suspend fun stop() {
            sounding = null
            paused = false
            player.value = null
        }

        override suspend fun seek(positionMs: Long) {}
        override suspend fun setVolume(volume: Float) {}
        override suspend fun close() {}

        val audible: Boolean get() = sounding != null && !paused
    }

    private fun songs(provider: ProviderId, n: Int) = List(n) { i ->
        Track(provider = provider, id = "${provider.short}-$i", title = "Song $i", artist = "Artist ${i % 5}", durationMs = 180_000)
    }

    @Test
    @Timeout(120)
    fun `a crowd of commands leaves one song sounding, the one the queue points at`() = repeat(12) { round ->
        runBlocking {
            val random = Random(round * 7919 + 13)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val services = listOf(Slow(ProviderId.YOUTUBE_MUSIC, Random(round)), Slow(ProviderId.SOUNDCLOUD, Random(round + 99)), Slow(ProviderId.SPOTIFY, Random(round + 7)))
            val coordinator = PlaybackCoordinator(services, scope, SettingsStore(read = { null }, write = {}))
            val library = songs(ProviderId.YOUTUBE_MUSIC, 12) + songs(ProviderId.SOUNDCLOUD, 12) + songs(ProviderId.SPOTIFY, 6)
            try {
                coordinator.play(library.first(), library.shuffled(random))
                val jobs = List(300) { k ->
                    scope.launch {
                        delay(random.nextLong(0, 400))
                        when (random.nextInt(15)) {
                            0, 1, 2 -> coordinator.next()
                            3 -> coordinator.previous()
                            4 -> coordinator.toggle()
                            5 -> coordinator.play(library.random(random), library.shuffled(random).take(random.nextInt(1, 30)))
                            6 -> coordinator.enqueue(library.random(random))
                            7 -> coordinator.playNext(library.random(random))
                            8 -> coordinator.queue.value.indices.randomOrNull(random)?.let { coordinator.removeAt(it) }
                            9 -> {
                                val q = coordinator.queue.value
                                if (q.size > 1) coordinator.move(random.nextInt(q.size), random.nextInt(q.size))
                            }
                            10 -> coordinator.setShuffle(random.nextBoolean())
                            11 -> coordinator.seek(random.nextLong(0, 180_000))
                            12 -> coordinator.setEffects(SoundEffects(reverb = random.nextFloat()))
                            // A service saying its song ended, sometimes after the app moved on already.
                            13 -> coordinator.active.value?.let { id -> services.first { it.id == id }.events.emit(ProviderEvent.TrackEnded()) }
                            else -> coordinator.cycleRepeat()
                        }
                    }
                }
                jobs.joinAll()
                // Whatever was still starting finishes on its own; nothing is tidied up after the crowd.
                delay(400)

                val audible = services.filter { it.audible }
                assertTrue(audible.size <= 1, "round $round: ${audible.map { it.id }} all sounding at once")
                val index = coordinator.index.value
                val now = coordinator.queue.value
                assertTrue(index in -1..now.lastIndex, "round $round: index $index outside a queue of ${now.size}")
                val expected = now.getOrNull(index)
                val heard = audible.firstOrNull()?.sounding
                if (heard != null) {
                    assertEquals(expected?.key, heard.key, "round $round: the queue says ${expected?.title}, the speakers play ${heard.title}")
                    assertEquals(heard.provider, coordinator.active.value, "round $round: the active service is not the one sounding")
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    @Timeout(60)
    fun `a song's end reported late does not skip the song after it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sc = Slow(ProviderId.SOUNDCLOUD, Random(1))
        val coordinator = PlaybackCoordinator(listOf(sc), scope, SettingsStore(read = { null }, write = {}))
        val list = songs(ProviderId.SOUNDCLOUD, 5)
        try {
            coordinator.play(list[0], list)
            coordinator.next()
            // The first song's process says it ended only now, after the app already moved to the second.
            val playingSecond = sc.player.value?.track?.key
            sc.player.value = sc.player.value?.copy(track = list[0])
            sc.events.emit(ProviderEvent.TrackEnded())
            delay(200)
            assertEquals(1, coordinator.index.value)
            assertEquals(list[1].key, playingSecond)
        } finally {
            scope.cancel()
        }
    }
}
