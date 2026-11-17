/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValenVersionTest {
    @Test
    fun `the number is the build's (root build gradle kts makes the same one)`() {
        assertEquals(10_000_102, ValenVersion.parse("1.0.0-alpha.2")!!.code)
        assertEquals(10_000_999, ValenVersion.parse("1.0.0")!!.code)
        assertEquals(10_100_201, ValenVersion.parse("v1.1.0-beta.1")!!.code)
        assertEquals(10_000_300, ValenVersion.parse("1.0.0-rc")!!.code)
    }

    @Test
    fun `test builds come before the release they lead to`() {
        val order = listOf("1.0.0-alpha.1", "1.0.0-alpha.2", "1.0.0-alpha.10", "1.0.0-beta.1", "1.0.0-rc.1", "1.0.0", "1.0.1-alpha.1", "1.0.1", "1.10.0", "2.0.0")
            .map { ValenVersion.parse(it)!! }
        assertEquals(order, order.sorted())
        assertEquals(order.map { it.toString() }, order.map { ValenVersion.parse(it.toString())!!.toString() })
    }

    @Test
    fun `only versions parse`() {
        assertNull(ValenVersion.parse("ALPHA-1"))
        assertNull(ValenVersion.parse("1.0"))
        assertNull(ValenVersion.parse("1.0.0-nightly.1"))
        assertNull(ValenVersion.parse("100.0.0"))
    }

    @Test
    fun `test builds follow the build unless chosen`() {
        val test = ValenVersion.parse("1.0.0-alpha.2")
        val final = ValenVersion.parse("1.0.0")
        assertTrue(UpdatePrefs().takesTestBuilds(test))
        assertFalse(UpdatePrefs().takesTestBuilds(final))
        assertTrue(UpdatePrefs(testBuilds = true).takesTestBuilds(final))
        assertFalse(UpdatePrefs(testBuilds = false).takesTestBuilds(test))
    }
}
