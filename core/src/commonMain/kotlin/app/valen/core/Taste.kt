/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** What a smart playlist is made from. */
@Serializable
enum class SmartSource { Favourites, MostPlayed, SpotifyFollows, Artists, Genres }

/** How a smart playlist is made; kept with it, so it can be made again later. */
@Serializable
data class SmartSpec(
    val sources: Set<SmartSource> = setOf(SmartSource.Favourites, SmartSource.MostPlayed),
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val size: Int = 40,
    /** 0 keeps to songs already heard, 1 goes looking for new ones. */
    val discovery: Float = 0.5f,
)

/** A service that can say which artists the account follows. */
interface ArtistFollows {
    suspend fun followedArtists(): List<String>
}

/**
 * The person's taste, read from what they told Valen (favourite artists and songs) and what they
 * did (time listened, plays, songs skipped early). Scores are relative: only their order matters.
 *
 * An artist is known by each name a song credits ("A, B", "A feat. B": both), as [artistKey] says
 * it, so a collaboration counts for each and "Daft Punk - Topic" is Daft Punk. Recent weeks weigh as
 * much as all the time before them ([ListeningLog.daily], halving every [RECENT_HALF_DAYS] days), so
 * the taste follows what is played now. A song is heard by recording, whichever service played it.
 */
