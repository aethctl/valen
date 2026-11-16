/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.Serializable
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
 */
class Taste(
    private val log: ListeningLog,
    favouriteArtists: List<String>,
    private val favouriteSongs: List<Track>,
) {
    private val favourites = (favouriteArtists + favouriteSongs.map { it.artist }).map(::artistKey).toSet()
    private val favouriteSongKeys = favouriteSongs.map { recordingKey(it.title, it.artist) }.toSet()
    private val total = log.totalMs.coerceAtLeast(1).toFloat()
    private val artistMs = log.artists.mapKeys { artistKey(it.key) }
    private val artistSkips = log.songs.entries
        .groupBy { artistKey(it.value.artist) }
        .mapValues { (_, songs) -> songs.sumOf { log.skips[it.key] ?: 0 } }

    /** How much this person likes [artist]: told favourites first, then listening, less skips. */
    fun artistScore(artist: String): Float {
        val key = artistKey(artist)
        val listened = (artistMs[key] ?: 0L) / total
        val skips = artistSkips[key] ?: 0
        return (if (key in favourites) 3f else 0f) + listened * 8f - skips * 0.25f
    }

    /** How much this person likes [track]; [heard] is whether it has been played here. */
    fun songScore(track: Track): Float {
        val listened = log.songs[track.key]
        val own = (listened?.ms ?: 0L) / total * 6f
        val skips = log.skips[track.key] ?: 0
        val favourite = if (recordingKey(track.title, track.artist) in favouriteSongKeys) 2.5f else 0f
        return artistScore(track.artist) + own + favourite - skips * 0.6f
    }

    fun heard(track: Track): Boolean = log.songs.containsKey(track.key)

    /** How much [track] itself is loved, apart from its artist: time with it, and being a told favourite. */
    fun loved(track: Track): Float {
        val listened = log.songs[track.key]
        val own = (listened?.ms ?: 0L) / total * 6f
        val favourite = if (recordingKey(track.title, track.artist) in favouriteSongKeys) 2.5f else 0f
        return own + favourite
    }

    fun skips(track: Track): Int = log.skips[track.key] ?: 0

    /** The artists this person likes most, told and listened, best first. */
    fun topArtists(n: Int): List<String> {
        val names = linkedMapOf<String, String>()
        (favourites.mapNotNull { key -> nameFor(key) } + log.artists.keys).forEach { names.putIfAbsent(artistKey(it), it) }
        return names.values.sortedByDescending { artistScore(it) }.take(n)
    }

    private val givenNames = (favouriteArtists + favouriteSongs.map { it.artist })
    private fun nameFor(key: String): String? = givenNames.firstOrNull { artistKey(it) == key }

    /** The songs played here this person comes back to most, by time and plays, less skips. */
    fun onRepeat(n: Int): List<Track> = log.songs.entries
        .filter { (log.skips[it.key] ?: 0) < it.value.plays }
        .sortedByDescending { it.value.ms + it.value.plays * 30_000L }
        .take(n)
        .map { (key, song) -> song.toTrack(key) }

    val favouriteTracks: List<Track> get() = favouriteSongs

    companion object {
        fun artistKey(name: String): String = name.trim().lowercase()
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
)

/**
 * Makes song lists from a taste: Home's "For you", and smart playlists. Candidates come from
 * searching the services for the seeds (artists or genres), mixed with songs already loved;
 * each is scored by the taste, with a little daily shuffle so the list changes, then the same
 * recording is kept once and no artist plays twice in a row.
 */
class SmartMixer(
    private val search: suspend (String) -> List<Track>,
    private val follows: suspend () -> List<String> = { emptyList() },
    private val random: Random = Random.Default,
) {
    /** The artists and genre words a [spec] asks for, artists first. */
    suspend fun seeds(spec: SmartSpec, taste: Taste): Pair<List<String>, List<String>> {
        val artists = buildList {
            if (SmartSource.Favourites in spec.sources) {
                addAll(taste.topArtists(12).filter { taste.artistScore(it) >= 3f })
                addAll(taste.favouriteTracks.map { it.artist })
            }
            if (SmartSource.MostPlayed in spec.sources) addAll(taste.topArtists(8))
            if (SmartSource.SpotifyFollows in spec.sources) addAll(runCatching { follows() }.getOrDefault(emptyList()).take(15))
            if (SmartSource.Artists in spec.sources) addAll(spec.artists)
        }.distinctBy(Taste::artistKey)
        val genres = if (SmartSource.Genres in spec.sources) spec.genres.distinct() else emptyList()
        return artists to genres
    }

    suspend fun build(spec: SmartSpec, taste: Taste, onProgress: (Float) -> Unit = {}): List<Track> {
        val (artists, genres) = seeds(spec, taste)
        val queries = (artists.take(14) + genres.take(8))
        if (queries.isEmpty()) return emptyList()
        val wanted = artists.map(Taste::artistKey).toSet()
        val found = mutableListOf<Track>()
        queries.forEachIndexed { i, query ->
            val tracks = runCatching { search(query) }.getOrDefault(emptyList())
            // For an artist seed, only that artist's own songs; a genre takes what it finds.
            found += if (i < artists.size.coerceAtMost(14)) {
                val key = Taste.artistKey(query)
                tracks.filter { Taste.artistKey(it.artist).contains(key) }.take(12)
            } else {
                tracks.take(12)
            }
            onProgress((i + 1f) / queries.size)
        }
        // Songs already loved that fit the seeds, so a mix is never only strangers.
        found += taste.onRepeat(40).filter { wanted.isEmpty() || Taste.artistKey(it.artist) in wanted }
        found += taste.favouriteTracks.filter { wanted.isEmpty() || Taste.artistKey(it.artist) in wanted }
        return pick(found, spec, taste)
    }

    /** Scores, dedupes and orders [candidates] into at most [SmartSpec.size] songs. */
    fun pick(candidates: List<Track>, spec: SmartSpec, taste: Taste): List<Track> {
        val unique = candidates.distinctBy { recordingKey(it.title, it.artist) }
        // Discovery is a dial between "songs you love" and "songs you have not heard here": at 0 a
        // song's own love counts fully and the familiar get a lift; at 1 its own love is set aside
        // and the unheard get the lift. The artist's place in the taste always counts, and so do skips.
        val d = spec.discovery.coerceIn(0f, 1f)
        val scored = unique.map { track ->
            val fresh = !taste.heard(track)
            val lift = (if (fresh) d else 1f - d) * 4f
            val score = taste.artistScore(track.artist) + taste.loved(track) * (1f - d) + lift -
                taste.skips(track) * 0.6f + random.nextFloat() * 0.8f
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
}
