/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Playlist files from this device: every common shape read, paths made absolute, streams left out. */
class LocalFilesTest {

    @Test
    fun `extended m3u keeps names, lengths and order, relative paths against its folder`() {
        val text = """
            #EXTM3U
            #PLAYLIST:Night drive
            #EXTINF:215,Daft Punk - Get Lucky
            ../Daft Punk/Random Access Memories/08 Get Lucky.flac
            #EXTINF:-1,Radio
            https://radio.example/stream
            file:///home/me/Music/Bonobo%20-%20Kerala.mp3
            C:\Music\old.mp3
        """.trimIndent()
        val read = parseLocalPlaylist(text, "/home/me/Music/Playlists", "m3u8")
        assertEquals("Night drive", read.name)
        assertEquals(3, read.entries.size)
        val first = read.entries[0]
        assertEquals("/home/me/Music/Daft Punk/Random Access Memories/08 Get Lucky.flac", first.path)
        assertEquals("Get Lucky", first.title)
        assertEquals("Daft Punk", first.artist)
        assertEquals(215_000, first.durationMs)
        assertEquals("/home/me/Music/Bonobo - Kerala.mp3", read.entries[1].path)
        // Another machine's drive letter stays as written: the importer can still match it by name.
        assertEquals("C:/Music/old.mp3", read.entries[2].path)
    }

    @Test
    fun `pls and xspf read the same way`() {
        val pls = """
            [playlist]
            File2=/m/b.ogg
            File1=a.flac
            Title1=Air - La femme d'argent
            Length1=430
            NumberOfEntries=2
        """.trimIndent()
        val p = parseLocalPlaylist(pls, "/m", "pls")
        assertEquals(listOf("/m/a.flac", "/m/b.ogg"), p.entries.map { it.path })
        assertEquals("Air", p.entries[0].artist)
        assertEquals("La femme d'argent", p.entries[0].title)
        assertEquals(430_000, p.entries[0].durationMs)

        val xspf = """
            <?xml version="1.0"?><playlist version="1" xmlns="http://xspf.org/ns/0/"><title>Rock &amp; roll</title>
            <trackList><track><location>file:///m/x%20y.mp3</location><title>X</title><creator>Y</creator><duration>1000</duration></track></trackList></playlist>
        """.trimIndent()
        val x = parseLocalPlaylist(xspf, "/m", "xspf")
        assertEquals("Rock & roll", x.name)
        assertEquals("/m/x y.mp3", x.entries.single().path)
        assertEquals("Y", x.entries.single().artist)
    }

    @Test
    fun `a pasted path is a playlist on this device, a web address is not`() {
        val link = parsePlaylistLink("/home/me/Music/list.m3u")
        assertEquals(ProviderId.LOCAL, link?.provider)
        assertEquals("/home/me/Music/list.m3u", link?.playlistId)
        assertEquals("/home/me/a b.m3u", parsePlaylistLink("file:///home/me/a%20b.m3u")?.playlistId)
        assertEquals(ProviderId.SPOTIFY, parsePlaylistLink("https://open.spotify.com/playlist/abc")?.provider)
        assertNull(entryPath("https://example.com/x.mp3", "/m"))
        assertEquals("/a/c", normalisePath("/a/./b/../c"))
        assertTrue(isAudioPath("/x/Song.FLAC"))
    }

    @Test
    fun `local songs come first in the order and never take a place among the services`() {
        assertEquals(ProviderId.services, AppSettings().priority)
        assertTrue(ProviderId.LOCAL !in ProviderId.services)
    }
}
