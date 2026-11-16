/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The addresses a person can paste, and the ones Valen must refuse. */
class PlaylistLinksTest {

    @Test
    fun `a Spotify playlist is recognised however the link was shared`() {
        val plain = parsePlaylistLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assertEquals(ProviderId.SPOTIFY, plain?.provider)
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", plain?.playlistId)

        val shared = parsePlaylistLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123&nd=1")
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", shared?.playlistId)

        val locale = parsePlaylistLink("open.spotify.com/intl-de/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", locale?.playlistId)

        val uri = parsePlaylistLink("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", uri?.playlistId)
    }

    @Test
    fun `a Spotify album or track link is not a playlist`() {
        assertNull(parsePlaylistLink("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy"))
        assertNull(parsePlaylistLink("https://open.spotify.com/track/7qiZfU4dY1lWllzX7mPBI3"))
    }

    @Test
    fun `a YouTube Music playlist is its list parameter`() {
        val music = parsePlaylistLink("https://music.youtube.com/playlist?list=PLw-VjHDlEOgs658kAHR_LAaELX7Qo6Hcv")
        assertEquals(ProviderId.YOUTUBE_MUSIC, music?.provider)
        assertEquals("PLw-VjHDlEOgs658kAHR_LAaELX7Qo6Hcv", music?.playlistId)

        val video = parsePlaylistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=4")
        assertEquals("PL123", video?.playlistId)

        val prefixed = parsePlaylistLink("https://music.youtube.com/playlist?list=VLPL123")
        assertEquals("PL123", prefixed?.playlistId)
    }

    @Test
    fun `a YouTube link without a list is not a playlist`() {
        assertNull(parsePlaylistLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `a SoundCloud set is recognised and a bare track is not`() {
        val set = parsePlaylistLink("https://soundcloud.com/daft-punk/sets/discovery")
        assertEquals(ProviderId.SOUNDCLOUD, set?.provider)
        assertEquals("sets/discovery", set?.playlistId)
        assertEquals("https://soundcloud.com/daft-punk/sets/discovery", set?.url)

        assertNull(parsePlaylistLink("https://soundcloud.com/daft-punk/one-more-time"))
    }

    @Test
    fun `a short link keeps its address for the reader to resolve`() {
        val spotify = parsePlaylistLink("https://spotify.link/abcDEF")
        assertEquals(ProviderId.SPOTIFY, spotify?.provider)
        assertNull(spotify?.playlistId)
        assertNotNull(spotify?.url)

        val soundcloud = parsePlaylistLink("https://on.soundcloud.com/xyz123")
        assertEquals(ProviderId.SOUNDCLOUD, soundcloud?.provider)
        assertNull(soundcloud?.playlistId)
    }

    @Test
    fun `another service's link is refused`() {
        assertNull(parsePlaylistLink("https://music.apple.com/fr/playlist/chill/pl.u-abc"))
        assertNull(parsePlaylistLink("https://tidal.com/browse/playlist/1234"))
        assertNull(parsePlaylistLink("not a link at all"))
        assertNull(parsePlaylistLink("   "))
    }
}
