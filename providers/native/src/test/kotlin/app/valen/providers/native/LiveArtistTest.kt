/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.myspoti.ytmusic.InnerTube
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** YouTube Music's real artist and album pages, only with `VALEN_LIVE=1`. */
class LiveArtistTest {
    @Test
    fun `an artist page and one of its albums read from YouTube Music`() = runBlocking {
        assumeTrue(System.getenv("VALEN_LIVE") == "1")
        val artists = YtmusicArtists(InnerTube(OkHttpClient(), cookies = { null }))
        val found = artists.find("Daft Punk", 5)
        println("found: " + found.map { "${it.name} (${it.id})" })
        val page = artists.artist(found.first { it.name == "Daft Punk" }.id)
        println("name=${page.artist.name} image=${page.artist.imageUrl?.take(60)} followers=${page.followers} about=${page.about?.take(60)}")
        println("top=" + page.topTracks.map { it.title })
        println("releases=" + page.releases.map { "${it.title} [${it.kind} ${it.year}]" })
        println("playlists=" + page.playlists.map { it.title })
        println("related=" + page.related.map { it.name })
        assertTrue(page.topTracks.isNotEmpty() && page.releases.isNotEmpty())
        val album = artists.release(page.releases.first().id)
        println("album ${album.release.title}: " + album.tracks.map { it.title })
        assertTrue(album.tracks.isNotEmpty())
    }
}