class Taste(
    private val log: ListeningLog,
    favouriteArtists: List<String>,
    private val favouriteSongs: List<Track>,
) {
    private val favourites = (favouriteArtists + favouriteSongs.map { it.artist }).flatMap(::creditsOf).toSet()
    private val favouriteSongKeys = favouriteSongs.map { recordingKey(it.title, it.artist) }.toSet()
    private val total = log.totalMs.coerceAtLeast(1).toFloat()

    /** All-time listening by credited artist. */
    private val artistMs: Map<String, Long> = HashMap<String, Long>().also { out ->
        log.artists.forEach { (line, ms) -> creditsOf(line).forEach { out[it] = (out[it] ?: 0L) + ms } }
    }

    /** Recent listening by credited artist, each day weighed by how long ago it was. */
    private val recentMs: Map<String, Double>
    private val recentTotal: Double

    init {
        val newest = log.daily.keys.mapNotNull { it.toLongOrNull() }.maxOrNull()
        val out = HashMap<String, Double>()
        var sum = 0.0
        if (newest != null) {
            for ((day, listening) in log.daily) {
                val age = newest - (day.toLongOrNull() ?: continue)
                val weight = 0.5.pow(age / RECENT_HALF_DAYS)
                for ((songKey, ms) in listening.songs) {
                    val artist = log.songs[songKey]?.artist ?: continue
                    val w = ms * weight
                    creditsOf(artist).forEach { out[it] = (out[it] ?: 0.0) + w }
                    sum += w
                }
            }
        }
        recentMs = out
        recentTotal = sum
    }

    /** Recording key of each song key in the log. */
    private val recordingOf: Map<String, String> = log.songs.mapValues { (_, s) -> recordingKey(s.title, s.artist) }
    private val recordingMs: Map<String, Long> = HashMap<String, Long>().also { out ->
        log.songs.forEach { (key, s) -> recordingOf[key]?.let { out[it] = (out[it] ?: 0L) + s.ms } }
    }
    private val recordingSkips: Map<String, Int> = HashMap<String, Int>().also { out ->
        log.skips.forEach { (key, n) -> (recordingOf[key] ?: key).let { out[it] = (out[it] ?: 0) + n } }
    }
    private val artistPlays: Map<String, Int> = HashMap<String, Int>().also { out ->
        log.songs.values.forEach { s -> creditsOf(s.artist).forEach { out[it] = (out[it] ?: 0) + s.plays } }
    }
    private val artistSkips: Map<String, Int> = HashMap<String, Int>().also { out ->
        log.skips.forEach { (key, n) -> log.songs[key]?.let { s -> creditsOf(s.artist).forEach { out[it] = (out[it] ?: 0) + n } } }
    }

    /** One credited artist: told favourite, share of the listening (recent and all-time), less skipping. */
    private fun creditScore(key: String): Float {
        val allTime = (artistMs[key] ?: 0L) / total
        val share = if (recentTotal > 0) 0.5f * allTime + 0.5f * ((recentMs[key] ?: 0.0) / recentTotal).toFloat() else allTime
        return (if (key in favourites) 3f else 0f) + share * 8f - skipPenalty(key)
    }

    /** Skipping counts as a rate: a few skips of an artist played a hundred times say little. */
    private fun skipPenalty(key: String): Float {
        val skips = artistSkips[key] ?: return 0f
        val rate = skips.toFloat() / maxOf(artistPlays[key] ?: 0, skips, 1)
        return if (skips < 2) skips * 0.25f else rate * 3f
    }

    /** How much this person likes [artist] (an artist line: its best-liked credit counts). */
    fun artistScore(artist: String): Float = creditsOf(artist).maxOfOrNull(::creditScore) ?: 0f

    /** An artist this person keeps skipping and never named: left out of what is made for them. */
    fun dislikes(artist: String): Boolean = creditsOf(artist).let { credits ->
        credits.isNotEmpty() && credits.all { key ->
            val skips = artistSkips[key] ?: 0
            key !in favourites && skips >= 3 && skips.toFloat() / maxOf(artistPlays[key] ?: 0, skips, 1) >= 0.6f
        }
    }

    /** How much this person likes [track]. */
    fun songScore(track: Track): Float = artistScore(track.artist) + loved(track) - skips(track) * 0.6f

    /**
     * This very upload has been played here. A derivative's name often reads as the song's own
     * recording ("Awake (1 hour loop)" is "Awake"), so only the upload itself makes it the person's.
     */
    fun played(track: Track): Boolean = log.songs.containsKey(track.key)

    /** Whether this recording has been played here, from any service. */
    fun heard(track: Track): Boolean = log.songs.containsKey(track.key) || recordingKey(track.title, track.artist) in recordingMs

    /** How much [track] itself is loved, apart from its artist: time with it, and being a told favourite. */
    fun loved(track: Track): Float {
        val rec = recordingKey(track.title, track.artist)
        val own = (recordingMs[rec] ?: 0L) / total * 6f
        val favourite = if (rec in favouriteSongKeys) 2.5f else 0f
        return own + favourite
    }

    fun skips(track: Track): Int = recordingSkips[recordingKey(track.title, track.artist)] ?: (log.skips[track.key] ?: 0)

    /** The artists this person likes most, told and listened, best first (never one they skip away from). */
    fun topArtists(n: Int): List<String> {
        val names = linkedMapOf<String, String>()
        (givenNames + log.artists.keys.flatMap(::artistsIn)).forEach { name ->
            creditsOf(name).firstOrNull()?.let { names.putIfAbsent(it, cleanName(name)) }
        }
        return names.values.filter { !dislikes(it) }.sortedByDescending { artistScore(it) }.take(n)
    }

    private val givenNames = (favouriteArtists + favouriteSongs.map { it.artist }).flatMap(::artistsIn)

    /** The songs played here this person comes back to most, by time and plays, less skips. */
    fun onRepeat(n: Int): List<Track> = log.songs.entries
        .filter { (log.skips[it.key] ?: 0) < it.value.plays }
        .sortedByDescending { it.value.ms + it.value.plays * 30_000L }
        .distinctBy { recordingOf[it.key] }
        .take(n)
        .map { (key, song) -> song.toTrack(key) }

    val favouriteTracks: List<Track> get() = favouriteSongs

    companion object {
        /** One artist's key, the same as artist pages use ([app.valen.core.artistKey]). */
        fun artistKey(name: String): String = creditsOf(name).firstOrNull() ?: app.valen.core.artistKey(name)

        /** Every artist an artist line credits, as keys: "A feat. B" → a, b; "Daft Punk - Topic" → daft punk. */
        fun creditsOf(line: String): List<String> =
            artistsIn(line).map { app.valen.core.artistKey(cleanName(it)) }.filter { it.isNotEmpty() }.distinct()

        /** A channel's decorations taken off an artist's name. */
        fun cleanName(name: String): String = name.trim()
            .replace(Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?<=\p{L})vevo$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(official|music)$""", RegexOption.IGNORE_CASE), "")
            .trim()

        /** Days for recent listening to count half as much. */
        const val RECENT_HALF_DAYS = 14.0
    }
}

/** A song from the log back as something to play. Its key is "provider:id". */
fun Listened.toTrack(key: String): Track = Track(
    provider = provider,
    id = key.substringAfter(':'),
    title = title,
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    durationMs = durationMs,
    uri = uri,
    link = link,
)

/**
 * Makes song lists from a taste: Home's "For you", artist mixes, a radio and smart playlists.
 *
 * Candidates come from searching the services for the seeds, and a song counts for an artist seed
 * only when it credits that very artist: a name that merely contains the seed ("Drake Bell" for
 * Drake, "Anastasia" for Sia) and someone else's upload were the "completely random songs". New
 * artists come from the ones the services call related to the seeds ([related]), never from
 * whatever a search returned. Derivatives (covers, karaoke, nightcore, slowed, 8D, hour-long loops)
 * are not offered unless already heard here. Each is scored by the taste, an artist's best-known
 * songs ahead of their rarities, with a little shuffle so the list changes; then the same recording
 * is kept once, no artist takes more than a quarter, and none plays twice in a row.
 */
class SmartMixer(
    private val search: suspend (String) -> List<Track>,
    private val follows: suspend () -> List<String> = { emptyList() },
    /** Artists the services call related to an artist (by name), best first. */
    private val related: suspend (String) -> List<String> = { emptyList() },
    private val random: Random = Random.Default,
) {
    /** The artists and genre words a [spec] asks for, artists first. */
    suspend fun seeds(spec: SmartSpec, taste: Taste): Pair<List<String>, List<String>> {
        val artists = buildList {
            if (SmartSource.Favourites in spec.sources) {
                addAll(taste.topArtists(12).filter { taste.artistScore(it) >= 3f })
                addAll(taste.favouriteTracks.map { it.artist }.filter { !taste.dislikes(it) })
            }
            if (SmartSource.MostPlayed in spec.sources) addAll(taste.topArtists(8))
            if (SmartSource.SpotifyFollows in spec.sources) addAll(runCatching { follows() }.getOrDefault(emptyList()).take(15))
            // Named on purpose: kept even when skipped often.
            if (SmartSource.Artists in spec.sources) addAll(spec.artists)
        }.distinctBy(Taste::artistKey)
        val genres = if (SmartSource.Genres in spec.sources) spec.genres.distinct() else emptyList()
        return artists to genres
    }

    /** What the search found besides the songs: how known each song is, and which artists are new. */
    class Context(
        /** By recording key: 1 for an artist's first search answer, falling to 0 by the twelfth. */
        val known: Map<String, Float> = emptyMap(),
        /** Related artists by key, with the weight they carry in (they are not in the taste yet). */
        val relatedArtists: Map<String, Float> = emptyMap(),
        /** The seed artists' keys: their songs are trusted as the person's own choice. */
        val seedArtists: Set<String> = emptySet(),
    )

    suspend fun build(spec: SmartSpec, taste: Taste, onProgress: (Float) -> Unit = {}): List<Track> {
        val (artists, genres) = seeds(spec, taste)
        val seedArtists = artists.take(MAX_ARTIST_SEEDS)
        val d = spec.discovery.coerceIn(0f, 1f)
        // Related artists, for the new: more of them the more discovery is asked for.
        val newArtists = LinkedHashMap<String, String>()
        val relatedCount = if (seedArtists.isEmpty()) 0 else (MAX_RELATED * d).roundToInt()
        if (relatedCount > 0) {
            val seedKeys = seedArtists.map(Taste::artistKey).toSet()
            for (seed in seedArtists.take(RELATED_FROM)) {
                runCatching { related(seed) }.getOrDefault(emptyList()).asSequence()
                    .filter { name -> Taste.artistKey(name).let { it.isNotEmpty() && it !in seedKeys && it !in newArtists } }
                    .filter { !taste.dislikes(it) }
                    .take(PER_SEED_RELATED)
                    .forEach { newArtists[Taste.artistKey(it)] = it }
                if (newArtists.size >= relatedCount) break
            }
        }
        val relatedNames = newArtists.values.take(relatedCount)
        val queries = seedArtists.map { it to true } + relatedNames.map { it to true } + genres.take(8).map { it to false }
        if (queries.isEmpty()) return emptyList()
        val found = mutableListOf<Track>()
        val known = HashMap<String, Float>()
        queries.forEachIndexed { i, (query, isArtist) ->
            val tracks = runCatching { search(query) }.getOrDefault(emptyList())
            val kept = if (isArtist) {
                val key = Taste.artistKey(query)
                tracks.filter { key in Taste.creditsOf(it.artist) && !derivative(it, taste) }
                    .take(if (query in relatedNames) PER_RELATED_SONGS else PER_SEED_SONGS)
            } else {
                tracks.filter { !derivative(it, taste) }.take(PER_SEED_SONGS)
            }
            kept.forEachIndexed { pos, t ->
                known.putIfAbsent(recordingKey(t.title, t.artist), 1f - pos / PER_SEED_SONGS.toFloat())
            }
            found += kept
            onProgress((i + 1f) / queries.size)
        }
        // Songs already loved that fit the seeds, so a mix is never only strangers.
        val wanted = seedArtists.map(Taste::artistKey).toSet()
        fun fits(t: Track) = wanted.isEmpty() || Taste.creditsOf(t.artist).any { it in wanted }
        found += taste.onRepeat(40).filter(::fits)
        found += taste.favouriteTracks.filter(::fits)
        val context = Context(
            known = known,
            relatedArtists = relatedNames.associate { Taste.artistKey(it) to RELATED_WEIGHT },
            seedArtists = wanted,
        )
        return pick(found, spec, taste, context)
    }

    /** Scores, dedupes and orders [candidates] into at most [SmartSpec.size] songs. */
    fun pick(candidates: List<Track>, spec: SmartSpec, taste: Taste, context: Context = Context()): List<Track> {
        val named = spec.artists.map(Taste::artistKey).toSet()
        val unique = candidates.distinctBy { recordingKey(it.title, it.artist) }
            .filter { t -> !derivative(t, taste) && (!taste.dislikes(t.artist) || Taste.creditsOf(t.artist).any { it in named }) }
        // Discovery is a dial between "songs you love" and "songs you have not heard here": at 0 a
        // song's own love counts fully and the familiar get a lift; at 1 its own love is set aside
        // and the unheard get the lift. The lift is trusted only as far as the artist is: a seed or
        // a liked artist fully, a related one mostly, a stranger (a genre's find) little.
        val d = spec.discovery.coerceIn(0f, 1f)
        val scored = unique.map { track ->
            val credits = Taste.creditsOf(track.artist)
            val liked = taste.artistScore(track.artist)
            val relatedWeight = credits.maxOfOrNull { context.relatedArtists[it] ?: 0f } ?: 0f
            val trust = when {
                credits.any { it in context.seedArtists } || liked >= 1f -> 1f
                relatedWeight > 0f -> 0.75f
                else -> 0.35f
            }
            val fresh = !taste.heard(track)
            val lift = (if (fresh) d else 1f - d) * 3f * trust
            val known = context.known[recordingKey(track.title, track.artist)] ?: 0f
            val score = maxOf(liked, relatedWeight) + taste.loved(track) * (1f - d) + lift + known * 0.8f -
                taste.skips(track) * 0.6f - (if (fresh && VARIANT.containsMatchIn(track.title.lowercase())) 1f else 0f) +
                random.nextFloat() * 0.5f
            track to score
        }.sortedByDescending { it.second }
        val size = spec.size.coerceIn(1, 200)
        val cap = (size / 4).coerceAtLeast(3)
        val perArtist = mutableMapOf<String, Int>()
        val chosen = scored.mapNotNull { (track, _) ->
            val key = Taste.artistKey(track.artist)
            val n = perArtist[key] ?: 0
            if (n >= cap) null else track.also { perArtist[key] = n + 1 }
        }.take(size).toMutableList()
        // With only a few artists the cap would leave the list short: the best of the rest fill it.
        if (chosen.size < size) chosen += scored.map { it.first }.filter { it !in chosen }.take(size - chosen.size)
        return spread(chosen)
    }

    /** The same songs, reordered so no artist plays twice in a row where that can be helped. */
    fun spread(tracks: List<Track>): List<Track> {
        val left = tracks.toMutableList()
        val out = ArrayList<Track>(tracks.size)
        while (left.isNotEmpty()) {
            val last = out.lastOrNull()?.let { Taste.artistKey(it.artist) }
            val i = left.indexOfFirst { Taste.artistKey(it.artist) != last }.takeIf { it >= 0 } ?: 0
            out += left.removeAt(i)
        }
        return out
    }

    companion object {
        private const val MAX_ARTIST_SEEDS = 14
        private const val PER_SEED_SONGS = 12
        private const val PER_RELATED_SONGS = 5
        /** Related artists asked for at most (at full discovery), from the first [RELATED_FROM] seeds. */
        private const val MAX_RELATED = 8
        private const val RELATED_FROM = 4
        private const val PER_SEED_RELATED = 3
        /** What a related artist starts with in place of a taste score (a told favourite is 3). */
        private const val RELATED_WEIGHT = 1.5f

        /** An upload that is not the song as released: offered only once heard here. */
        private val DERIVATIVE = Regex(
            """\b(cover|covered by|karaoke|nightcore|slowed|sped ?up|speed ?up|8d|type beat|reaction|tutorial|""" +
                """lesson|backing track|1 hour|10 hours?|hour loop|full album|mashup)\b""",
        )
        /** A different take of a song: fine, but behind the song itself when both are new. */
        private val VARIANT = Regex("""\b(remix|live|acoustic|demo|edit|version|instrumental|extended)\b""")

        /** Too short or too long to be a song (a clip, a mix), or a derivative upload — unless heard. */
        fun derivative(track: Track, taste: Taste): Boolean {
            if (taste.played(track)) return false
            val title = track.title.lowercase()
            if (DERIVATIVE.containsMatchIn(title)) return true
            val ms = track.durationMs
            return ms in 1 until 60_000 || ms > 12 * 60_000
        }
    }
}
