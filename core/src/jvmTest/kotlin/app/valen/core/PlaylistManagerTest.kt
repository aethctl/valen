/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one path a playlist takes into Valen: a pasted address or an account list is read by the
 * service that owns it, matched onto what can be played here, and stored once per source list.
 */
class PlaylistManagerTest {

    private class MemoryStore {
        var text: String? = null
        val store = PlaylistStore(read = { text }, write = { text = it })
    }

    private class FakeCatalog(
        override val provider: ProviderId,
        var answer: ReadPlaylist? = null,
        var lists: List<PlaylistRef> = emptyList(),
        var readFailure: Exception? = null,
        var listFailure: Exception? = null,
    ) : PlaylistCatalog {
        val reads = mutableListOf<PlaylistLink>()

        override suspend fun playlists(): List<PlaylistRef> {
            listFailure?.let { throw it }
            return lists
        }

        override suspend fun read(link: PlaylistLink): ReadPlaylist {
            reads += link
            readFailure?.let { throw it }
            return answer ?: throw IllegalStateException("no answer")
        }
    }

    private fun track(provider: ProviderId, id: String, title: String = "Get Lucky") = Track(
        provider = provider,
        id = id,
        title = title,
        artist = "Daft Punk",
        album = "Random Access Memories",
        durationMs = 369_000,
    )

    private val spotifyTrack = track(ProviderId.SPOTIFY, "sp-1")
    private val youtubeTrack = track(ProviderId.YOUTUBE_MUSIC, "yt-1")

    private val spotifyLink = PlaylistLink(ProviderId.SPOTIFY, "PL1", "https://open.spotify.com/playlist/PL1")
    private val spotifyRef = PlaylistRef("Discovery", 2, link = spotifyLink)

    private fun manager(
        catalogs: List<PlaylistCatalog>,
        store: MemoryStore = MemoryStore(),
        search: suspend (ProviderId, String, Int) -> List<Track> = { _, _, _ -> listOf(youtubeTrack) },
        playable: (Track) -> Boolean = { it.provider != ProviderId.SPOTIFY },
        priority: List<ProviderId> = listOf(ProviderId.YOUTUBE_MUSIC, ProviderId.SOUNDCLOUD),
    ): Pair<PlaylistManager, MemoryStore> {
        var clock = 1_000L
        val library = PlaylistLibrary(store.store, clock = { clock++ })
        return PlaylistManager(
            library = library,
            catalogs = catalogs,
            search = search,
            playable = playable,
            priority = { priority },
        ) to store
    }

