/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserIdentityTest {

    // Real desktop Chrome headers: the grease brand, its version and the order follow the major.
    @Test fun `brands match what Chrome itself sends`() {
        val real = mapOf(
            116 to "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Google Chrome\";v=\"116\"",
            120 to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            124 to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
            131 to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
        )
        real.forEach { (major, header) -> assertEquals(header, BrowserIdentity(major, 1, 1, "6.8.0").secChUa) }
    }

    // The 2026-11-16 regression: Chrome/14x in the user agent, "Chromium 133" in the hints.
    @Test fun `the user agent and the hints name the same version`() {
        val id = BrowserIdentity.resolve("142.0.7444.171", "6.6.9")
        assertTrue(id.userAgent.contains("Chrome/142.0.0.0 "))
        assertEquals("142.0.7444.171", id.fullVersion)
        assertTrue(id.brands.filter { it.name == "Chromium" || it.name == "Google Chrome" }.all { it.major == "142" })
        assertEquals(setOf("Chromium", "Google Chrome"), id.brands.map { it.name }.filter { !it.startsWith("Not") }.toSet())
    }

    @Test fun `an unreadable WebView version falls back to a plausible Chrome`() {
        val id = BrowserIdentity.resolve("not a version", "nonsense")
        assertTrue(id.major in 100..300 && id.build > 0)
        assertEquals(BrowserIdentity.KERNELS.first(), id.kernel)
        assertNull(BrowserIdentity.parseEngine("141.0"))
        assertNull(BrowserIdentity.parseEngine(null))
    }
}
