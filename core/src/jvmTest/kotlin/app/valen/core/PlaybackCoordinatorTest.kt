/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun track(provider: ProviderId, id: String, title: String = id) = Track(
    provider = provider,
    id = id,
    title = title,
    artist = "Artist",
    durationMs = 180_000,
    uri = "uri:$id",
)

private class FakeProvider(override val id: ProviderId) : MusicProvider {
    override val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val player = MutableStateFlow<PlayerSnapshot?>(null)
    override val events = MutableSharedFlow<ProviderEvent>()

    var played: Track? = null
    var volume: Float = 1f
    var playing = false

    override suspend fun connect() { session.value = SessionState.Connected("test") }
    override suspend fun disconnect() { session.value = SessionState.SignedOut }
    override suspend fun search(query: String, limit: Int): List<Track> =
        listOf(track(id, "$id-$query"))

    override suspend fun play(track: Track, startMs: Long) {
        played = track
        playing = true
        player.value = PlayerSnapshot(track = track, isPlaying = true, durationMs = track.durationMs)
    }

    override suspend fun pause() { playing = false; player.value = player.value?.copy(isPlaying = false) }
    override suspend fun resume() { playing = true; player.value = player.value?.copy(isPlaying = true) }
    override suspend fun seek(positionMs: Long) { player.value = player.value?.copy(positionMs = positionMs) }
    override suspend fun setVolume(volume: Float) { this.volume = volume }
    override suspend fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {

    private fun settings(): SettingsStore = SettingsStore(read = { null }, write = {})

    @Test
    fun `play queues the whole context and starts the chosen track`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val ytm = FakeProvider(ProviderId.YOUTUBE_MUSIC)
        val coordinator = PlaybackCoordinator(listOf(spotify, ytm), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        val c = track(ProviderId.SPOTIFY, "c")
        coordinator.play(b, listOf(a, b, c))

        assertEquals(listOf(a, b, c), coordinator.queue.value)
        assertEquals(1, coordinator.index.value)
        assertEquals(ProviderId.SPOTIFY, coordinator.active.value)
        assertEquals(b, spotify.played)
        assertTrue(spotify.playing)
    }

    @Test
    fun `next crosses providers and pauses the previous adapter`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val ytm = FakeProvider(ProviderId.YOUTUBE_MUSIC)
        val coordinator = PlaybackCoordinator(listOf(spotify, ytm), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.YOUTUBE_MUSIC, "b")
        coordinator.play(a, listOf(a, b))
        assertEquals(ProviderId.SPOTIFY, coordinator.active.value)

        coordinator.next()
        assertEquals(ProviderId.YOUTUBE_MUSIC, coordinator.active.value)
        assertEquals(1, coordinator.index.value)
        assertEquals(b, ytm.played)
        assertTrue(!spotify.playing)

        coordinator.previous()
        assertEquals(ProviderId.SPOTIFY, coordinator.active.value)
        assertEquals(a, spotify.played)
    }

    @Test
    fun `the last track pauses instead of wrapping when it ends`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        coordinator.play(a, listOf(a, b))
        coordinator.next()
        coordinator.next(auto = true)

