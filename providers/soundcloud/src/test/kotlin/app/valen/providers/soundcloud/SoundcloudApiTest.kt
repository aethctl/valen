/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.soundcloud

import app.valen.core.ProviderId
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SoundcloudApiTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `search maps the script's compact tracks`() {
        val value = json(
            """
            [
              {"id":"291313240","title":"Kerala","user":"Bonobo",
               "artwork":"https://i1.sndcdn.com/artworks-000123-large.jpg","duration":234000,
               "permalink":"https://soundcloud.com/bonobo/kerala","streamable":true},
              {"id":"2","title":"  ","user":"x","artwork":null,"duration":1,"permalink":null,"streamable":false}
            ]
            """.trimIndent(),
        )

        val tracks = SoundcloudApi.parseSearch(value, limit = 25)

        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals(ProviderId.SOUNDCLOUD, track.provider)
        assertEquals("291313240", track.id)
        assertEquals("Kerala", track.title)
        assertEquals("Bonobo", track.artist)
        assertEquals("https://i1.sndcdn.com/artworks-000123-t500x500.jpg", track.artworkUrl)
        assertEquals(234000L, track.durationMs)
        assertEquals("291313240", track.uri)
        assertFalse(track.explicit)
    }

    @Test
    fun `search honours the limit and survives a shape it does not know`() {
        val value = json("""[{"id":"1","title":"A"},{"id":"2","title":"B"},{"id":"3","title":"C"}]""")
        assertEquals(listOf("1", "2"), SoundcloudApi.parseSearch(value, limit = 2).map { it.id })
        assertTrue(SoundcloudApi.parseSearch(json("""{"collection":[]}"""), 25).isEmpty())
        assertTrue(SoundcloudApi.parseSearch(json("""null"""), 25).isEmpty())
    }

    @Test
    fun `state reads every field the adapter uses`() {
        val s = SoundcloudApi.parseState(
            json(
                """
                {"trackId":"42","positionMs":1200,"durationMs":300000,"paused":false,"buffering":true,
                 "ended":false,"signedIn":true,"volume":0.4,"error":null,"at":1}
                """.trimIndent(),
            ),
        )!!
        assertEquals("42", s.trackId)
        assertEquals(1200L, s.positionMs)
        assertEquals(300000L, s.durationMs)
        assertFalse(s.paused)
        assertTrue(s.buffering)
        assertFalse(s.ended)
        assertTrue(s.signedIn)
        assertEquals(0.4f, s.volume)
        assertNull(s.error)
    }

    @Test
    fun `state defaults and the error text`() {
        val s = SoundcloudApi.parseState(json("""{"error":"HLS-only track"}"""))!!
        assertNull(s.trackId)
        assertEquals(0L, s.positionMs)
        assertTrue(s.paused)
        assertFalse(s.buffering)
        assertFalse(s.ended)
        assertEquals(1f, s.volume)
        assertEquals("HLS-only track", s.error)
        assertNull(SoundcloudApi.parseState(null))
    }

    @Test
    fun `artwork is served at 500 px`() {
        assertEquals(
            "https://i1.sndcdn.com/artworks-1-t500x500.jpg",
            SoundcloudApi.artwork("https://i1.sndcdn.com/artworks-1-large.jpg"),
        )
        assertEquals(
            "https://i1.sndcdn.com/avatars-1-t500x500.png",
            SoundcloudApi.artwork("https://i1.sndcdn.com/avatars-1-t300x300.png"),
        )
        assertEquals(
            "https://i1.sndcdn.com/artworks-1-t500x500.jpg?x=1",
            SoundcloudApi.artwork("https://i1.sndcdn.com/artworks-1-crop.jpg?x=1"),
        )
        assertEquals(
            "https://i1.sndcdn.com/artworks-1-t500x500.jpg",
            SoundcloudApi.artwork("https://i1.sndcdn.com/artworks-1-t500x500.jpg"),
        )
        assertEquals("https://example.test/cover", SoundcloudApi.artwork("https://example.test/cover"))
        assertNull(SoundcloudApi.artwork(null))
        assertNull(SoundcloudApi.artwork("  "))
    }

    @Test
    fun `the page script is bundled and speaks the bridge protocol`() {
        val script = SoundcloudScript.SCRIPT
        assertTrue(script.contains("window.MyspotiPage"))
        assertTrue(script.contains("MyspotiHost.postMessage"))
        assertTrue(script.contains("__mspHostPost"))
        assertTrue(script.contains("HLS-only track"))
        // The client id is discovered, never written down.
        assertTrue(script.contains("__sc_hydration"))
    }
}
