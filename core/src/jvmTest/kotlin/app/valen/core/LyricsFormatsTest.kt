/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/** The words here are made up; no song's lyrics are kept in the tests. */
class LyricsFormatsTest {

    @Test
    fun `plain LRC is timed by line and each line ends where the next begins`() {
        val lrc = """
            [ti:Paper Moons]
            [00:01.50]chalk on the wall
            [00:04.00]rain on the glass
            [00:07.25]
            [00:12.00]we were here
        """.trimIndent()
        val lyrics = need(LyricsFormats.parse(lrc, "lrc", "LRCLIB", durationMs = 20_000))
        assertEquals(LyricsTiming.Lines, lyrics.timing)
        assertEquals(listOf("chalk on the wall", "rain on the glass", "we were here"), lyrics.lines.map { it.text })
        assertEquals(1_500, lyrics.lines[0].startMs)
        assertEquals(4_000, lyrics.lines[0].endMs)
        // The empty stamp closes the line before it: an instrumental gap follows.
        assertEquals(7_250, lyrics.lines[1].endMs)
        assertEquals(20_000, lyrics.lines[2].endMs)
    }

    @Test
    fun `enhanced LRC reads each word to the next and the last to its line`() {
        val lrc = "[00:10.00]<00:10.00>paint <00:10.40>it <00:10.70>loud\n[00:13.00]<00:13.00>again"
        val lyrics = need(LyricsFormats.parse(lrc, null, "x"))
        assertEquals(LyricsTiming.Words, lyrics.timing)
        val first = lyrics.lines[0]
        assertEquals(listOf("paint", "it", "loud"), first.words.map { it.text })
        assertEquals(listOf(10_000L, 10_400L, 10_700L), first.words.map { it.startMs })
        assertEquals(10_400, first.words[0].endMs)
        assertTrue(first.words[0].space)
        assertFalse(first.words[2].space)
        // No closing tag: the last word lasts to its line's end, capped.
        assertEquals(12_100, first.words[2].endMs)
        assertEquals("paint it loud", first.text)
    }

    @Test
    fun `words before the first tag are sung from the line start`() {
        val lyrics = need(LyricsFormats.parse("[00:02.00]hey <00:03.00>you<00:03.50>\n[00:05.00]<00:05.00>ok<00:05.40>", "lrc", "x"))
        val words = lyrics.lines[0].words
        assertEquals(listOf("hey", "you"), words.map { it.text })
        assertEquals(2_000, words[0].startMs)
        assertEquals(3_000, words[0].endMs)
    }

    @Test
    fun `LRC offset moves every stamp`() {
        val lyrics = need(LyricsFormats.parse("[offset:+500]\n[00:02.00]one\n[00:04.00]two", "lrc", "x"))
        assertEquals(listOf(1_500L, 3_500L), lyrics.lines.map { it.startMs })
    }

    @Test
    fun `MLX text reads ends, voices and backing vocals`() {
        val mlx = """
            [meta]
            source=Lyprio
            [song]
            title=Paper Moons
            [lyrics]
            [00:01.000~00:03.000|v1]<00:01.000>stay <00:01.500>up<00:02.400> [bg:<00:02.000>(stay<00:02.800>)]
            [00:03.200~00:05.000|v2]<00:03.200>call <00:03.900>me<00:04.600>
            [romanLyrics]
            [00:01.000]ignored
        """.trimIndent()
        val lyrics = need(LyricsFormats.parse(mlx, "mlx", "Lyprio"))
        assertEquals(2, lyrics.lines.size)
        val first = lyrics.lines[0]
        assertEquals("v1", first.voice)
        assertEquals(listOf("stay", "up"), first.words.map { it.text })
        assertEquals(2_400, first.words[1].endMs)
        assertEquals(listOf("(stay)"), first.backing.map { it.text })
        assertEquals(3_000, first.endMs)
        assertEquals(listOf("v1", "v2"), lyrics.voices)
    }

    @Test
    fun `MLX JSON is what Lyprio answers in`() {
        val json = """
            {"version":2,"provider":"Lyprio","lines":[
              {"startMs":1000,"endMs":2500,"text":"","agent":"v1","words":[
                {"text":"neon ","startMs":1000,"endMs":1400},{"text":"never","startMs":1400,"endMs":2400}]},
              {"startMs":2600,"endMs":3600,"text":"(never)","background":true,"words":[{"text":"(never)","startMs":2600,"endMs":3500}]},
              {"startMs":4000,"text":"just a line"}
            ]}
        """.trimIndent()
        val lyrics = need(LyricsFormats.parse(json, "mlx", "Lyprio"))
        // The backing line is sung inside the first line's time, so it goes under it.
        assertEquals(listOf("neon never", "just a line"), lyrics.lines.map { it.text })
        assertEquals("(never)", lyrics.lines[0].backingText)
        assertEquals(listOf("neon", "never"), lyrics.lines[0].words.map { it.text })
        assertTrue(lyrics.lines[0].words[0].space)
    }