        assertEquals(1, coordinator.index.value)
        assertEquals(false, spotify.playing)
    }

    @Test
    fun `removing the current track keeps a sensible index`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        val c = track(ProviderId.SPOTIFY, "c")
        coordinator.play(b, listOf(a, b, c))

        coordinator.removeAt(1)
        assertEquals(listOf(a, c), coordinator.queue.value)
        assertEquals(1, coordinator.index.value)

        coordinator.removeAt(1)
        assertEquals(listOf(a), coordinator.queue.value)

        coordinator.removeAt(0)
        assertEquals(emptyList<Track>(), coordinator.queue.value)
        assertEquals(-1, coordinator.index.value)
        assertNull(coordinator.active.value)
    }

    @Test
    fun `moving rows keeps the playing track selected`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        val c = track(ProviderId.SPOTIFY, "c")
        coordinator.play(b, listOf(a, b, c))

        coordinator.move(1, 0)
        assertEquals(listOf(b, a, c), coordinator.queue.value)
        assertEquals(0, coordinator.index.value)

        coordinator.move(2, 0)
        assertEquals(listOf(c, b, a), coordinator.queue.value)
        assertEquals(1, coordinator.index.value)
    }

    @Test
    fun `enqueue does not duplicate a song already queued`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        coordinator.play(a, listOf(a))
        coordinator.enqueue(b)
        coordinator.enqueue(b)

        assertEquals(listOf(a, b), coordinator.queue.value)
    }

    @Test
    fun `volume is persisted and applied to the active adapter`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        coordinator.play(track(ProviderId.SPOTIFY, "a"))

        coordinator.setVolume(0.4f)
        assertEquals(0.4f, spotify.volume)
        assertEquals(0.4f, coordinator.settings.value.volume)

        coordinator.setVolume(2f)
        assertEquals(1f, spotify.volume)
    }

    @Test
    fun `search fans out and keeps a failure beside the results`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val broken = object : MusicProvider by FakeProvider(ProviderId.SOUNDCLOUD) {
            override suspend fun search(query: String, limit: Int): List<Track> = error("no client id")
        }
        val coordinator = PlaybackCoordinator(listOf(spotify, broken), backgroundScope, settings())

        val results = coordinator.search("daft punk")
        assertEquals(2, results.size)
        assertEquals(1, results.single { it.provider == ProviderId.SPOTIFY }.tracks.size)
        assertEquals("no client id", results.single { it.provider == ProviderId.SOUNDCLOUD }.error)
    }

    @Test
    fun `the same recording listed twice becomes one result`() = runTest {
        val twice = object : MusicProvider by FakeProvider(ProviderId.YOUTUBE_MUSIC) {
            override suspend fun search(query: String, limit: Int): List<Track> = listOf(
                track(ProviderId.YOUTUBE_MUSIC, "a", "One More Time"),
                track(ProviderId.YOUTUBE_MUSIC, "b", "One More Time"),
                track(ProviderId.YOUTUBE_MUSIC, "c", "Aerodynamic"),
            )
        }
        val coordinator = PlaybackCoordinator(listOf(twice), backgroundScope, settings())

        val results = coordinator.search("daft punk").single()
        assertEquals(listOf("a", "c"), results.tracks.map { it.id })
    }

    @Test
    fun `unified search lists a recording once and plays the preferred service's copy`() = runTest {
        val spotify = object : MusicProvider by FakeProvider(ProviderId.SPOTIFY) {
            override suspend fun search(query: String, limit: Int): List<Track> = listOf(
                track(ProviderId.SPOTIFY, "sp-other", "Aerodynamic"),
                track(ProviderId.SPOTIFY, "sp-one", "One More Time"),
            )
        }
        val ytm = object : MusicProvider by FakeProvider(ProviderId.YOUTUBE_MUSIC) {
            override suspend fun search(query: String, limit: Int): List<Track> = listOf(
                track(ProviderId.YOUTUBE_MUSIC, "yt-one", "One More Time (Official Video)"),
            )
        }
        val coordinator = PlaybackCoordinator(
            listOf(spotify, ytm),
            backgroundScope,
            settings(),
            AppSettings(priority = listOf(ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC)),
        )

        val result = coordinator.unifiedSearch("one more time")

        assertEquals(2, result.hits.size)
        val one = result.hits.single { it.track.title.startsWith("One More Time") }
        assertEquals("sp-one", one.track.id)
        assertEquals(listOf(ProviderId.YOUTUBE_MUSIC), one.alsoOn)
        assertEquals(2, result.tracks.distinctBy { it.id }.size)
    }

    @Test
    fun `the source order decides which copy of a shared song is kept`() = runTest {
        val spotify = object : MusicProvider by FakeProvider(ProviderId.SPOTIFY) {
            override suspend fun search(query: String, limit: Int): List<Track> =
                listOf(track(ProviderId.SPOTIFY, "sp", "One More Time"))
        }
        val ytm = object : MusicProvider by FakeProvider(ProviderId.YOUTUBE_MUSIC) {
            override suspend fun search(query: String, limit: Int): List<Track> =
                listOf(track(ProviderId.YOUTUBE_MUSIC, "yt", "One More Time"))
        }
        val coordinator = PlaybackCoordinator(
            listOf(spotify, ytm),
            backgroundScope,
            settings(),
            AppSettings(priority = listOf(ProviderId.YOUTUBE_MUSIC, ProviderId.SPOTIFY)),
        )

        val hit = coordinator.unifiedSearch("one more time").hits.single()

        assertEquals(ProviderId.YOUTUBE_MUSIC, hit.track.provider)
        assertEquals(listOf(ProviderId.SPOTIFY), hit.alsoOn)
    }

    @Test
    fun `a failed service is reported beside the merged results`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val broken = object : MusicProvider by FakeProvider(ProviderId.SOUNDCLOUD) {
            override suspend fun search(query: String, limit: Int): List<Track> = error("no client id")
        }
        val coordinator = PlaybackCoordinator(listOf(spotify, broken), backgroundScope, settings())

        val result = coordinator.unifiedSearch("daft punk")

        assertEquals(1, result.hits.size)
        assertEquals("no client id", result.errors[ProviderId.SOUNDCLOUD])
    }

    @Test
    fun `the source order is cleaned, completed and persisted`() = runTest {
        var stored: String? = null
        val store = SettingsStore(read = { stored }, write = { stored = it })
        val coordinator = PlaybackCoordinator(
            listOf(FakeProvider(ProviderId.SPOTIFY), FakeProvider(ProviderId.YOUTUBE_MUSIC), FakeProvider(ProviderId.SOUNDCLOUD)),
            backgroundScope,
            store,
        )

        coordinator.setPriority(listOf(ProviderId.SOUNDCLOUD, ProviderId.SOUNDCLOUD, ProviderId.SPOTIFY))

        assertEquals(
            listOf(ProviderId.SOUNDCLOUD, ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC),
            coordinator.settings.value.priority,
        )
        assertEquals(
            coordinator.settings.value.priority,
            SettingsStore(read = { stored }, write = {}).load().priority,
        )
    }

    @Test
    fun `settings survive a JSON round trip`() {
        var stored: String? = null
        val store = SettingsStore(read = { stored }, write = { stored = it })
        store.save(AppSettings(quality = AudioQuality.HIGH, volume = 0.3f, lastProvider = ProviderId.YOUTUBE_MUSIC))

        val loaded = SettingsStore(read = { stored }, write = {}).load()
        assertEquals(AudioQuality.HIGH, loaded.quality)
        assertEquals(0.3f, loaded.volume)
        assertEquals(ProviderId.YOUTUBE_MUSIC, loaded.lastProvider)
    }

    @Test
    fun `a broken settings file falls back to defaults`() {
        val loaded = SettingsStore(read = { "{not json" }, write = {}).load()
        assertEquals(AppSettings(), loaded)
    }
}
