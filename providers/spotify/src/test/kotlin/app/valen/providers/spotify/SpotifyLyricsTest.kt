/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.spotify

import app.myspoti.api.Lyrics
import app.myspoti.api.LyricsLine
import app.myspoti.api.LyricsWord
import app.valen.core.LyricsTiming
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spotify's lyrics card read into Valen's lyrics. The words are made up. */
class SpotifyLyricsTest {
    private fun card(lines: List<LyricsLine>, synced: Boolean = true) =
        Lyrics(lines, synced, "en", "MusixMatch", null, null, null)

    @Test
    fun `lines are timed, a note line ends the line before it, and the credit names the source`() {
        val lyrics = card(
            listOf(
                LyricsLine(1_000, null, "chalk on the wall"),
                LyricsLine(4_000, null, "rain on the glass"),
                LyricsLine(7_000, null, "♪"),
                LyricsLine(15_000, null, "we were here"),
            ),
        ).toValen(durationMs = 20_000)!!
        assertEquals("Spotify · Musixmatch", lyrics.source)
        assertEquals(LyricsTiming.Lines, lyrics.timing)
        assertEquals(listOf("chalk on the wall", "rain on the glass", "we were here"), lyrics.lines.map { it.text })
        assertEquals(7_000, lyrics.lines[1].endMs)
        assertEquals(20_000, lyrics.lines[2].endMs)
    }

    @Test
    fun `syllables that spell the line become word timing`() {
        val lyrics = card(
            listOf(
                LyricsLine(1_000, 2_400, "paint it loud", listOf(LyricsWord(1_000, "paint"), LyricsWord(1_500, "it"), LyricsWord(1_800, "loud"))),
                LyricsLine(3_000, 4_000, "again", listOf(LyricsWord(3_000, "a"), LyricsWord(3_300, "gain"))),
            ),
        ).toValen(null)!!
        assertEquals(LyricsTiming.Words, lyrics.timing)
        val first = lyrics.lines[0].words
        assertEquals(listOf("paint", "it", "loud"), first.map { it.text })
        assertEquals(listOf(true, true, false), first.map { it.space })
        assertEquals(1_500, first[0].endMs)
        assertEquals(2_400, first[2].endMs)
        // Two syllables of one word: no space between them.
        assertEquals(listOf(false, false), lyrics.lines[1].words.map { it.space })
    }

    @Test
    fun `syllables that do not spell the line are dropped, and untimed cards are a page`() {
        val odd = card(listOf(LyricsLine(1_000, 2_000, "paint it", listOf(LyricsWord(1_000, "paint"), LyricsWord(1_500, "this"))))).toValen(null)!!
        assertTrue(odd.lines[0].words.isEmpty())
        val page = card(listOf(LyricsLine(0, null, "one"), LyricsLine(0, null, "two")), synced = false).toValen(null)!!
        assertEquals(LyricsTiming.None, page.timing)
    }
}
