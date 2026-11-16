/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The library Valen keeps: the same list imported twice is one entry, and nothing is lost at start. */
class PlaylistLibraryTest {

    private class MemoryStore(initial: String? = null) {
        var text: String? = initial
        val store = PlaylistStore(read = { text }, write = { text = it })
    }

    private fun track(id: String, title: String = "Song $id") = Track(
        provider = ProviderId.SOUNDCLOUD,
        id = id,
        title = title,
        artist = "Artist",
        durationMs = 200_000,
    )

    @Test
    fun `playlists survive a restart`() = runBlocking {
        val memory = MemoryStore()
        var clock = 1_000L
        val first = PlaylistLibrary(memory.store, clock = { clock++ })
        val added = first.add("Discovery", listOf(track("1")), artworkUrl = "https://example.test/a.jpg")

        val second = PlaylistLibrary(memory.store, clock = { clock++ })
        assertEquals(1, second.playlists.value.size)
        assertEquals(added, second.playlists.value.single())
        assertEquals("Discovery", second.get(added.id)?.name)
    }

    @Test
    fun `importing the same source playlist again replaces it in place`() = runBlocking {
        val memory = MemoryStore()
        var clock = 1_000L
        val library = PlaylistLibrary(memory.store, clock = { clock++ })
        val mine = PlaylistSource(ProviderId.SOUNDCLOUD, "sets/discovery", "https://soundcloud.com/x/sets/discovery")
        val first = library.add("Discovery", listOf(track("1")), source = mine)
        val other = library.add("Something else", listOf(track("2"), track("3")))

        val refreshed = library.add("Discovery (2026)", listOf(track("1"), track("4")), source = mine.copy(url = "https://soundcloud.com/x/sets/discovery?si=1"))

        assertEquals(listOf(first.id, other.id), library.playlists.value.map { it.id })
        assertEquals(first.id, refreshed.id, "a re-import keeps the entry's id")
        assertEquals("Discovery (2026)", refreshed.name)
        assertEquals(2, refreshed.tracks.size)
        assertEquals(2, library.playlists.value.size)
    }