    @Test
    fun `TTML reads word spans, spaces between them and backing vocals`() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word">
              <head><metadata><ttm:agent type="person" xml:id="v1"/></metadata></head>
              <body><div>
                <p begin="00:01.000" end="00:03.000" ttm:agent="v1"><span begin="00:01.000" end="00:01.600">Hold</span> <span begin="00:01.600" end="00:02.000">on</span><span begin="00:02.000" end="00:02.900">&amp;</span><span ttm:role="x-bg" begin="00:02.000" end="00:02.900"><span begin="00:02.000" end="00:02.900">(oh)</span></span></p>
                <p begin="4.5s" end="6s"><span begin="4.5s" end="5.2s">tight</span></p>
              </div></body>
            </tt>
        """.trimIndent()
        val lyrics = need(LyricsFormats.parse(ttml, "ttml", "Lyprio"))
        assertEquals(LyricsTiming.Words, lyrics.timing)
        val first = lyrics.lines[0]
        assertEquals(listOf("Hold", "on", "&"), first.words.map { it.text })
        assertTrue(first.words[0].space)
        assertFalse(first.words[1].space)
        assertEquals("Hold on&", first.text)
        assertEquals("oh", first.backingText)
        assertEquals("v1", first.voice)
        assertEquals(4_500, lyrics.lines[1].startMs)
        assertEquals(6_000, lyrics.lines[1].endMs)
    }

    @Test
    fun `plain text is untimed`() {
        val lyrics = need(LyricsFormats.parse("one\n\n two \nthree", "plain", "x"))
        assertEquals(LyricsTiming.None, lyrics.timing)
        assertEquals(listOf("one", "two", "three"), lyrics.lines.map { it.text })
    }

    @Test
    fun `a song half in words is shown by line`() {
        val lrc = "[00:01.00]<00:01.00>a <00:01.50>b\n[00:03.00]c d\n[00:05.00]e f\n[00:07.00]g h"
        val lyrics = need(LyricsFormats.parse(lrc, "lrc", "x"))
        assertEquals(LyricsTiming.Lines, lyrics.timing)
        assertTrue(lyrics.lines.all { it.words.isEmpty() })
    }

    @Test
    fun `nothing readable is no lyrics`() {
        assertNull(LyricsFormats.parse("   ", "lrc", "x"))
        assertNull(LyricsFormats.parse("[00:01.00]\n[00:02.00]", "lrc", "x"))
    }

    @Test
    fun `a header naming the song is dropped`() {
        val lyrics = need(LyricsFormats.parse("[00:00.50]Paper Moons - The Walls\n[00:02.00]chalk", "lrc", "x"))
        val clean = LyricsFormats.withoutHeader(lyrics, "Paper Moons", "The Walls")
        assertEquals(listOf("chalk"), clean.lines.map { it.text })
    }

    @Test
    fun `artists are compared by name, loosely`() {
        assertTrue(LyricsFormats.artistsMatch("The Walls feat. Kid", "Walls"))
        assertTrue(LyricsFormats.artistsMatch("Beyonce", "Beyoncé"))
        assertFalse(LyricsFormats.artistsMatch("The Walls", "Someone Else"))
        assertTrue(LyricsFormats.artistsMatch("宇多田ヒカル", "Hikaru Utada"))
    }

    @Test
    fun `a query is cleaned of what only advertises the upload`() {
        val q = Track(ProviderId.YOUTUBE_MUSIC, "abc", "The Walls - Paper Moons (Official Video) [HD]", "The Walls", durationMs = 200_000).lyricsQuery()
        assertEquals("Paper Moons", q.title)
        assertEquals("The Walls", q.artist)
        assertEquals("abc", q.videoId)
        val live = Track(ProviderId.SOUNDCLOUD, "1", "Paper Moons (Live) feat. Kid", "The Walls - Topic").lyricsQuery()
        assertEquals("Paper Moons (Live)", live.title)
        assertEquals("The Walls", live.artist)
    }
}

private fun <T : Any> need(value: T?): T = value ?: throw AssertionError("expected a value")
