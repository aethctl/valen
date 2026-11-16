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
}