    @Test
    fun `two imports of one source at the same instant still get different ids`() = runBlocking {
        val memory = MemoryStore()
        val library = PlaylistLibrary(memory.store, clock = { 5_000L })
        val a = library.add("One", listOf(track("1")))
        val b = library.add("Two", listOf(track("2")))
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `rename and remove are reflected in the stored file`() = runBlocking {
        val memory = MemoryStore()
        var clock = 1_000L
        val library = PlaylistLibrary(memory.store, clock = { clock++ })
        val playlist = library.add("Discovery", listOf(track("1")))

        assertTrue(library.rename(playlist.id, "  Homework  "))
        assertEquals("Homework", library.get(playlist.id)?.name)
        assertFalse(library.rename(playlist.id, "   "), "a blank name is refused")

        assertTrue(library.remove(playlist.id))
        assertFalse(library.remove(playlist.id), "removing twice reports that nothing was removed")
        assertNull(library.get(playlist.id))

        val reloaded = PlaylistLibrary(memory.store, clock = { clock++ })
        assertTrue(reloaded.playlists.value.isEmpty())
    }

    @Test
    fun `an unreadable file is an empty library, not a crash`() = runBlocking {
        val memory = MemoryStore("{ this is not json")
        val library = PlaylistLibrary(memory.store, clock = { 0L })
        assertTrue(library.playlists.value.isEmpty())
    }

    @Test
    fun `a source key without a playlist id falls back to the address`() {
        val byId = PlaylistSource(ProviderId.SPOTIFY, "abc", "https://open.spotify.com/playlist/abc?si=1")
        val byUrl = PlaylistSource(ProviderId.SPOTIFY, null, "https://open.spotify.com/playlist/abc?si=1")
        val local = PlaylistSource()
        assertEquals("SPOTIFY:abc", byId.key)
        assertEquals("SPOTIFY:https://open.spotify.com/playlist/abc?si=1", byUrl.key)
        assertNull(local.key)
    }

    @Test
    fun `a list of your own is made empty, takes songs once each, and gives them back`() = runBlocking {
        val memory = MemoryStore()
        val library = PlaylistLibrary(memory.store, clock = { 5L })
        val mine = library.create("  Late  ")
        assertEquals("Late", mine.name)
        assertTrue(mine.tracks.isEmpty())
        assertTrue(mine.isOwn)
        assertEquals("New playlist", library.create(" ").name)

        assertEquals(2, library.addTracks(mine.id, listOf(track("1"), track("2"))))
        assertEquals(1, library.addTracks(mine.id, listOf(track("2"), track("3"))))
        assertEquals(listOf("1", "2", "3"), library.get(mine.id)?.tracks?.map { it.id })
        assertTrue(library.contains(mine.id, track("3")))

        assertTrue(library.removeTrack(mine.id, track("2").key))
        assertFalse(library.removeTrack(mine.id, track("2").key))
        assertEquals(listOf("1", "3"), PlaylistLibrary(memory.store).get(mine.id)?.tracks?.map { it.id })
        assertEquals(0, library.addTracks("missing", listOf(track("9"))))
    }

    @Test
    fun `the heart fills one liked list, newest first, which leaves with its last song`() = runBlocking {
        val memory = MemoryStore()
        val library = PlaylistLibrary(memory.store, clock = { 7L })
        library.add("Night drive", listOf(track("x")))

        assertTrue(library.toggleLiked(track("1")))
        assertTrue(library.toggleLiked(track("2")))
        val liked = library.playlists.value.first()
        assertTrue(liked.isLiked)
        assertFalse(liked.isOwn)
        assertEquals(LIKED_PLAYLIST_NAME, liked.name)
        assertEquals(listOf("2", "1"), liked.tracks.map { it.id })
        assertTrue(library.isLiked(track("1")))

        assertFalse(library.toggleLiked(track("1")))
        assertFalse(library.isLiked(track("1")))
        assertTrue(library.removeTrack(LIKED_PLAYLIST_ID, track("2").key))
        assertNull(library.get(LIKED_PLAYLIST_ID))
        assertEquals(listOf("Night drive"), library.playlists.value.map { it.name })
    }

    @Test
    fun `lists read from a service or made by the mixer are not hand made`() = runBlocking {
        val library = PlaylistLibrary(MemoryStore().store)
        val read = library.add("Read", listOf(track("1")), source = PlaylistSource(ProviderId.SPOTIFY, "abc"))
        val mix = library.add("Mix", listOf(track("2")), smart = SmartSpec())
        assertFalse(read.isOwn)
        assertFalse(mix.isOwn)
    }

    @Test
    fun `a saved list keeps what was added and taken out here across a refresh, and its new name`() = runBlocking {
        val library = PlaylistLibrary(MemoryStore().store, clock = { 9L })
        val source = PlaylistSource(ProviderId.SPOTIFY, "abc")
        val saved = library.add("Discover", listOf(track("1"), track("2"), track("3")), source = source)
        assertTrue(saved.takesSongs)

        assertEquals(1, library.addTracks(saved.id, listOf(track("9"))))
        assertTrue(library.removeTrack(saved.id, track("2").key))
        assertTrue(library.rename(saved.id, "My discover"))

        // The service's list comes back with a change of its own (4 is new); ours stays on top of it.
        val again = library.add("Discover", listOf(track("1"), track("2"), track("3"), track("4")), source = source)
        assertEquals(saved.id, again.id)
        assertEquals(listOf("1", "3", "4", "9"), again.tracks.map { it.id })
        assertEquals("My discover", again.name)

        // Taking out a song added here forgets it; adding a removed one back undoes the removal.
        assertTrue(library.removeTrack(saved.id, track("9").key))
        assertEquals(1, library.addTracks(saved.id, listOf(track("2"))))
        val third = library.add("Discover", listOf(track("1"), track("2"), track("3")), source = source)
        assertEquals(listOf("1", "2", "3"), third.tracks.map { it.id })
    }

    @Test
    fun `a mix made again keeps the songs added to it`() = runBlocking {
        val library = PlaylistLibrary(MemoryStore().store)
        val mix = library.add("Mix", listOf(track("1")), smart = SmartSpec())
        library.addTracks(mix.id, listOf(track("7")))
        val remade = library.add("Mix", listOf(track("5"), track("6")), smart = SmartSpec(), replacing = mix.id)
        assertEquals(listOf("5", "6", "7"), remade.tracks.map { it.id })
    }
}
