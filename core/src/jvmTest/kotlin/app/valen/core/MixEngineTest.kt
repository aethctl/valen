/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MixEngineTest {
    private fun t(
        title: String,
        artist: String,
        id: String = "$title@$artist",
        provider: ProviderId = ProviderId.SPOTIFY,
        durationMs: Long = 200_000L,
    ) = Track(provider, id, title, artist, durationMs = durationMs)

    private fun rec(track: Track) = recordingKey(track.title, track.artist)

    /** A log whose [artists] each have three days of real listening (a minute a day): they are trusted. */
    private fun evidenceLog(
        vararg artists: String,
        extra: Map<String, Listened> = emptyMap(),
        skips: Map<String, Int> = emptyMap(),
    ): ListeningLog {
        val songs = LinkedHashMap<String, Listened>()
        artists.forEachIndexed { index, artist ->
            songs["SPOTIFY:evidence-$index"] = Listened("Evidence $index", artist, provider = ProviderId.SPOTIFY, ms = 180_000, plays = 4)
        }
        songs.putAll(extra)
        val daily = LinkedHashMap<String, DayListening>()
        for (day in 0L until 3L) {
            daily[(1000L + day).toString()] = DayListening(
                ms = 60_000L * artists.size,
                songs = artists.indices.associate { "SPOTIFY:evidence-$it" to 60_000L },
            )
        }
        return ListeningLog(
            totalMs = 180_000L * artists.size + extra.values.sumOf { it.ms },
            songs = songs,
            artists = artists.associateWith { 180_000L },
            daily = daily,
            skips = skips,
        )
    }

    private fun tasteOf(
        vararg artists: String,
        disliked: List<String> = emptyList(),
        hidden: List<String> = emptyList(),
        extra: Map<String, Listened> = emptyMap(),
        skips: Map<String, Int> = emptyMap(),
    ): Taste = Taste(evidenceLog(*artists, extra = extra, skips = skips), artists.toList(), emptyList(), disliked, hidden)

    private fun top(vararg tracks: Track): suspend (String) -> List<Track> =
        { name -> if (name.equals(tracks.firstOrNull()?.artist, ignoreCase = true)) tracks.toList() else emptyList() }

    // 1. Entity-exact new artists: a same-named artist on another service cannot stand in.
    @Test
    fun `a related artist is the service's exact artist, never a same-named one elsewhere`() = runBlocking {
        val taste = tasteOf("Tycho")
        val bonobo = RelatedArtist("Bonobo", ProviderId.YOUTUBE_MUSIC, "UC-bonobo", 0)
        val junk = t("Kerala", "Bonobo", id = "sc-fake", provider = ProviderId.SOUNDCLOUD)
        val real = t("Kerala", "Bonobo", id = "yt-real", provider = ProviderId.YOUTUBE_MUSIC)
        val queries = mutableListOf<String>()
        val mixer = SmartMixer(
            search = { q -> queries += q; if (q == "Bonobo") listOf(junk) else emptyList() },
            related = { name -> if (name == "Tycho") listOf(bonobo) else emptyList() },
            relatedSongs = { artist -> if (artist == bonobo) listOf(real) else emptyList() },
            artistTop = { name -> if (name == "Tycho") listOf(t("Dive", "Tycho", id = "yt-dive")) else emptyList() },
            random = Random(1),
        )
        val mix = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 10, discovery = 1f), taste)
        assertTrue(mix.any { it.provider == ProviderId.YOUTUBE_MUSIC && it.id == "yt-real" }, "the exact related page's song is offered: $mix")
        assertTrue(mix.none { it.id == "sc-fake" }, "a same-named artist on another service can never stand in: $mix")
        assertTrue("Bonobo" !in queries, "a related artist is never name-searched")
    }

    // 2. Seed artists: artistTop only. A search-only fan upload never appears, and search is not asked.
    @Test
    fun `a seed artist's list comes from the artist page, never a name search`() = runBlocking {
        val taste = tasteOf("Tycho")
        val queries = mutableListOf<String>()
        val mixer = SmartMixer(
            search = { q -> queries += q; listOf(t("Dive (fan upload)", "Tycho", id = "sc-fan")) },
            artistTop = { name ->
                if (name == "Tycho") listOf(t("Dive", "Tycho", id = "yt-dive"), t("A Walk", "Tycho", id = "yt-walk")) else emptyList()
            },
            random = Random(1),
        )
        val mix = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 10, discovery = 0f), taste)
        assertTrue(mix.any { it.id == "yt-dive" }, "the artist page's songs are the seed's: $mix")
        assertTrue(mix.none { it.id == "sc-fan" }, "a fan upload that a name search returns never appears: $mix")
        assertTrue(queries.isEmpty(), "the seed artist is never name-searched while its page answers")
    }

    // 2b. The related list can name an artist, but its exact page says nothing: drop it, never search it.
    @Test
    fun `a related artist without an exact page is dropped, not name-searched`() = runBlocking {
        val taste = tasteOf("Tycho")
        val queries = mutableListOf<String>()
        val mixer = SmartMixer(
            search = { q -> queries += q; listOf(t("Kerala", "Bonobo", id = "sc-bonobo")) },
            related = { listOf(RelatedArtist("Bonobo", ProviderId.YOUTUBE_MUSIC, "UC-bonobo", 0)) },
            relatedSongs = { emptyList() },
            artistTop = { name -> if (name == "Tycho") listOf(t("Dive", "Tycho", id = "yt-dive")) else emptyList() },
            random = Random(2),
        )
        val mix = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 10, discovery = 1f), taste)
        assertTrue(mix.none { it.artist == "Bonobo" }, "better empty than a name-search stranger: $mix")
        assertTrue("Bonobo" !in queries)
    }

    // Lead's correlation rule: one list's outlier is not a discovery; a corroborated peer is.
    @Test
    fun `a related outlier named by one seed is not picked, a corroborated one is`() = runBlocking {
        val taste = tasteOf("Tycho", "Bonobo")
        val outlier = RelatedArtist("K-Pop Act", ProviderId.YOUTUBE_MUSIC, "yt-kpop", 9)
        val corroborated = RelatedArtist("Four Tet", ProviderId.YOUTUBE_MUSIC, "yt-four-tet", 3)
        val mixer = SmartMixer(
            search = { emptyList() },
            related = { name -> if (name == "Tycho") listOf(outlier, corroborated) else listOf(corroborated) },
            relatedSongs = { artist ->
                when (artist.id) {
                    "yt-kpop" -> listOf(t("Dynamite", "K-Pop Act", id = "yt-dynamite", provider = ProviderId.YOUTUBE_MUSIC))
                    "yt-four-tet" -> listOf(t("Baby", "Four Tet", id = "yt-baby", provider = ProviderId.YOUTUBE_MUSIC))
                    else -> emptyList()
                }
            },
            artistTop = { name ->
                when (name) {
                    "Tycho" -> listOf(t("Dive", "Tycho", id = "yt-dive"))
                    "Bonobo" -> listOf(t("Kerala", "Bonobo", id = "yt-kerala"))
                    else -> emptyList()
                }
            },
            random = Random(3),
        )
        val mix = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 20, discovery = 1f), taste)
        assertTrue(mix.none { it.artist == "K-Pop Act" }, "a rank-9 artist named by one seed of two is an outlier: $mix")
        assertTrue(mix.any { it.artist == "Four Tet" }, "a peer named by two seeds is a discovery: $mix")
    }

    // Lead's rule, single-seed case: there is nobody to corroborate, so only the top of the list counts.
    @Test
    fun `a single-seed mix takes only the top of its related list`() = runBlocking {
        val taste = tasteOf("Tycho")
        val near = RelatedArtist("Near Artist", ProviderId.YOUTUBE_MUSIC, "yt-near", 5)
        val far = RelatedArtist("Far Artist", ProviderId.YOUTUBE_MUSIC, "yt-far", 6)
        val mixer = SmartMixer(
            search = { emptyList() },
            related = { listOf(near, far) },
            relatedSongs = { artist ->
                when (artist.id) {
                    "yt-near" -> listOf(t("Near Song", "Near Artist", id = "yt-near-song", provider = ProviderId.YOUTUBE_MUSIC))
                    "yt-far" -> listOf(t("Far Song", "Far Artist", id = "yt-far-song", provider = ProviderId.YOUTUBE_MUSIC))
                    else -> emptyList()
                }
            },
            artistTop = { name -> if (name == "Tycho") listOf(t("Dive", "Tycho", id = "yt-dive")) else emptyList() },
            random = Random(4),
        )
        val mix = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 10, discovery = 1f), taste)
        assertTrue(mix.any { it.artist == "Near Artist" }, "rank 5 is accepted for a single-seed mix: $mix")
        assertTrue(mix.none { it.artist == "Far Artist" }, "rank 6 is not a peer of a one-artist mix: $mix")
    }

    // 3. A derivative/unknown-duration upload is offered only once that very upload was played here.
    @Test
    fun `slowed uploads, clips and unknown takes are blocked unless that upload was played`() = runBlocking {
        val clean = t("Dive", "Tycho", id = "yt-dive")
        val slowed = t("Dive (slowed + reverb)", "Tycho", id = "yt-slowed")
        val clip = t("Dive - Radio Edit", "Tycho", id = "yt-clip", durationMs = 20_000)
        val eightD = t("Dive 8D Audio", "Tycho", id = "yt-8d")
        val top = listOf(clean, slowed, clip, eightD)
        val fresh = tasteOf("Tycho")
        fun mixerOf(taste: Taste) = SmartMixer(
            search = { emptyList() },
            artistTop = { name -> if (name == "Tycho") top else emptyList() },
            random = Random(5),
        )
        val cleanMix = mixerOf(fresh).build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 20, discovery = 0f), fresh)
        assertEquals(setOf("yt-dive", "evidence-0"), cleanMix.map { it.id }.toSet(), "only the real recording and the person's own history: $cleanMix")

        val playedTaste = tasteOf(
            "Tycho",
            extra = listOf(slowed, clip, eightD).associate { track ->
                track.key to Listened(track.title, track.artist, provider = track.provider, ms = 30_000, plays = 1)
            },
        )
        val playedMix = mixerOf(playedTaste).build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 20, discovery = 0f), playedTaste)
        assertTrue(playedMix.map { it.id }.containsAll(listOf("yt-slowed", "yt-clip", "yt-8d")), "once played, the person's own uploads may be offered: $playedMix")
    }

    // 4. Cover-by-title: a re-upload whose title looks ordinary is blocked unless it was played.
    @Test
    fun `a cover whose title looks ordinary is blocked by title until played`() = runBlocking {
        val original = t("The Eyes of Ibad", "Panchiko", id = "panchiko-original")
        val cover = t("The Eyes of Ibad", "Sleepy Gary", id = "sleepy-cover")
        val extra = mapOf(original.key to Listened("The Eyes of Ibad", "Panchiko", provider = ProviderId.SPOTIFY, ms = 200_000, plays = 3))
        fun mixerOf(taste: Taste) = SmartMixer(
            search = { genre -> if (genre == "dream pop") listOf(cover) else emptyList() },
            artistTop = { name ->
                when (name) {
                    "Tycho" -> listOf(t("Dive", "Tycho", id = "yt-dive"))
                    "Sleepy Gary" -> listOf(cover)
                    else -> emptyList()
                }
            },
            random = Random(6),
        )
        val spec = SmartSpec(sources = setOf(SmartSource.Favourites, SmartSource.Genres), genres = listOf("dream pop"), size = 10, discovery = 1f)
        val unheard = mixerOf(tasteOf("Tycho", extra = extra)).build(spec, tasteOf("Tycho", extra = extra))
        assertTrue(unheard.none { it.title == "The Eyes of Ibad" }, "an unheard cover of a song heard here is blocked: $unheard")

        val playedTaste = tasteOf(
            "Tycho",
            extra = extra + (cover.key to Listened("The Eyes of Ibad", "Sleepy Gary", provider = ProviderId.SPOTIFY, ms = 90_000, plays = 1)),
        )
        val played = mixerOf(playedTaste).build(spec, playedTaste)
        assertTrue(played.any { it.id == "sleepy-cover" }, "the same upload once played here is the person's own: $played")
    }

    // 5. Discovery 0: nothing from outside the seeds and the artists the taste already chose.
    @Test
    fun `discovery 0 adds no artist outside the seeds`() = runBlocking {
        val taste = tasteOf("Tycho")
        val bonobo = RelatedArtist("Bonobo", ProviderId.YOUTUBE_MUSIC, "yt-bonobo", 0)
        val mixer = SmartMixer(
            search = { genre -> if (genre == "chill") listOf(t("Kerala", "Bonobo", id = "yt-kerala", provider = ProviderId.YOUTUBE_MUSIC)) else emptyList() },
            related = { listOf(bonobo) },
            relatedSongs = { listOf(t("Kerala", "Bonobo", id = "yt-kerala", provider = ProviderId.YOUTUBE_MUSIC)) },
            artistTop = { name ->
                when (name) {
                    "Tycho" -> (1..5).map { t("Tycho Song $it", "Tycho", id = "yt-tycho-$it") }
                    "Bonobo" -> listOf(t("Kerala", "Bonobo", id = "yt-kerala", provider = ProviderId.YOUTUBE_MUSIC))
                    else -> emptyList()
                }
            },
            random = Random(7),
        )
        val mix = mixer.build(
            SmartSpec(sources = setOf(SmartSource.Favourites, SmartSource.Genres), genres = listOf("chill"), size = 10, discovery = 0f),
            taste,
        )
        assertTrue(mix.isNotEmpty())
        assertTrue(
            mix.all { track -> Taste.creditsOf(track.artist).any { it == "tycho" } },
            "at discovery 0 only the person's own artists may play: $mix",
        )
    }

    // 6/7. Discovery 1: at most half the list is new artists, at most two songs each, and the familiar
    // floor holds. Enough songs for a full list are provided so the share means the finished list's.
    @Test
    fun `discovery 1 caps new artists at half the list and two songs each`() = runBlocking {
        val taste = tasteOf("Tycho")
        val peers = (1..4).map { i -> RelatedArtist("Peer $i", ProviderId.YOUTUBE_MUSIC, "yt-peer-$i", i) }
        val mixer = SmartMixer(
            search = { emptyList() },
            related = { peers },
            relatedSongs = { peer ->
                (1..5).map { i -> t("Peer Song $i", peer.name, id = "yt-${peer.id}-$i", provider = ProviderId.YOUTUBE_MUSIC) }
            },
            artistTop = { name -> if (name == "Tycho") (1..12).map { t("Tycho Song $it", "Tycho", id = "yt-tycho-$it") } else emptyList() },
            random = Random(8),
        )
        val mix = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 20, discovery = 1f), taste)
        val newSongs = mix.filter { it.artist != "Tycho" }
        assertTrue(newSongs.isNotEmpty(), "discovery 1 finds new artists: $mix")
        assertTrue(newSongs.size <= 10, "at most half the list is new artists at discovery 1: $mix")
        assertTrue(newSongs.groupingBy { it.artist }.eachCount().values.all { it <= 2 }, "at most two songs per new artist: ${newSongs.groupBy { it.artist }}")
        val familiar = mix.filter { it.artist == "Tycho" }
        assertTrue(familiar.size >= 20 / 4, "at least a quarter of the list stays familiar: $mix")
        assertTrue(mix.zipWithNext().none { (a, b) -> a.artist == b.artist }, "no artist twice in a row: $mix")
    }

    // 7b. The same quotas hold when [SmartMixer.pick] is called directly with a context that marks a
    // new artist: a related peer never takes more than two places, however many songs it has.
    @Test
    fun `pick keeps two songs per new artist`() {
        val taste = tasteOf("Tycho")
        val peers = (1..2).map { "Peer $it" }
        val candidates = (1..6).map { t("Tycho Song $it", "Tycho", id = "yt-tycho-$it") } +
            peers.flatMap { peer -> (1..5).map { i -> t("$peer Song $i", peer, id = "yt-$peer-$i", provider = ProviderId.YOUTUBE_MUSIC) } }
        val context = Context(
            origins = candidates.filter { it.artist != "Tycho" }.associate { rec(it) to Origin.RELATED },
            newArtists = peers.mapTo(HashSet(), Taste::artistKey),
            seedArtists = setOf("tycho"),
        )
        val mixer = SmartMixer(search = { emptyList() }, random = Random(9))
        val mix = mixer.pick(candidates, SmartSpec(size = 20, discovery = 1f), taste, context)
        val perNew = mix.filter { it.artist != "Tycho" }.groupingBy { it.artist }.eachCount()
        assertTrue(perNew.values.all { it <= 2 }, "two songs per new artist at most: $perNew")
        assertTrue(mix.count { it.artist == "Tycho" } <= 20 / 4, "the artist cap still holds: $mix")
    }

    // 9. Hidden uploads, explicit dislikes, resting artists and skipped-away songs never enter a mix;
    // a named artist survives the dislike gate because it was asked for on purpose.
    @Test
    fun `hidden, disliked, resting and skipped tracks never enter a mix`() = runBlocking {
        val hiddenTrack = t("Hidden", "Tycho", id = "yt-hidden")
        val skippedSong = t("Skipped Away", "Tycho", id = "yt-skipped")
        val restingSong = t("Meh", "Resting Band", id = "yt-meh")
        val log = evidenceLog(
            "Tycho",
            extra = mapOf(
                restingSong.key to Listened("Meh", "Resting Band", provider = ProviderId.SPOTIFY, ms = 20_000, plays = 4),
                skippedSong.key to Listened("Skipped Away", "Tycho", provider = ProviderId.SPOTIFY, ms = 20_000, plays = 3),
            ),
            skips = mapOf(restingSong.key to 4, skippedSong.key to 3),
        )
        val taste = Taste(log, listOf("Tycho"), emptyList(), dislikedArtists = listOf("Disliked Band"), hiddenTracks = listOf(hiddenTrack.key))
        assertTrue(taste.restingArtist("Resting Band"))
        val mixer = SmartMixer(
            search = { emptyList() },
            related = {
                listOf(
                    RelatedArtist("Disliked Band", ProviderId.YOUTUBE_MUSIC, "yt-disliked", 0),
                    RelatedArtist("Resting Band", ProviderId.YOUTUBE_MUSIC, "yt-resting", 0),
                    RelatedArtist("New Peer", ProviderId.YOUTUBE_MUSIC, "yt-peer", 0),
                )
            },
            relatedSongs = { artist ->
                when (artist.id) {
                    "yt-disliked" -> listOf(t("Disliked Song", "Disliked Band", id = "yt-disliked-song", provider = ProviderId.YOUTUBE_MUSIC))
                    "yt-resting" -> listOf(t("Resting Song", "Resting Band", id = "yt-resting-song", provider = ProviderId.YOUTUBE_MUSIC))
                    "yt-peer" -> listOf(t("Peer Song", "New Peer", id = "yt-peer-song", provider = ProviderId.YOUTUBE_MUSIC))
                    else -> emptyList()
                }
            },
            artistTop = { name ->
                when (name) {
                    "Tycho" -> listOf(t("Normal", "Tycho", id = "yt-normal"), hiddenTrack, skippedSong)
                    "Disliked Band" -> listOf(t("Good Song", "Disliked Band", id = "yt-good"))
                    else -> emptyList()
                }
            },
            random = Random(10),
        )
        val mix = mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 10, discovery = 1f), taste)
        assertTrue(mix.any { it.id == "yt-normal" })
        assertTrue(mix.none { it.id == "yt-hidden" }, "a hidden upload is never offered: $mix")
        assertTrue(mix.none { it.id == "yt-skipped" }, "a skipped-away song is not offered: $mix")
        assertTrue(mix.none { it.artist == "Disliked Band" }, "a disliked artist's discovery is dropped: $mix")
        assertTrue(mix.none { it.artist == "Resting Band" }, "a resting artist is not discovered: $mix")

        // Named on purpose: the artist mix the person asked for keeps the disliked artist.
        val namedMix = mixer.build(
            SmartSpec(sources = setOf(SmartSource.Artists), artists = listOf("Disliked Band"), size = 10, discovery = 0f),
            taste,
        )
        assertTrue(namedMix.any { it.id == "yt-good" }, "a named artist survives the dislike gate: $namedMix")
    }

    // 10. One copy per recording, and artists are spread apart.
    @Test
    fun `one copy per recording, and artists are spread apart`() = runBlocking {
        val taste = tasteOf("A", "B")
        val candidates = listOf("A", "B", "C", "D").flatMap { artist ->
            (1..4).map { i -> t("Song $i by $artist", artist, id = "$artist-$i") }
        } + listOf(t("Song 1 by A", "A", id = "a-other-copy", provider = ProviderId.SOUNDCLOUD))
        val mixer = SmartMixer(search = { emptyList() }, random = Random(11))
        val picked = mixer.pick(candidates, SmartSpec(size = 12, discovery = 0f), taste)
        assertTrue(picked.isNotEmpty())
        assertEquals(picked.size, picked.distinctBy { rec(it) }.size, "one copy per recording: $picked")

        // A build returns a spread list: the seeds' songs alternate artists.
        val topByArtist = mapOf(
            "A" to (1..4).map { i -> t("Song $i by A", "A", id = "A-$i") },
            "B" to (1..4).map { i -> t("Song $i by B", "B", id = "B-$i") },
        )
        val spender = SmartMixer(
            search = { emptyList() },
            artistTop = { name -> topByArtist[name].orEmpty() + if (name == "A") listOf(t("Song 1 by A", "A", id = "A-copy", provider = ProviderId.SOUNDCLOUD)) else emptyList() },
            random = Random(11),
        )
        val mix = spender.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 6, discovery = 0f), taste)
        assertTrue(mix.isNotEmpty())
        assertEquals(mix.size, mix.distinctBy { rec(it) }.size, "one copy per recording: $mix")
        assertTrue(mix.zipWithNext().none { (a, b) -> a.artist == b.artist }, "no artist twice in a row: $mix")
    }

    // 11. The served history is left out when enough candidates remain, and never empties a list.
    @Test
    fun `the avoid set is respected and never empties a mix`() = runBlocking {
        val taste = tasteOf("Tycho")
        val top = (1..10).map { t("Tycho Song $it", "Tycho", id = "yt-$it") }
        fun mixer() = SmartMixer(
            search = { emptyList() },
            artistTop = { name -> if (name == "Tycho") top else emptyList() },
            random = Random(12),
        )
        val spec = SmartSpec(sources = setOf(SmartSource.Favourites), size = 4, discovery = 0f)
        val first = mixer().build(spec, taste)
        val served = first.map { it.key }.toSet()
        assertTrue(first.isNotEmpty())
        val again = mixer().build(spec, taste, avoid = served)
        assertTrue(again.isNotEmpty())
        assertTrue(again.none { it.key in served }, "what Home already showed is left out: $again")
        // Only avoided songs are left: history alone must not empty the list.
        val starved = mixer().build(spec, taste, avoid = top.map { it.key }.toSet())
        assertTrue(starved.isNotEmpty(), "the avoid set can never empty a mix: $starved")
    }

    // Design's genre gate: a one-off search hit by a stranger is dropped; a catalogue member is kept.
    @Test
    fun `a genre's one-off stranger is dropped, a catalogue member is kept`() = runBlocking {
        val taste = tasteOf("Tycho")
        val stranger = t("Random Upload", "Somebody", id = "yt-random")
        val verified = t("Verified Song", "Verified Artist", id = "yt-verified")
        val mixer = SmartMixer(
            search = { genre -> if (genre == "chill") listOf(stranger, verified) else emptyList() },
            artistTop = { name ->
                when (name) {
                    "Tycho" -> listOf(t("Dive", "Tycho", id = "yt-dive"))
                    "Verified Artist" -> listOf(verified)
                    else -> emptyList()
                }
            },
            random = Random(13),
        )
        val mix = mixer.build(
            SmartSpec(sources = setOf(SmartSource.Favourites, SmartSource.Genres), genres = listOf("chill"), size = 10, discovery = 1f),
            taste,
        )
        assertTrue(mix.any { it.id == "yt-verified" }, "a recording its artist's page confirms is kept: $mix")
        assertTrue(mix.none { it.id == "yt-random" }, "a one-off stranger never rides a genre in: $mix")
    }

    // Follows and genres are still seed sources, and an old-style construction with only search works.
    @Test
    fun `follows and genres become seeds`() = runBlocking {
        val asked = mutableListOf<String>()
        val mixer = SmartMixer(search = { q -> asked += q; emptyList() }, follows = { listOf("Followed") })
        val fresh = Taste(ListeningLog(), emptyList(), emptyList())
        mixer.build(SmartSpec(sources = setOf(SmartSource.SpotifyFollows, SmartSource.Genres), genres = listOf("lo-fi")), fresh)
        assertEquals(listOf("Followed", "lo-fi"), asked)
    }

    @Test
    fun `no callback answering is not a crash, and a failing listener is not a failure`() = runBlocking {
        val fresh = Taste(ListeningLog(), listOf("Tycho"), emptyList())
        val spec = SmartSpec(sources = setOf(SmartSource.Favourites, SmartSource.MostPlayed), size = 10)
        assertTrue(SmartMixer(search = { emptyList() }).build(spec, fresh).isEmpty(), "nothing to offer is an empty list")

        val broken = SmartMixer(
            search = { throw IllegalStateException("search down") },
            follows = { throw IllegalStateException("follows down") },
            related = { throw IllegalStateException("related down") },
            relatedSongs = { throw IllegalStateException("artist page down") },
            artistTop = { throw IllegalStateException("artist page down") },
        )
        assertTrue(broken.build(spec.copy(sources = setOf(SmartSource.Favourites, SmartSource.SpotifyFollows)), fresh).isEmpty())

        val working = SmartMixer(
            search = { emptyList() },
            artistTop = { name -> if (name == "Tycho") listOf(t("Dive", "Tycho", id = "yt-dive")) else emptyList() },
            random = Random(14),
        )
        val made = working.build(spec.copy(size = 5), tasteOf("Tycho"), onProgress = { error("progress exploded") })
        assertTrue(made.isNotEmpty(), "a progress listener that fails must not fail the mix: $made")
    }

    @Test
    fun `the same random seed and the same answers make the same list`() = runBlocking {
        fun mixer() = SmartMixer(
            search = { q -> listOf(t("Search for $q", "Tycho", id = "yt-$q")) },
            related = { listOf(RelatedArtist("Peer", ProviderId.YOUTUBE_MUSIC, "yt-peer", 2)) },
            relatedSongs = { listOf(t("Peer Song", "Peer", id = "yt-peer-song", provider = ProviderId.YOUTUBE_MUSIC)) },
            artistTop = { name -> if (name == "Tycho") (1..6).map { i -> t("Tycho Song $i", "Tycho", id = "yt-tycho-$i") } else emptyList() },
            random = Random(42),
        )
        val spec = SmartSpec(sources = setOf(SmartSource.Favourites), size = 12, discovery = 1f)
        assertEquals(mixer().build(spec, tasteOf("Tycho")), mixer().build(spec, tasteOf("Tycho")))
    }

    @Test
    fun `on progress is reported from the start to the end`() = runBlocking {
        val progress = mutableListOf<Float>()
        val mixer = SmartMixer(
            search = { emptyList() },
            artistTop = { name -> if (name == "Tycho") listOf(t("Dive", "Tycho", id = "yt-dive")) else emptyList() },
            random = Random(15),
        )
        mixer.build(SmartSpec(sources = setOf(SmartSource.Favourites), size = 5, discovery = 0f), tasteOf("Tycho"), onProgress = { progress += it })
        assertTrue(progress.isNotEmpty())
        assertEquals(0f, progress.first())
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }, "progress never goes back: $progress")
    }

    @Test
    fun `a name seen twice in one genre search is not a discovered artist`() = runBlocking {
        // A compilation channel (or a tag coincidence) is not a new artist: a stranger is accepted
        // only when the artist page backs the recording, never from repetition alone.
        val one = t("Study 1", "Lofi Girl", id = "yt-1")
        val two = t("Study 2", "Lofi Girl", id = "yt-2")
        val mixer = SmartMixer(
            search = { listOf(one, two) },
            artistTop = { emptyList() },
            random = Random(3),
        )
        val mix = mixer.build(SmartSpec(sources = setOf(SmartSource.Genres), genres = listOf("lofi"), size = 10, discovery = 1f), tasteOf("Tycho"))
        assertTrue(mix.none { it.artist == "Lofi Girl" }, "a channel credit is never a discovery: $mix")

        // The same name with its own page that carries the recording is a real artist and is kept.
        val member = t("Real Song", "Real Artist", id = "yt-real")
        val confirmed = SmartMixer(
            search = { listOf(member) },
            artistTop = { name -> if (name == "Real Artist") listOf(member.copy(id = "yt-page")) else emptyList() },
            random = Random(3),
        )
        assertTrue(
            confirmed.build(SmartSpec(sources = setOf(SmartSource.Genres), genres = listOf("lofi"), size = 10, discovery = 1f), tasteOf("Tycho"))
                .any { it.artist == "Real Artist" },
            "a page-confirmed genre artist is kept",
        )
    }

    @Test
    fun `an explicitly disliked act does not ride in as a feature, unless it is named`() = runBlocking {
        val feature = t("Song", "Tycho, Disliked One", id = "yt-feature")
        val mixer = SmartMixer(
            search = { emptyList() },
            artistTop = { name -> if (name == "Tycho") listOf(feature) else emptyList() },
            random = Random(4),
        )
        val taste = tasteOf("Tycho", disliked = listOf("Disliked One"))
        val notNamed = mixer.build(SmartSpec(sources = setOf(SmartSource.Artists), artists = listOf("Tycho"), size = 10, discovery = 0f), taste)
        assertTrue(notNamed.none { it.id == "yt-feature" }, "a feature of a disliked act is left out: $notNamed")

        val ownSong = t("Their Song", "Disliked One", id = "yt-own")
        val naming = SmartMixer(
            search = { emptyList() },
            artistTop = { name -> if (name == "Disliked One") listOf(ownSong) else emptyList() },
            random = Random(4),
        )
        val named = naming.build(SmartSpec(sources = setOf(SmartSource.Artists), artists = listOf("Disliked One"), size = 10, discovery = 0f), taste)
        assertTrue(named.any { it.id == "yt-own" }, "naming the artist is the explicit override: $named")
    }

    @Test
    fun `spread never loses a track and keeps artists apart`() {
        val mixer = SmartMixer(search = { emptyList() }, random = Random(1))
        val a1 = t("A1", "A", id = "a1")
        val a2 = t("A2", "A", id = "a2")
        val b1 = t("B1", "B", id = "b1")
        val b2 = t("B2", "B", id = "b2")
        val spread = mixer.spread(listOf(a1, a2, b1, b2))
        assertEquals(4, spread.size)
        assertEquals(4, spread.map { it.key }.toSet().size, "nothing lost or duplicated: $spread")
        assertTrue(spread.zipWithNext().none { (x, y) -> x.artist == y.artist }, "no artist twice in a row: $spread")
        assertEquals(listOf(a1), mixer.spread(listOf(a1)), "a one-song list comes back as it is")
    }
}
