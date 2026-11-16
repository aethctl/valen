/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A service whose cache holds some songs whole, and which counts its look-ups. */
private class CachingAdapter(override val id: ProviderId, var cached: Set<String> = emptySet()) : MusicProvider {
    override val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val player = MutableStateFlow<PlayerSnapshot?>(null)
    override val events = MutableSharedFlow<ProviderEvent>()
    val warmed = mutableListOf<String>()
    val prepared = mutableListOf<String>()
    override suspend fun connect() {}
    override suspend fun disconnect() {}
    override suspend fun search(query: String, limit: Int): List<Track> = emptyList()
    override suspend fun play(track: Track, startMs: Long) {
        player.value = PlayerSnapshot(track = track, isPlaying = true, durationMs = track.durationMs)
    }
    override suspend fun prepare(track: Track) { prepared += track.key }
    override suspend fun warm(track: Track) { warmed += track.key }
    override fun isCached(track: Track): Boolean = track.key in cached
    override suspend fun pause() {}
    override suspend fun resume() {}
    override suspend fun seek(positionMs: Long) {}
    override suspend fun setVolume(volume: Float) {}
    override suspend fun close() {}
}

private class KeptShelf(private val files: Map<String, String>) : OfflineShelf {
    override val state: StateFlow<OfflineState> = MutableStateFlow(OfflineState(kept = files.keys, files = files.size))
    override fun fileFor(track: Track): String? = files[track.key]
    override fun keep(playlist: Playlist) {}
    override suspend fun forget(playlistId: String) {}
    override val folder: String = "/music"
}

/** "Offline" follows what plays with no network; warm-ups and the two-ahead window stay in budget. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineListTest {

    private val songs = (1..5).map { Track(ProviderId.YOUTUBE_MUSIC, "y$it", "Song $it", "Band", durationMs = 200_000) }
    private val spotify = Track(ProviderId.SPOTIFY, "s1", "Kept", "Band")

    private fun library() = PlaylistLibrary(PlaylistStore(read = { null }, write = {}), clock = { 1L })

    @Test
    fun `offline holds downloaded and cached songs, appears and leaves with them, and obeys the setting`() = runTest {
        val ytm = CachingAdapter(ProviderId.YOUTUBE_MUSIC, cached = setOf(songs[1].key))
        val coordinator = PlaybackCoordinator(
            listOf(ytm, CachingAdapter(ProviderId.SPOTIFY), CachingAdapter(ProviderId.LOCAL)),
            backgroundScope,
            SettingsStore(read = { null }, write = {}),
            offline = KeptShelf(mapOf(spotify.key to "/music/Kept.opus")),
        )
        val library = library()
        library.add("Mine", songs + spotify)
        OfflineList(coordinator, library, backgroundScope, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)).start()
        advanceTimeBy(OfflineList.SETTLE_MS + 100)
        advanceUntilIdle()

        val offline = library.playlists.value.first { it.isOffline }
        assertEquals(listOf(songs[1].key, spotify.key), offline.tracks.map { it.key })
        assertTrue(!offline.takesSongs && !offline.isOwn, "Valen fills it, nobody edits it")

        coordinator.setOfflineList(false)
        advanceTimeBy(OfflineList.SETTLE_MS + 100)
        advanceUntilIdle()
        assertNull(library.playlists.value.firstOrNull { it.isOffline }, "turned off, it leaves the library")

        coordinator.setOfflineList(true)
        ytm.cached = emptySet()
        library.setOffline(emptyList())
        advanceTimeBy(OfflineList.SETTLE_MS + 100)
        advanceUntilIdle()
        assertEquals(listOf(spotify.key), library.playlists.value.first { it.isOffline }.tracks.map { it.key })
    }

    @Test
    fun `nothing offline means no list at all`() = runTest {
        val coordinator = PlaybackCoordinator(listOf(CachingAdapter(ProviderId.YOUTUBE_MUSIC)), backgroundScope, SettingsStore(read = { null }, write = {}))
        val library = library()
        library.add("Mine", songs)
        OfflineList(coordinator, library, backgroundScope, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)).start()
        advanceTimeBy(OfflineList.SETTLE_MS + 100)
        advanceUntilIdle()
        assertNull(library.playlists.value.firstOrNull { it.isOffline })
    }

    @Test
    fun `the next song is prepared and the one after only warmed`() = runTest {
        val ytm = CachingAdapter(ProviderId.YOUTUBE_MUSIC)
        val coordinator = PlaybackCoordinator(listOf(ytm), backgroundScope, SettingsStore(read = { null }, write = {}))
        coordinator.play(songs[0], songs)
        advanceUntilIdle()
        assertEquals(listOf(songs[1].key), ytm.prepared)
        assertEquals(listOf(songs[2].key), ytm.warmed)
    }

    @Test
    fun `warm-ups are skipped for what needs none and kept to a budget`() = runTest {
        val ytm = CachingAdapter(ProviderId.YOUTUBE_MUSIC, cached = setOf(songs[0].key))
        val coordinator = PlaybackCoordinator(listOf(ytm), backgroundScope, SettingsStore(read = { null }, write = {}))
        coordinator.warm(songs[0])
        runCurrent()
        assertTrue(ytm.warmed.isEmpty(), "a cached song needs no look-up")
        coordinator.warm(songs[1])
        runCurrent()
        coordinator.warm(songs[1])
        runCurrent()
        assertEquals(listOf(songs[1].key), ytm.warmed, "the same song once in a while")
        val many = (1..40).map { Track(ProviderId.YOUTUBE_MUSIC, "m$it", "M $it", "Band") }
        many.forEach {
            coordinator.warm(it)
            runCurrent()
        }
        assertEquals(12, ytm.warmed.size, "at most a dozen a minute")
    }
}
