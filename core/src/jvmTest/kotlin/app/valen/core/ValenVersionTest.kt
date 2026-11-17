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

class ReleaseNotesTest {
    @Test
    fun `GitHub's generated notes read as plain lines`() {
        val notes = """
            ## What's Changed
            * fix(audio): make peak limiting optional and balance EQ boost gain by @feelyourwarmth in https://github.com/dodo986/valen/pull/2
            * Add **bold** and [a link](https://example.com) and `code`


            **Full Changelog**: https://github.com/dodo986/valen/compare/v1.0.0-alpha.3...v1.0.0-alpha.4
        """.trimIndent()
        assertEquals(
            listOf(
                NotePiece.Heading("What's Changed"),
                NotePiece.Item("fix(audio): make peak limiting optional and balance EQ boost gain by @feelyourwarmth (#2)"),
                NotePiece.Item("Add bold and a link and code"),
            ),
            releaseNotes(notes),
        )
    }

    @Test
    fun `a heading with nothing under it and empty notes say nothing`() {
        assertEquals(emptyList<NotePiece>(), releaseNotes(""))
        assertEquals(emptyList<NotePiece>(), releaseNotes("## What's Changed\n\n**Full Changelog**: https://x"))
    }

    @Test
    fun `later keeps the sheet away a day, and only for that release`() {
        val asset = ReleaseAsset("linux-appimage", "a", 1, "0".repeat(64))
        val four = AppRelease("1.0.0-alpha.4", 10_000_104, true, asset, "p", "u")
        val five = four.copy(version = "1.0.0-alpha.5", code = 10_000_105)
        val prefs = UpdatePrefs().later(four, now = 1_000)
        assertFalse(prefs.mayShow(four, now = 1_000 + UpdatePrefs.LATER_MS - 1))
        assertTrue(prefs.mayShow(four, now = 1_000 + UpdatePrefs.LATER_MS))
        assertTrue(prefs.mayShow(five, now = 2_000))
    }
}
