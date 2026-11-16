/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The client id is scraped from SoundCloud's own page at runtime and must never be hardcoded, so
 * the shape it is read from is pinned here.
 */
class SoundcloudAudioTest {

    @Test
    fun `the client id is read from the page's boot data`() {
        val html = """<script>window.__sc_hydration = [{"hydratable":"apiClient","data":{"id":"QhVEWrrJKvW4uSU5u5EoI0FTXYUGzy5k","isExpiring":true}}];</script>"""
        assertEquals("QhVEWrrJKvW4uSU5u5EoI0FTXYUGzy5k", SoundcloudAudio.CLIENT_ID.find(html)?.groupValues?.get(1))
    }

    @Test
    fun `a page without the boot data yields nothing`() {
        assertNull(SoundcloudAudio.CLIENT_ID.find("<html><body>Sign in</body></html>"))
    }

    private fun row(vararg protocols: String) = Json.parseToJsonElement(
        """{"kind":"track","id":1,"title":"t","media":{"transcodings":[${protocols.joinToString(",") { "{\"url\":\"u\",\"format\":{\"protocol\":\"$it\"}}" }}]}}""",
    )

    @Test
    fun `a track streamed only copy-protected is told apart from one that plays`() {
        val audio = SoundcloudAudio()
        assertTrue(audio.protectedOnly(row("cbc-encrypted-hls", "ctr-encrypted-hls")))
        assertFalse(audio.protectedOnly(row("cbc-encrypted-hls", "hls")))
        assertFalse(audio.protectedOnly(row("progressive")))
        // A row without its streams listed is resolved when played, not judged here.
        assertFalse(audio.protectedOnly(Json.parseToJsonElement("""{"kind":"track","id":1,"title":"t"}""")))
    }
}
