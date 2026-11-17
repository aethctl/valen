/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TasteTest {
    private fun t(title: String, artist: String, id: String = "$title-$artist") =
        Track(ProviderId.SPOTIFY, id, title, artist)

    private val heardSong = t("Awake", "Tycho")
    private val log = ListeningLog(
        totalMs = 600_000,
        songs = mapOf(heardSong.key to Listened("Awake", "Tycho", provider = ProviderId.SPOTIFY, ms = 500_000, plays = 6)),
        artists = mapOf("Tycho" to 500_000L, "Muzak" to 100_000L),
        skips = mapOf("SPOTIFY:x" to 3),
    )

    @Test
    fun `told favourites and listening both count, and skips count against`() {
        val taste = Taste(log, favouriteArtists = listOf("Daft Punk"), favouriteSongs = emptyList())
        assertTrue(taste.artistScore("daft punk") > taste.artistScore("Muzak"), "a favourite outranks a little listening")
        assertTrue(taste.artistScore("Tycho") > taste.artistScore("Nobody"), "listening counts")
        // Most of the listening outweighs a name given once; both lead the rest.
        assertEquals(listOf("Tycho", "Daft Punk"), taste.topArtists(2))
        val skipped = Taste(log.copy(skips = mapOf(heardSong.key to 9)), emptyList(), emptyList())
        assertTrue(skipped.songScore(heardSong) < Taste(log, emptyList(), emptyList()).songScore(heardSong))
    }

    @Test
    fun `a mix keeps one copy of a recording, caps each artist and never repeats an artist back to back`() {
        val taste = Taste(log, listOf("A", "B"), emptyList())
        val candidates = listOf("A", "B", "C", "D").flatMap { a -> (1..6).map { t("Song $it", a) } } +
            listOf(t("Song 1", "A", id = "other-copy"))
        val mix = SmartMixer(search = { emptyList() }, random = Random(1)).pick(candidates, SmartSpec(size = 8), taste)
        assertEquals(8, mix.size)
        assertEquals(mix.size, mix.distinctBy { recordingKey(it.title, it.artist) }.size)
        assertTrue(mix.groupBy { it.artist }.values.all { it.size <= 3 }, "no artist takes more than a quarter")
        assertTrue(mix.zipWithNext().none { (a, b) -> a.artist == b.artist }, "artists alternate")
        // Two artists cannot fill eight places under the cap: the list fills up anyway.
        val few = (1..10).map { t("Tune $it", "A") } + (1..10).map { t("Tune $it", "B") }
        assertEquals(8, SmartMixer(search = { emptyList() }).pick(few, SmartSpec(size = 8), taste).size)
    }

    @Test
    fun `an artist seed keeps that artist's songs, and fresh leans to the unheard`() = runBlocking {
        val taste = Taste(log, listOf("Tycho"), emptyList())
        val mixer = SmartMixer(
            search = { q -> listOf(t("New one", "Tycho"), t("Cover", "Someone Else"), t("Another", "Tycho")) },
            random = Random(2),
        )
        val fresh = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 3, discovery = 1f), taste)
        assertTrue(fresh.all { it.artist == "Tycho" }, "a song by someone else never rides on an artist seed")
        assertTrue(fresh.first() != heardSong, "fresh puts an unheard song first")
        val familiar = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 3, discovery = 0f), taste)
        assertEquals(heardSong.title, familiar.first().title, "familiar puts the loved song first")
    }

    @Test
    fun `spotify follows and genres become seeds`() = runBlocking {
        val asked = mutableListOf<String>()
        val mixer = SmartMixer(search = { q -> asked += q; emptyList() }, follows = { listOf("Followed") })
        mixer.build(SmartSpec(sources = setOf(SmartSource.SpotifyFollows, SmartSource.Genres), genres = listOf("lo-fi")), Taste(ListeningLog(), emptyList(), emptyList()))
        assertEquals(listOf("Followed", "lo-fi"), asked)
    }

    // The "completely random songs": a name that only contains the seed, someone else's upload,
    // and derivatives of the real song.
    @Test
    fun `an artist seed takes only songs that credit that artist, and never derivatives`() = runBlocking {
        val taste = Taste(ListeningLog(), listOf("Sia", "Drake"), emptyList())
        val found = listOf(
            t("Chandelier", "Sia"),
            t("Elastic Heart", "Sia - Topic"),
            t("Together", "Labrinth, Sia"),
            t("Once Upon a December", "Anastasia"),
            t("Found a Way", "Drake Bell"),
            t("God's Plan", "Drake"),
            t("God's Plan (Nightcore)", "Drake"),
            t("Chandelier - slowed + reverb", "Sia"),
            t("God's Plan (Piano Cover)", "Drake"),
            Track(ProviderId.SPOTIFY, "clip", "Hotline Bling snippet", "Drake", durationMs = 20_000),
        )
        val mixer = SmartMixer(search = { found }, random = Random(3))
        val mix = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 20, discovery = 0.5f), taste)
        assertEquals(setOf("Chandelier", "Elastic Heart", "Together", "God's Plan"), mix.map { it.title }.toSet())
    }

    @Test
    fun `a song heard on one service is heard on all, and a derivative once heard is the person's own`() {
        val heardHere = Track(ProviderId.SOUNDCLOUD, "sc", "Awake", "Tycho")
        val taste = Taste(log, emptyList(), emptyList())
        assertTrue(taste.heard(heardHere), "the SoundCloud copy of a song heard on Spotify")
        val loop = t("Awake (1 hour loop)", "Tycho", id = "loop")
        assertTrue(SmartMixer.derivative(loop, taste))
        val looped = Taste(log.copy(songs = log.songs + (loop.key to Listened(loop.title, "Tycho", provider = ProviderId.SPOTIFY, ms = 60_000, plays = 2))), emptyList(), emptyList())
        assertTrue(!SmartMixer.derivative(loop, looped))
    }

    @Test
    fun `recent listening leads, and an artist skipped away from is left out`() {
        val old = Listened("Old song", "Old Band", provider = ProviderId.SPOTIFY, ms = 900_000, plays = 20)
        val new = Listened("New song", "New Band", provider = ProviderId.SPOTIFY, ms = 300_000, plays = 8)
        val skipped = Listened("Meh", "Skipped Band", provider = ProviderId.SPOTIFY, ms = 20_000, plays = 4)
        val listening = ListeningLog(
            totalMs = 1_220_000,
            songs = mapOf("SPOTIFY:old" to old, "SPOTIFY:new" to new, "SPOTIFY:meh" to skipped),
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
        assertTrue(!taste.dislikes("New Band"))
    }

    @Test
    fun `new artists come from the related ones, and a stranger's song is not lifted over them`() = runBlocking {
        val taste = Taste(log, listOf("Tycho"), emptyList())
        val songs = mapOf(
            "Tycho" to listOf(t("Dive", "Tycho"), t("A Walk", "Tycho")),
            "Bonobo" to listOf(t("Kerala", "Bonobo")),
            "chill" to listOf(t("Random Upload", "Somebody")),
        )
        val asked = mutableListOf<String>()
        val mixer = SmartMixer(
            search = { q -> asked += q; songs[q].orEmpty() },
            related = { name -> if (name == "Tycho") listOf("Tycho", "Bonobo") else emptyList() },
            random = Random(4),
        )
        val mix = mixer.build(
            SmartSpec(sources = setOf(SmartSource.Favourites, SmartSource.Genres), genres = listOf("chill"), size = 10, discovery = 0.8f),
            taste,
        )
        assertEquals(listOf("Tycho", "Bonobo", "chill"), asked, "the seed, a related artist, then the genre")
        assertTrue(mix.any { it.artist == "Bonobo" }, "a related artist's song is offered")
        assertTrue(mix.indexOfFirst { it.artist == "Bonobo" } < mix.indexOfFirst { it.artist == "Somebody" } ||
            mix.none { it.artist == "Somebody" }, "the related artist ranks above a stranger: $mix")
    }
}
