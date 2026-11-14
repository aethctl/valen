/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
}
