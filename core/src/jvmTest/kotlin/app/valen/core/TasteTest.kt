/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TasteTest {
    private fun t(title: String, artist: String, id: String = "$title-$artist", provider: ProviderId = ProviderId.SPOTIFY) =
        Track(provider, id, title, artist)

    private fun song(
        key: String,
        title: String,
        artist: String,
        ms: Long = 0,
        plays: Int = 0,
        genMs: Long = 0,
        genPlays: Int = 0,
        provider: ProviderId = ProviderId.SPOTIFY,
    ): Pair<String, Listened> = key to Listened(
        title = title,
        artist = artist,
        provider = provider,
        ms = ms,
        plays = plays,
        genMs = genMs,
        genPlays = genPlays,
    )

    @Test
    fun `told favourites and listening both count, and skips count against`() {
        val heardSong = t("Awake", "Tycho")
        val log = ListeningLog(
            totalMs = 600_000,
            songs = mapOf(heardSong.key to Listened("Awake", "Tycho", provider = ProviderId.SPOTIFY, ms = 500_000, plays = 6)),
            artists = mapOf("Tycho" to 500_000L, "Muzak" to 100_000L),
            skips = mapOf("SPOTIFY:x" to 3),
        )
        val taste = Taste(log, favouriteArtists = listOf("Daft Punk"), favouriteSongs = emptyList())
        assertTrue(taste.artistScore("daft punk") > taste.artistScore("Muzak"), "a favourite outranks a little listening")
        assertTrue(taste.artistScore("Tycho") > taste.artistScore("Nobody"), "listening counts")
        // Most of the listening outweighs a name given once; both lead the rest.
        assertEquals(listOf("Tycho", "Daft Punk"), taste.topArtists(2))
        val skipped = Taste(log.copy(skips = mapOf(heardSong.key to 9)), emptyList(), emptyList())
        assertTrue(skipped.songScore(heardSong) < Taste(log, emptyList(), emptyList()).songScore(heardSong))
    }

    @Test
    fun `one generated session is not evidence, two days of listening are`() {
        val songs = (1..3).associate { i ->
            song("SPOTIFY:one$i", "Song $i", "One Day Band", ms = 120_000, plays = 2)
        }
        val oneDay = ListeningLog(
            totalMs = 360_000,
            songs = songs,
            artists = mapOf("One Day Band" to 360_000L),
            daily = mapOf("100" to DayListening(ms = 360_000, songs = songs.keys.associateWith { 120_000L })),
        )
        val oneTaste = Taste(oneDay, emptyList(), emptyList())
        assertEquals(1, oneTaste.evidence("One Day Band").days)
        assertTrue(oneTaste.trust("One Day Band") < Taste.SEED_TRUST, "one session of listening never seeds a mix")
        val twoDays = oneDay.copy(
            daily = oneDay.daily + ("101" to DayListening(ms = 240_000, songs = songs.keys.take(2).associateWith { 120_000L })),
        )
        val twoTaste = Taste(twoDays, emptyList(), emptyList())
        assertTrue(twoTaste.evidence("One Day Band").days >= 2)
        assertTrue(twoTaste.trust("One Day Band") >= Taste.SEED_TRUST, "the same artist across 2+ days is trusted")
        assertEquals(1f, Taste(oneDay, listOf("One Day Band"), emptyList()).trust("One Day Band"), "a favourite is always trusted")
    }

    @Test
    fun `generated listening is counted apart, never as the person's choice`() {
        val generated = song("SPOTIFY:gen", "Generated Song", "Mix Band", ms = 600_000, plays = 10, genMs = 600_000, genPlays = 10)
        val mostlyGenerated = song("SPOTIFY:mostly", "Mostly Offered", "Offered Band", ms = 300_000, plays = 5, genMs = 200_000, genPlays = 3)
        val ownChoice = song("SPOTIFY:own", "Own Choice", "Own Band", ms = 300_000, plays = 5)
        val log = ListeningLog(
            totalMs = 1_200_000,
            songs = mapOf(generated, mostlyGenerated, ownChoice),
            artists = mapOf("Mix Band" to 600_000L, "Offered Band" to 300_000L, "Own Band" to 300_000L),
            daily = mapOf(
                "100" to DayListening(
                    ms = 1_200_000,
                    songs = mapOf("SPOTIFY:gen" to 600_000L, "SPOTIFY:mostly" to 300_000L, "SPOTIFY:own" to 300_000L),
                    genSongs = mapOf("SPOTIFY:gen" to 600_000L, "SPOTIFY:mostly" to 200_000L),
                ),
            ),
        )
        val taste = Taste(log, emptyList(), emptyList())
        assertEquals(1f, taste.evidence("Mix Band").generatedShare)
        assertEquals(0L, taste.evidence("Mix Band").ms, "generated time is not the person's own")
        assertTrue(taste.trust("Mix Band") < Taste.SEED_TRUST)
        assertEquals(0f, taste.artistScore("Mix Band"), "a generated afternoon does not lift an artist")
        val repeat = taste.onRepeat(5).map { it.title }
        assertTrue("Generated Song" !in repeat, "a song played only from a mix is not on repeat")
        assertTrue("Mostly Offered" !in repeat, "a song whose listening is mostly generated is not on repeat")
        assertTrue("Own Choice" in repeat, "the person's own listening is what on repeat means")
        assertEquals(300_000L, taste.evidence("Own Band").ms, "real listening still counts")
        assertEquals(0f, taste.evidence("Own Band").generatedShare)
        assertTrue(taste.trust("Own Band") > 0f, "one real day is something, even if not enough to seed")
    }

    @Test
    fun `a cover whose title looks ordinary is caught by title, unless that upload was played`() {
        val original = t("The Eyes of Ibad", "Panchiko")
        val log = ListeningLog(
            totalMs = 200_000,
            songs = mapOf(original.key to Listened("The Eyes of Ibad", "Panchiko", provider = ProviderId.SPOTIFY, ms = 200_000, plays = 3)),
            artists = mapOf("Panchiko" to 200_000L),
        )
        val taste = Taste(log, emptyList(), emptyList())
        val cover = t("The Eyes of Ibad", "Sleepy Gary", id = "cover")
        assertTrue(taste.isCover(cover))
        assertTrue(SmartMixer.derivative(cover, taste), "an unheard cover is a derivative")
        val playedCover = t("The Eyes of Ibad", "Sleepy Gary", id = "played")
        val playedTaste = Taste(
            log.copy(songs = log.songs + (playedCover.key to Listened("The Eyes of Ibad", "Sleepy Gary", provider = ProviderId.SPOTIFY, ms = 90_000, plays = 1))),
            emptyList(),
            emptyList(),
        )
        assertFalse(SmartMixer.derivative(playedCover, playedTaste), "once played here, that very upload is the person's")
        assertFalse(taste.isCover(t("The Eyes of Ibad", "Panchiko", id = "alt")), "another recording by the same artist is no cover")
        assertFalse(taste.isCover(t("Awake", "Someone Else")), "a short generic title is not enough to call a cover")
    }

    @Test
    fun `hidden uploads and disliked artists are known, told or inferred`() {
        val song = t("Song", "Artist")
        val taste = Taste(
            ListeningLog(),
            emptyList(),
            emptyList(),
            dislikedArtists = listOf("Disliked Band"),
            hiddenTracks = listOf(song.key),
        )
        assertTrue(taste.hidden(song))
        assertFalse(taste.hidden(t("Other", "Artist")))
        assertTrue(taste.explicitlyDisliked("Disliked Band"))
        assertTrue(taste.dislikes("Disliked Band"))
        assertFalse(taste.dislikes("Liked Band"))

        // Skipped away from: three skips and never as many plays is inferred dislike (resting).
        val skipped = t("Meh", "Skipped Band")
        val log = ListeningLog(
            totalMs = 20_000,
            songs = mapOf(skipped.key to Listened("Meh", "Skipped Band", provider = ProviderId.SPOTIFY, ms = 20_000, plays = 4)),
            artists = mapOf("Skipped Band" to 20_000L),
            skips = mapOf(skipped.key to 4),
        )
        val inferred = Taste(log, emptyList(), emptyList())
        assertTrue(inferred.restingArtist("Skipped Band"))
        assertTrue(inferred.dislikes("Skipped Band"))
        assertFalse(inferred.dislikes("New Band"))
        assertTrue(inferred.topArtists(5).none { it == "Skipped Band" }, "a skipped-away artist is left out of the top")
    }

    @Test
    fun `old logs without daily data trust plays and songs and are not punished`() {
        val songs = (1..2).associate { i ->
            song("SPOTIFY:old$i", "Old Song $i", "Old Band", ms = 150_000, plays = 3)
        }
        val log = ListeningLog(
            totalMs = 300_000,
            songs = songs,
            artists = mapOf("Old Band" to 300_000L),
        )
        val taste = Taste(log, emptyList(), emptyList())
        assertTrue(taste.trust("Old Band") >= Taste.SEED_TRUST, "an old log can still seed a mix")
        assertEquals(2, taste.evidence("Old Band").songs)
        assertTrue(taste.topArtists(1).contains("Old Band"))
    }

    @Test
    fun `an overplayed upload rests for a few days`() {
        val overplayed = t("Loop", "Band")
        val fresh = t("New", "Band")
        val log = ListeningLog(
            totalMs = 600_000,
            songs = mapOf(
                overplayed.key to Listened("Loop", "Band", provider = ProviderId.SPOTIFY, ms = 400_000, plays = 6),
                fresh.key to Listened("New", "Band", provider = ProviderId.SPOTIFY, ms = 60_000, plays = 1),
            ),
            artists = mapOf("Band" to 460_000L),
            daily = mapOf(
                "100" to DayListening(ms = 400_000, songs = mapOf(overplayed.key to 400_000L), plays = mapOf(overplayed.key to 3)),
                "98" to DayListening(ms = 60_000, songs = mapOf(fresh.key to 60_000L), plays = mapOf(fresh.key to 1)),
            ),
        )
        val taste = Taste(log, emptyList(), emptyList())
        assertTrue(taste.resting(overplayed), "heard away three times in the last days: let it rest")
        assertFalse(taste.resting(fresh))
    }

    @Test
    fun `recent listening leads, and a song heard on one service is heard on all`() {
        val old = song("SPOTIFY:old", "Old song", "Old Band", ms = 900_000, plays = 20)
        val new = song("SPOTIFY:new", "New song", "New Band", ms = 300_000, plays = 8)
        val skipped = song("SPOTIFY:meh", "Meh", "Skipped Band", ms = 20_000, plays = 4)
        val listening = ListeningLog(
            totalMs = 1_220_000,
            songs = mapOf(old, new, skipped),
            artists = mapOf("Old Band" to 900_000L, "New Band" to 300_000L, "Skipped Band" to 20_000L),
            skips = mapOf("SPOTIFY:meh" to 4),
            // Old Band was all of two months ago; New Band is this week.
            daily = mapOf(
                "20000" to DayListening(ms = 900_000, songs = mapOf("SPOTIFY:old" to 900_000L)),
                "20060" to DayListening(ms = 300_000, songs = mapOf("SPOTIFY:new" to 300_000L)),
            ),
        )
        val taste = Taste(listening, emptyList(), emptyList())
        assertEquals(listOf("New Band", "Old Band"), taste.topArtists(3))
        assertTrue(taste.dislikes("Skipped Band"))
        assertFalse(taste.dislikes("New Band"))

        // A recording is heard whichever service played it; the upload itself is not.
        val otherService = Track(ProviderId.SOUNDCLOUD, "sc", "Old song", "Old Band")
        assertTrue(taste.heard(otherService))
        assertFalse(taste.played(otherService))
    }

    @Test
    fun `a liked song played only from generated lists never becomes on repeat`() {
        val liked = t("Liked Song", "Favourite Band", id = "liked")
        val log = ListeningLog(
            totalMs = 400_000,
            songs = mapOf(liked.key to Listened("Liked Song", "Favourite Band", provider = ProviderId.SPOTIFY, ms = 400_000, plays = 5, genMs = 400_000, genPlays = 5)),
            artists = mapOf("Favourite Band" to 400_000L),
            daily = mapOf("100" to DayListening(ms = 400_000, songs = mapOf(liked.key to 400_000L), genSongs = mapOf(liked.key to 400_000L))),
        )
        // Told as a favourite song, but only ever heard from a generated list: not a repeat seed.
        val taste = Taste(log, emptyList(), listOf(liked))
        assertTrue(taste.onRepeat(5).isEmpty(), "generated listening does not make a repeat")
        assertTrue(taste.heard(liked), "it was still heard here")
        assertEquals(0L, taste.evidence("Favourite Band").ms)
    }

    @Test
    fun `a cover in the log does not make the original's own upload look like a cover`() {
        val original = song("SPOTIFY:orig", "The Eyes of Ibad", "Original Band", ms = 200_000, plays = 2)
        val cover = song("SPOTIFY:cover", "The Eyes of Ibad", "Cover Band", ms = 200_000, plays = 1)
        val both = ListeningLog(
            totalMs = 400_000,
            songs = mapOf(original, cover),
            artists = mapOf("Original Band" to 200_000L, "Cover Band" to 200_000L),
        )
        val taste = Taste(both, emptyList(), emptyList())
        assertFalse(
            taste.isCover(Track(ProviderId.YOUTUBE_MUSIC, "yt-new", "The Eyes of Ibad", "Original Band", durationMs = 200_000)),
            "the original artist's upload is not a cover just because a cover is in the log",
        )
        assertFalse(
            taste.isCover(Track(ProviderId.SOUNDCLOUD, "sc-cover", "The Eyes of Ibad", "Cover Band", durationMs = 200_000)),
            "a version heard here from this act is not a cover to it",
        )

        // Heard only as a cover by someone else: a third act's upload of that title is a cover.
        val coverOnly = Taste(both.copy(songs = mapOf(cover), artists = mapOf("Cover Band" to 200_000L)), emptyList(), emptyList())
        assertTrue(
            coverOnly.isCover(Track(ProviderId.YOUTUBE_MUSIC, "yt-third", "The Eyes of Ibad", "Third Band", durationMs = 200_000)),
            "a title only heard from another act is a cover",
        )
    }
}