    @Test
    fun `a pasted Spotify link is stored with the songs Valen can play`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("Discovery", listOf(spotifyTrack)))
        val (manager, store) = manager(listOf(catalog))

        val result = manager.importLink("https://open.spotify.com/playlist/PL1?si=share")

        val playlist = (result as ImportResult.Imported).playlist
        assertEquals(listOf("https://open.spotify.com/playlist/PL1?si=share"), catalog.reads.map { it.url })
        assertEquals("Discovery", playlist.name)
        assertEquals(listOf(youtubeTrack), playlist.tracks)
        assertEquals("SPOTIFY:PL1", playlist.source?.key)
        assertTrue(playlist.unmatched.isEmpty())
        assertEquals(listOf(playlist), PlaylistLibrary(store.store).playlists.value, "the import is persisted")
    }

    @Test
    fun `importing the same list again refreshes one entry instead of adding a second`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("Discovery", listOf(spotifyTrack)))
        val (manager, _) = manager(listOf(catalog))

        val first = (manager.importLink(spotifyLink.url) as ImportResult.Imported).playlist
        catalog.answer = ReadPlaylist("Discovery (2026)", listOf(spotifyTrack, spotifyTrack))
        val second = (manager.importLink("spotify:playlist:PL1") as ImportResult.Imported).playlist

        assertEquals(first.id, second.id)
        assertEquals(1, manager.library.playlists.value.size)
        assertEquals("Discovery (2026)", second.name)
        assertEquals(2, second.tracks.size)
    }

    @Test
    fun `a short link is stored under the id the reader resolved`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("Discovery", listOf(spotifyTrack), link = spotifyLink))
        val (manager, _) = manager(listOf(catalog))

        val short = (manager.importLink("https://spotify.link/abcDEF") as ImportResult.Imported).playlist
        assertEquals("SPOTIFY:PL1", short.source?.key)

        val again = (manager.importLink("https://open.spotify.com/playlist/PL1") as ImportResult.Imported).playlist
        assertEquals(short.id, again.id, "the resolved id is what makes the two addresses one list")
    }

    @Test
    fun `a pasted link that is not a playlist is refused without storing anything`() = runBlocking {
        val (manager, _) = manager(listOf(FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("X", emptyList()))))

        val result = manager.importLink("https://music.apple.com/fr/playlist/chill/pl.u-abc")

        assertTrue(result is ImportResult.Failed)
        assertTrue(manager.library.playlists.value.isEmpty())
    }

    @Test
    fun `a service with no catalog says so instead of doing nothing`() = runBlocking {
        val (manager, _) = manager(listOf(FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("X", emptyList()))))

        val result = manager.importLink("https://soundcloud.com/daft-punk/sets/discovery")

        assertTrue(result is ImportResult.Failed)
        assertTrue((result as ImportResult.Failed).reason.contains("SoundCloud"))
        assertTrue(manager.library.playlists.value.isEmpty())
    }

    @Test
    fun `a reader that fails is reported and leaves the library alone`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SOUNDCLOUD, readFailure = IllegalStateException("SoundCloud refused the set"))
        val (manager, _) = manager(listOf(catalog))

        val result = manager.importLink("https://soundcloud.com/daft-punk/sets/discovery")

        assertEquals("SoundCloud refused the set", (result as ImportResult.Failed).reason)
        assertTrue(manager.library.playlists.value.isEmpty())
    }

    @Test
    fun `an account playlist imports under its own identity`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("Discovery", listOf(spotifyTrack)))
        catalog.lists = listOf(spotifyRef)
        val (manager, _) = manager(listOf(catalog))

        val choices = manager.accountPlaylists(ProviderId.SPOTIFY)
        assertEquals(listOf(spotifyRef), choices.playlists)
        assertNull(choices.error)

        val playlist = (manager.importPlaylist(choices.playlists.single()) as ImportResult.Imported).playlist
        assertEquals("SPOTIFY:PL1", playlist.source?.key)
        assertEquals(spotifyLink.url, playlist.source?.url)
    }

    @Test
    fun `a service that cannot list playlists reports why`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SOUNDCLOUD, listFailure = IllegalStateException("SoundCloud is signed out"))
        val (manager, _) = manager(listOf(catalog))

        val choices = manager.accountPlaylists(ProviderId.SOUNDCLOUD)

        assertEquals("SoundCloud is signed out", choices.error)
        assertTrue(choices.playlists.isEmpty())
    }

    @Test
    fun `refresh re-reads the stored source and keeps the entry`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SOUNDCLOUD, answer = ReadPlaylist("Discovery", listOf(spotifyTrack), link = null))
        val (manager, _) = manager(
            listOf(catalog),
            playable = { false },
            search = { _, _, _ -> listOf(youtubeTrack) },
        )
        val added = (manager.importLink("https://soundcloud.com/daft-punk/sets/discovery") as ImportResult.Imported).playlist
        catalog.answer = ReadPlaylist("Discovery", listOf(spotifyTrack, spotifyTrack))

        val refreshed = (manager.refresh(added.id) as ImportResult.Imported).playlist

        assertEquals(added.id, refreshed.id)
        assertEquals(2, refreshed.tracks.size)
        assertEquals("https://soundcloud.com/daft-punk/sets/discovery", catalog.reads.last().url)
    }

    @Test
    fun `a playlist with no source cannot be refreshed`() = runBlocking {
        val (manager, _) = manager(listOf(FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("X", emptyList()))))
        val local = manager.library.add("Made here", listOf(youtubeTrack))

        val result = manager.refresh(local.id)

        assertTrue((result as ImportResult.Failed).reason.contains("made in Valen"))
        assertTrue(manager.refresh("pl-does-not-exist") is ImportResult.Failed)
    }

    @Test
    fun `an empty source is not stored as an empty playlist`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("Nothing", emptyList()))
        val (manager, _) = manager(listOf(catalog))

        val result = manager.importLink(spotifyLink.url)

        assertTrue(result is ImportResult.Failed)
        assertTrue(manager.library.playlists.value.isEmpty())
    }

    @Test
    fun `progress reaches the screen as the import runs`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("Discovery", listOf(spotifyTrack, spotifyTrack)))
        val (manager, _) = manager(listOf(catalog))
        val seen = mutableListOf<ImportProgress>()

        manager.importLink(spotifyLink.url) { seen += it }

        assertEquals(listOf(1, 2), seen.map { it.processed })
        assertEquals(2, seen.last().total)
        assertEquals(2, seen.last().matched)
    }

    @Test
    fun `a song its own service can play is kept exactly`() = runBlocking {
        val catalog = FakeCatalog(ProviderId.SPOTIFY, answer = ReadPlaylist("Discovery", listOf(spotifyTrack)))
        var searches = 0
        val (manager, _) = manager(
            listOf(catalog),
            search = { _, _, _ -> searches++; emptyList() },
            playable = { true },
        )

        val playlist = (manager.importLink(spotifyLink.url) as ImportResult.Imported).playlist

        assertEquals(listOf(spotifyTrack), playlist.tracks)
        assertEquals(0, searches)
        assertTrue(manager.canPlay(spotifyTrack))
    }

    @Test
    fun `a playlist the catalogue lists but the catalogue cannot read fails cleanly`() = runBlocking {
        val (manager, _) = manager(listOf(FakeCatalog(ProviderId.SPOTIFY)))

        val result = manager.importLink(spotifyLink.url)

        assertTrue((result as ImportResult.Failed).reason.isNotBlank())
        assertTrue(manager.library.playlists.value.isEmpty())
    }
}
