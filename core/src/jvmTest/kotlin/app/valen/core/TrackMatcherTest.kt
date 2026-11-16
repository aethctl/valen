/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The matcher's whole job: accept the same recording however a catalogue spells it, and refuse a
 * different take of it. A wrong match puts someone else's song in the playlist, which is worse
 * than an honest gap.
 */
class TrackMatcherTest {

    private fun track(
        title: String,
        artist: String,
        album: String? = "Album",
        durationMs: Long = 213_000,
        provider: ProviderId = ProviderId.YOUTUBE_MUSIC,
    ) = Track(
        provider = provider,
        id = "id-${title.hashCode()}",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    )

    private val studio = track("Get Lucky", "Daft Punk", album = "Random Access Memories", durationMs = 369_000)

    @Test
    fun `advertising in a title is not a different song`() {
        val video = track("Get Lucky (Official Video) [HD]", "Daft Punk", durationMs = 370_000)
        assertTrue(TrackMatcher.score(studio, video) >= TrackMatcher.ACCEPT)
        assertEquals(video, TrackMatcher.best(studio, listOf(video)))
    }

    @Test
    fun `accents and punctuation fold away`() {
        assertEquals("beyonce halo", TrackMatcher.normalizeText("Beyoncé — Halo"))
        assertEquals(TrackMatcher.normalizeText("Sigur Rós"), TrackMatcher.normalizeText("sigur ros"))
        val accented = track("Halo", "Beyoncé", album = "I Am... Sasha Fierce", durationMs = 262_000)
        val plain = track("Halo", "Beyonce", album = null, durationMs = 262_000)
        assertTrue(TrackMatcher.score(accented, plain) >= TrackMatcher.ACCEPT)
    }

    @Test
    fun `a remix the source did not ask for is refused`() {
        val remix = track("Get Lucky (Daft Punk Remix)", "Daft Punk", durationMs = 369_000)
        assertTrue(
            TrackMatcher.score(studio, remix) < TrackMatcher.ACCEPT,
            "a remix is a different recording and must not fill the song's place",
        )
        assertNull(TrackMatcher.best(studio, listOf(remix)))
    }

    @Test
    fun `a live album is refused even when its title and artist match`() {
        val live = track("Get Lucky", "Daft Punk", album = "Random Access Memories (Live)", durationMs = 369_000)
        assertTrue(TrackMatcher.score(studio, live) < TrackMatcher.ACCEPT)
    }

    @Test
    fun `length decides between two uploads of one title`() {
        val close = track("Get Lucky", "Daft Punk", durationMs = 371_000)
        val extended = track("Get Lucky", "Daft Punk", durationMs = 590_000)
        assertTrue(TrackMatcher.score(studio, close) > TrackMatcher.score(studio, extended))
        assertEquals(close, TrackMatcher.best(studio, listOf(extended, close)))
    }

    @Test
    fun `a different artist with the same title is not the song`() {
        val cover = track("Get Lucky", "The Lounge Kittens", durationMs = 369_000)
        assertTrue(TrackMatcher.score(studio, cover) < TrackMatcher.ACCEPT)
    }

    @Test
    fun `one title fully containing the other is close but not exact`() {
        val part = track("Get Lucky pt. 1", "Daft Punk", durationMs = 369_000)
        val score = TrackMatcher.score(studio, part)
        assertTrue(score in TrackMatcher.FALLBACK_ACCEPT..0.99, "was $score")
        assertFalse(score >= 0.99)
    }

    @Test
    fun `the query keeps the title and the lead artist only`() {
        val messy = track("Get Lucky (feat. Pharrell Williams) - Remastered 2013", "Daft Punk, Pharrell Williams")
        assertEquals("Get Lucky Daft Punk", TrackMatcher.query(messy))
    }

    @Test
    fun `ranked answers are best first with their scores`() {
        val weak = track("Get Lucky", "The Lounge Kittens", durationMs = 369_000)
        val good = track("Get Lucky", "Daft Punk", durationMs = 370_000)
        val ranked = TrackMatcher.ranked(studio, listOf(weak, good))
        assertEquals(listOf(good, weak), ranked.map { it.track })
        assertTrue(ranked[0].score >= ranked[1].score)
    }
}
