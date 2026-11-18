/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PresenceTest {
    private val song = Track(
        provider = ProviderId.SPOTIFY,
        id = "2Foc5Q5nqNiosCNqttzHof",
        title = "Get Lucky",
        artist = "Daft Punk, Pharrell Williams",
        album = "Random Access Memories",
        artworkUrl = "https://i.scdn.co/image/abc",
        durationMs = 248_000,
    )

    private fun now(
        track: Track = song,
        playing: Boolean = true,
        positionMs: Long = 60_000,
        durationMs: Long = 248_000,
        line: String? = null,
        kept: Boolean = false,
        place: Int = 3,
        of: Int = 41,
    ) = PresenceNow(track, playing, positionMs, durationMs, line = line, kept = kept, place = place, of = of)

    private fun card(
        now: PresenceNow = now(),
        settings: PresenceSettings = PresenceSettings(),
        homepage: String? = null,
        nowMs: Long = 1_000_000,
    ) = PresenceCard.of(now = now, settings = settings, homepage = homepage, nowMs = nowMs)

    @Test
    fun `a playing song is the same listening card it always was`() {
        val a = card()
        assertEquals("Valen", a.memberText)
        assertEquals("Get Lucky", a.details)
        assertEquals("Daft Punk, Pharrell Williams", a.state)
        assertEquals("https://i.scdn.co/image/abc", a.largeImage)
        assertEquals("Random Access Memories · from Spotify", a.largeText)
        assertEquals(PresenceCard.MARK, a.smallImage)
        assertEquals("Valen · 3 of 41 in the queue", a.smallText)
        assertEquals(1_000_000 - 60_000, a.startMs)
        assertEquals(1_000_000 - 60_000 + 248_000, a.endMs)
        assertEquals(
            listOf(
                "Play on Spotify" to "https://open.spotify.com/track/2Foc5Q5nqNiosCNqttzHof",
                "Get Valen" to PresenceCard.HOMEPAGE,
            ),
            a.buttons.map { it.label to it.url },
        )
    }

    @Test
    fun `paused says so, keeps no time bar and goes by the pause setting`() {
        val a = card(now = now(playing = false))
        assertEquals("Paused · Daft Punk, Pharrell Williams", a.state)
        assertNull(a.startMs)
        assertNull(a.endMs)
        assertFalse(PresencePause.KEEP.gone(60 * 60_000L))
        assertFalse(PresencePause.FIVE_MINUTES.gone(4 * 60_000L))
        assertTrue(PresencePause.FIVE_MINUTES.gone(6 * 60_000L))
        assertFalse(PresencePause.FIFTEEN_MINUTES.gone(10 * 60_000L))
        assertTrue(PresencePause.FIFTEEN_MINUTES.gone(16 * 60_000L))
        assertTrue(PresencePause.AT_ONCE.gone(0))
    }

    @Test
    fun `the sung line takes the artist's place while it lasts, and never while paused`() {
        val sung = PresenceSettings(state = "{status} · {line|artist}")
        val singing = card(now = now(line = "We're up all night to get lucky"), settings = sung)
        assertEquals("♪ We're up all night to get lucky", singing.state)
        val paused = card(now = now(playing = false, line = "We're up all night to get lucky"), settings = sung)
        assertEquals("Paused · Daft Punk, Pharrell Williams", paused.state)
    }

    @Test
    fun `the lines are templates with the song's own words`() {
        val settings = PresenceSettings(details = "{artist}", state = "{title} · {album}")
        val a = card(settings = settings)
        assertEquals("Daft Punk, Pharrell Williams", a.details)
        assertEquals("Get Lucky · Random Access Memories", a.state)

        val noAlbum = card(now = now(track = song.copy(album = null)), settings = PresenceSettings(state = "{album}"))
        assertEquals("", noAlbum.state, "an empty line is left out, not sent blank")
        assertNull(noAlbum.activity(PresenceDisplay.NAME)["state"])
    }

    @Test
    fun `a fallback token is the first value that has something to say`() {
        val settings = PresenceSettings(state = "{line|album|artist}")
        assertEquals("Random Access Memories", card(settings = settings).state)
        assertEquals("♪ one", card(now = now(line = "one"), settings = settings).state)

        assertEquals("{titel}", PresenceCard.resolve("{titel}", now()))
        assertEquals("Daft Punk, Pharrell Williams", PresenceCard.resolve("{line|artist}", now()))
        assertEquals("Daft Punk, Pharrell Williams", PresenceCard.resolve("{LINE | artist}", now()))
    }

    @Test
    fun `an empty value takes one separator with it`() {
        assertEquals("from Spotify", PresenceCard.resolve("{album} · {source}", now(track = song.copy(album = null))))
        assertEquals("from Spotify", PresenceCard.resolve("{album} - {source}", now(track = song.copy(album = null))))
        assertEquals("Get Lucky", PresenceCard.resolve("{title} · {album}", now(track = song.copy(album = null))))

        assertEquals(
            "Get Lucky · Daft Punk, Pharrell Williams",
            PresenceCard.resolve("{title} · {album} · {album|artist}", now(track = song.copy(album = null))),
        )
        assertEquals("Get Lucky · Daft Punk", PresenceCard.resolve(" {title} ·  · {artist} ", now(track = song.copy(artist = "Daft Punk"))))
        assertEquals("Paused · Daft Punk", PresenceCard.resolve("{status} · {artist}", now(playing = false, track = song.copy(artist = "Daft Punk"))))
        assertEquals("♪ one · Daft Punk", PresenceCard.resolve("{line} · {artist}", now(line = "one", track = song.copy(artist = "Daft Punk"))))
    }

    @Test
    fun `the member list repeats the line that was chosen, or the name`() {
        fun status(
            display: PresenceDisplay,
            settings: PresenceSettings = PresenceSettings(details = "{artist}"),
            now: PresenceNow = now(),
        ): Pair<Int, String> {
            val a = card(now = now, settings = settings.copy(display = display))
            return a.activity(display)["status_display_type"]!!.jsonPrimitive.content.toInt() to a.memberText
        }
        assertEquals(0 to "Valen", status(PresenceDisplay.NAME))
        assertEquals(2 to "Daft Punk, Pharrell Williams", status(PresenceDisplay.FIRST))
        assertEquals(1 to "Daft Punk, Pharrell Williams", status(PresenceDisplay.SECOND))

        assertEquals(
            0 to "Valen",
            status(PresenceDisplay.FIRST, PresenceSettings(details = "{album}", state = ""), now(track = song.copy(album = null))),
        )
    }

    @Test
    fun `the time can be a bar, a count up, a count down or nothing`() {
        val at = 1_000_000L
        val bar = card(settings = PresenceSettings(time = PresenceTime.PROGRESS), nowMs = at)
        assertEquals(at - 60_000, bar.startMs)
        assertEquals(at - 60_000 + 248_000, bar.endMs)

        val up = card(settings = PresenceSettings(time = PresenceTime.ELAPSED), nowMs = at)
        assertEquals(at - 60_000, up.startMs)
        assertNull(up.endMs)

        val down = card(settings = PresenceSettings(time = PresenceTime.REMAINING), nowMs = at)
        assertNull(down.startMs)
        assertEquals(at + (248_000 - 60_000), down.endMs)

        val off = card(settings = PresenceSettings(time = PresenceTime.OFF), nowMs = at)
        assertNull(off.startMs)
        assertNull(off.endMs)
        assertNull(off.activity(PresenceDisplay.NAME)["timestamps"])
    }

    @Test
    fun `pictures can be the cover with the mark, the mark alone, or none`() {
        val marked = card(settings = PresenceSettings(cover = false))
        assertEquals(PresenceCard.MARK, marked.largeImage, "the mark stands in for a cover that is off")
        assertNull(marked.smallImage, "the mark is never drawn on itself")

        val plain = card(settings = PresenceSettings(mark = false))
        assertEquals("https://i.scdn.co/image/abc", plain.largeImage)
        assertNull(plain.smallImage)

        val none = card(settings = PresenceSettings(cover = false, mark = false))
        assertNull(none.largeImage)
        assertNull(none.activity(PresenceDisplay.NAME)["assets"])

        val local = Track(ProviderId.LOCAL, "/m/a.flac", "Kerala", "Bonobo", artworkUrl = "file:///m/a.jpg")
        assertEquals(PresenceCard.MARK, card(now = now(track = local)).largeImage)
    }

    @Test
    fun `the two hover lines are templates too`() {
        val settings = PresenceSettings(largeText = "{title} · {service}", smallText = "{kept}")
        val a = card(settings = settings)
        assertEquals("Get Lucky · Spotify", a.largeText)
        assertEquals("", a.smallText, "not kept: nothing left to say")
        val kept = card(now = now(kept = true), settings = settings)
        assertEquals("kept on this device", kept.smallText)
        assertEquals("Get Lucky · Spotify", kept.largeText)
    }

    @Test
    fun `buttons can be the song's page, Valen's page or none`() {
        val songOnly = card(settings = PresenceSettings(homeButton = false))
        assertEquals(listOf("Play on Spotify"), songOnly.buttons.map { it.label })

        val homepage = card(homepage = "https://valen.example")
        assertEquals("Get Valen" to "https://valen.example", homepage.buttons.last().let { it.label to it.url })

        val none = card(settings = PresenceSettings(songButton = false, homeButton = false))
        assertTrue(none.buttons.isEmpty())
        assertNull(none.activity(PresenceDisplay.NAME)["buttons"])

        val local = Track(ProviderId.LOCAL, "/m/a.flac", "Kerala", "Bonobo")
        assertEquals(listOf("Get Valen"), card(now = now(track = local)).buttons.map { it.label })
    }

    @Test
    fun `texts are kept inside Discord's limits`() {
        val long = song.copy(title = "x".repeat(200), artist = "y")
        val json = card(now = now(track = long)).activity(PresenceDisplay.NAME)
        assertEquals(128, json["details"]!!.jsonPrimitive.content.length)
        assertEquals("y ", json["state"]!!.jsonPrimitive.content, "at least two characters")
    }

    @Test
    fun `the line being sung is only asked for when a template asks for it`() {
        assertFalse(PresenceCard.needsLine(PresenceSettings()), "the sung line is off by default")
        assertTrue(PresenceCard.needsLine(PresenceSettings(state = "{status} · {line|artist}")))
        assertTrue(PresenceCard.needsLine(PresenceSettings(details = "{line}")))
        assertTrue(PresenceCard.needsLine(PresenceSettings(largeText = " {LINE} ")))
        assertFalse(PresenceCard.needsLine(PresenceSettings(details = "{title}", state = "{artist}", largeText = "{album}", smallText = "{queue}")))
    }

    @Test
    fun `the line at a moment is the last one started, and nothing in a long gap`() {
        val lyrics = LyricsState.Found(
            Lyrics(
                listOf(LyricLine(1_000, 3_000, "one"), LyricLine(3_000, 5_000, "two"), LyricLine(20_000, 22_000, "three")),
                LyricsTiming.Lines,
                "test",
            ),
        )
        assertNull(PresenceCard.lineAt(lyrics, 500))
        assertEquals("one", PresenceCard.lineAt(lyrics, 1_200))
        assertEquals("two", PresenceCard.lineAt(lyrics, 4_000))
        assertNull(PresenceCard.lineAt(lyrics, 10_000))
        assertNull(PresenceCard.lineAt(LyricsState.Missing, 1_200))
    }

    @Test
    fun `times are written the way people read them`() {
        assertEquals("0:00", PresenceCard.clockTime(0))
        assertEquals("1:02", PresenceCard.clockTime(62_000))
        assertEquals("59:59", PresenceCard.clockTime(3_599_000))
        assertEquals("1:00:00", PresenceCard.clockTime(3_600_000))
    }

    @Test
    fun `settings written by the first Discord pass still turn the card on`() {
        val old = """
            {"enabled":true,"cover":false,"progress":true,"lyricLine":false,"service":true,
             "buttons":false,"pausedClearMinutes":15}
        """.trimIndent()
        val settings = SettingsStore(read = { """{"presence":$old}""" }, write = {}).load()
        assertTrue(settings.presence.enabled)
        assertEquals(
            PresenceSettings().copy(enabled = true, cover = false),
            settings.presence,
            "the switches that kept their names carry over; the rest fall back to the new defaults",
        )
    }
}
