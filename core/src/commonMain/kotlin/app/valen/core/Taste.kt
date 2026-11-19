/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.pow

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
 * One credited artist's listening, as evidence that this person really chooses them.
 *
 * The all-time time and plays here are the person's *own* listening: time that came out of a list
 * Valen made ("For you", a radio, a mix) is kept apart ([genMs]) and never makes an artist look
 * chosen, so a mix cannot seed the next mix. A song is counted once per recording, from any service.
 */
data class ArtistEvidence(
    /** Time listened for real (generated-list time left out), in ms. */
    val ms: Long = 0,
    /** The part of the all-time listening that came from generated lists. */
    val genMs: Long = 0,
    /** Recency-weighted real listening: recent weeks weigh as much as all before, halving every [RECENT_HALF_DAYS]. */
    val recentMs: Double = 0.0,
    /** Plays here, generated-list starts left out. */
    val plays: Int = 0,
    /** Recordings of this artist heard for real (not only from generated lists). */
    val songs: Int = 0,
    /** Distinct days with a real listen of this artist ([Taste.MIN_EVIDENCE_DAY_MS] or more). */
    val days: Int = 0,
    /** Early skips of this artist's songs. */
    val skips: Int = 0,
) {
    /** The generated share of the listening, 0 (all their own) to 1 (all from mixes). */
    val generatedShare: Float
        get() = if (ms + genMs <= 0L) 0f else genMs.toFloat() / (ms + genMs).toFloat()
}

/**
 * The person's taste, read from what they told Valen (favourite artists and songs) and what they
 * did (time listened, plays, songs skipped early, songs hidden).
 *
 * An artist is known by each name a song credits ("A, B", "A feat. B": both), as [artistKey] says
 * it, so a collaboration counts for each and "Daft Punk - Topic" is Daft Punk. Recent weeks weigh as
 * much as all the time before them ([ListeningLog.daily], halving every [RECENT_HALF_DAYS] days), so
 * the taste follows what is played now. A song is heard by recording, whichever service played it.
 *
 * Listening from a list Valen generated is counted apart ([Listened.genMs], [DayListening.genSongs]):
 * it shows in the stats but is not evidence of a choice, so a generated mix cannot get its own
 * artists seeded, its own songs onto "on repeat", or its own uploads past the derivative gates.
 */
class Taste(
    private val log: ListeningLog,
    favouriteArtists: List<String>,
    private val favouriteSongs: List<Track>,
    /** The song menu's "Less like this": these artists are left out of everything Valen makes. */
    dislikedArtists: List<String> = emptyList(),
    /** The song menu's "Don't recommend this song": these uploads ([Track.key]) are never offered. */
    hiddenTracks: List<String> = emptyList(),
) {
    /** The artists named as favourites, as given. */
    val favouriteArtists: List<String> = favouriteArtists.distinct()

    private val favourites: Set<String> =
        (this.favouriteArtists + favouriteSongs.map { it.artist }).flatMap(::creditsOf).toSet()
    private val favouriteSongKeys: Set<String> =
        favouriteSongs.map { recordingKey(it.title, it.artist) }.toSet()
    private val hiddenKeys: Set<String> = hiddenTracks.toSet()
    private val explicitDislikeKeys: Set<String> = dislikedArtists.map(::artistKey).filter { it.isNotEmpty() }.toSet()

    /** Whether [daily] has any data at all: old logs (and unit tests) have none, and are not punished for it. */
    private val hasDaily = log.daily.isNotEmpty()
    private val newestDay: Long? = log.daily.keys.mapNotNull { it.toLongOrNull() }.maxOrNull()

    /** All-time time by credited artist, generated included (what [ListeningLog.artists] says). */
    private val artistTotalMs: Map<String, Long> = HashMap<String, Long>().also { out ->
        log.artists.forEach { (line, ms) -> creditsOf(line).forEach { out[it] = (out[it] ?: 0L) + ms } }
    }

    /** The generated part of the all-time time, by credited artist. */
    private val artistGenMs: Map<String, Long> = HashMap<String, Long>().also { out ->
        log.songs.values.forEach { song ->
            val gen = song.genMs
            if (gen > 0) creditsOf(song.artist).forEach { out[it] = (out[it] ?: 0L) + gen }
        }
    }

    /** The person's own time by credited artist: all-time minus what generated lists spent on them. */
    private val artistMs: Map<String, Long> =
        artistTotalMs.mapValues { (key, ms) -> (ms - (artistGenMs[key] ?: 0L)).coerceAtLeast(0L) }

    private val totalGen: Long = log.songs.values.sumOf { it.genMs }
    /** The person's own listening, in ms: [ListeningLog.totalMs] minus generated-list time. */
    private val totalReal: Float = (log.totalMs - totalGen).coerceAtLeast(1L).toFloat()

    /** All plays here by credited artist, generated starts included (for the skip rate). */
    private val artistPlays: Map<String, Int> = HashMap<String, Int>().also { out ->
        log.songs.values.forEach { song -> creditsOf(song.artist).forEach { out[it] = (out[it] ?: 0) + song.plays } }
    }

    /** The person's own plays, by credited artist: generated starts left out. */
    private val artistRealPlays: Map<String, Int> = HashMap<String, Int>().also { out ->
        log.songs.values.forEach { song ->
            val plays = (song.plays - song.genPlays).coerceAtLeast(0)
            creditsOf(song.artist).forEach { out[it] = (out[it] ?: 0) + plays }
        }
    }

    /** Recordings with real listening, by credited artist. */
    private val artistSongs: Map<String, Int> = HashMap<String, Int>().also { out ->
        log.songs.values.forEach { song ->
            if (song.ms - song.genMs > 0L) creditsOf(song.artist).forEach { out[it] = (out[it] ?: 0) + 1 }
        }
    }

    private val artistSkips: Map<String, Int> = HashMap<String, Int>().also { out ->
        log.skips.forEach { (key, n) -> log.songs[key]?.let { song -> creditsOf(song.artist).forEach { out[it] = (out[it] ?: 0) + n } } }
    }

    /** Recent real listening by credited artist, each day weighed by how long ago it was. */
    private val recentMs: Map<String, Double>
    private val recentTotal: Double

    /** Distinct days with a real listen of each artist, and the days it happened on. */
    private val artistDays: Map<String, Int>

    /** Recording key of each song key in the log. */
    private val recordingOf: Map<String, String> = log.songs.mapValues { (_, song) -> recordingKey(song.title, song.artist) }

    /** Every recording heard here, from any service. */
    private val recordingKeys: Set<String> = recordingOf.values.toSet()

    /** The person's own time with each recording (generated time left out). */
    private val recordingRealMs: Map<String, Long> = HashMap<String, Long>().also { out ->
        log.songs.forEach { (key, song) ->
            val real = (song.ms - song.genMs).coerceAtLeast(0L)
            if (real > 0L) recordingOf[key]?.let { out[it] = (out[it] ?: 0L) + real }
        }
    }
    private val recordingPlays: Map<String, Int> = HashMap<String, Int>().also { out ->
        log.songs.forEach { (key, song) -> recordingOf[key]?.let { out[it] = (out[it] ?: 0) + song.plays } }
    }
    private val recordingSkips: Map<String, Int> = HashMap<String, Int>().also { out ->
        log.skips.forEach { (key, n) -> (recordingOf[key] ?: key).let { out[it] = (out[it] ?: 0) + n } }
    }

    /**
     * Distinctive titles already heard here and the credited artists of those recordings: what a
     * cover is recognised by, even when its upload never says "cover".
     */
    private val knownTitles: Map<String, List<Set<String>>> = HashMap<String, MutableList<Set<String>>>().also { out ->
        log.songs.values.forEach { song ->
            val title = songTitleKey(song.title)
            if (!distinctiveTitle(title)) return@forEach
            val credits = creditsOf(song.artist).toSet()
            if (credits.isEmpty()) return@forEach
            out.getOrPut(title) { ArrayList() }.add(credits)
        }
    }

    init {
        val out = HashMap<String, Double>()
        var sum = 0.0
        val days = HashMap<String, Int>()
        val newest = newestDay
        if (newest != null) {
            for ((day, listening) in log.daily) {
                val age = newest - (day.toLongOrNull() ?: continue)
                val weight = 0.5.pow(age / RECENT_HALF_DAYS)
                val realToday = HashMap<String, Long>()
                for ((songKey, ms) in listening.songs) {
                    val song = log.songs[songKey] ?: continue
                    val real = (ms - (listening.genSongs[songKey] ?: 0L)).coerceAtLeast(0L)
                    if (real <= 0L) continue
                    creditsOf(song.artist).forEach { key -> realToday[key] = (realToday[key] ?: 0L) + real }
                    sum += real * weight
                }
                for ((key, ms) in realToday) {
                    out[key] = (out[key] ?: 0.0) + ms * weight
                    // A real listen of a minute or more is a day of evidence for that artist.
                    if (ms >= MIN_EVIDENCE_DAY_MS) days[key] = (days[key] ?: 0) + 1
                }
            }
        }
        recentMs = out
        recentTotal = sum
        artistDays = days
    }

    private val evidenceCache = HashMap<String, ArtistEvidence>()

    private fun evidenceOfKey(key: String): ArtistEvidence = evidenceCache.getOrPut(key) {
        ArtistEvidence(
            ms = artistMs[key] ?: 0L,
            genMs = artistGenMs[key] ?: 0L,
            recentMs = recentMs[key] ?: 0.0,
            plays = artistRealPlays[key] ?: 0,
            songs = artistSongs[key] ?: 0,
            days = artistDays[key] ?: 0,
            skips = artistSkips[key] ?: 0,
        )
    }

    /**
     * One artist line's listening: the best-liked credit of it, as [artistScore] reads it. An empty
     * artist line (a channel that credits nobody) has no evidence.
     */
    fun evidence(artist: String): ArtistEvidence {
        val credits = creditsOf(artist)
        if (credits.isEmpty()) return ArtistEvidence()
        return credits.map(::evidenceOfKey).maxWithOrNull(compareBy({ it.ms }, { it.plays }, { it.recentMs })) ?: ArtistEvidence()
    }

    /**
     * How much this person really chose [artist], from 0 to 1: favourite artists are trusted fully;
     * otherwise it grows with the number of days, recordings and plays they gave them, less their
     * skip rate. A single day of listening — one generated afternoon, however many songs it added —
     * never reaches enough to seed a mix on its own ([SEED_TRUST]).
     */
    fun trust(artist: String): Float = creditsOf(artist).maxOfOrNull(::trustOfKey) ?: 0f

    private fun trustOfKey(key: String): Float {
        if (key in favourites) return 1f
        val evidence = evidenceOfKey(key)
        if (evidence.ms <= 0L && evidence.genMs <= 0L) return 0f
        val plays = maxOf(artistPlays[key] ?: 0, evidence.skips, 1)
        val skipRate = (evidence.skips.toFloat() / plays).coerceIn(0f, 1f)
        val keep = 1f - 0.5f * skipRate
        // Old logs (and tests) predate [ListeningLog.daily]: plays and recordings are all there is,
        // so the day term is left out rather than counted as zero.
        if (!hasDaily) {
            val songs = min(1f, evidence.songs / 3f)
            val hearing = min(1f, evidence.plays / 6f)
            return ((0.3f * songs + 0.2f * hearing) / 0.5f * keep).coerceIn(0f, 1f)
        }
        val raw = 0.5f * min(1f, evidence.days / 2f) +
            0.3f * min(1f, evidence.songs / 3f) +
            0.2f * min(1f, evidence.plays / 6f)
        // The slop shield: one day is one session, not a taste.
        val shielded = if (evidence.days < 2) min(raw, SEED_TRUST * 0.9f) else raw
        return (shielded * keep).coerceIn(0f, 1f)
    }

    /** One credited artist: told favourite, share of the real listening (recent and all-time), less skipping. */
    private fun creditScore(key: String): Float {
        val allTime = (artistMs[key] ?: 0L) / totalReal
        val recent = if (recentTotal > 0) ((recentMs[key] ?: 0.0) / recentTotal).toFloat() else 0f
        val share = 0.5f * allTime + 0.5f * recent
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

    /** The artists this person likes most, told and listened, best first (never one they skip away from). */
    fun topArtists(n: Int): List<String> {
        val names = linkedMapOf<String, String>()
        (givenNames + log.artists.keys.flatMap(::artistsIn)).forEach { name ->
            creditsOf(name).firstOrNull()?.let { names.putIfAbsent(it, cleanName(name)) }
        }
        return names.values.filter { !dislikes(it) }.sortedByDescending { artistScore(it) }.take(n)
    }

    private val givenNames = (favouriteArtists + favouriteSongs.map { it.artist }).flatMap(::artistsIn)

    /** The songs played here this person comes back to most: their own listening only, less skips. */
    fun onRepeat(n: Int): List<Track> = log.songs.entries
        // A song whose listening is mostly generated was offered, not chosen: it is not a repeat.
        .filter { (key, song) -> song.genMs * 2 <= song.ms && (log.skips[key] ?: 0) < song.plays }
        .sortedByDescending { (_, song) -> (song.ms - song.genMs) + (song.plays - song.genPlays).coerceAtLeast(0) * 30_000L }
        .distinctBy { (key, _) -> recordingOf[key] }
        .take(n)
        .map { (key, song) -> song.toTrack(key) }

    val favouriteTracks: List<Track> get() = favouriteSongs

    /** This very upload has been played here. */
    fun played(track: Track): Boolean = log.songs.containsKey(track.key)

    /** Whether this recording has been played here, from any service, even from a generated list. */
    fun heard(track: Track): Boolean = log.songs.containsKey(track.key) || recordingKey(track.title, track.artist) in recordingKeys

    /** How much [track] itself is loved, apart from its artist: the person's own time with it, and being told. */
    fun loved(track: Track): Float {
        val rec = recordingKey(track.title, track.artist)
        val own = (recordingRealMs[rec] ?: 0L) / totalReal * 6f
        val favourite = if (rec in favouriteSongKeys) 2.5f else 0f
        return own + favourite
    }

    fun skips(track: Track): Int = recordingSkips[recordingKey(track.title, track.artist)] ?: (log.skips[track.key] ?: 0)

    /** Plays of [track]'s recording here (all services). */
    fun plays(track: Track): Int = recordingPlays[recordingKey(track.title, track.artist)] ?: (log.songs[track.key]?.plays ?: 0)

    /**
     * The song behind this upload has been heard here by a *different* artist: a cover whose title
     * says nothing about being one. The title has to be distinctive ("the eyes of ibad" is, "awake"
     * is not) so two unrelated songs that happen to share a short name are not mistaken for it.
     */
    fun isCover(track: Track): Boolean {
        val title = songTitleKey(track.title)
        if (!distinctiveTitle(title)) return false
        val credits = creditsOf(track.artist).toSet()
        if (credits.isEmpty()) return false
        val heard = knownTitles[title] ?: return false
        // A cover only when no version heard here shares an artist with this track: once the title
        // was heard from a different act it is a cover; if it was heard from this act too (the
        // original and its cover are both in the log), it is not.
        return heard.none { logCredits -> logCredits.any { it in credits } }
    }

    /** This very upload is never to be recommended again ("Don't recommend this song"). */
    fun hidden(track: Track): Boolean = track.key in hiddenKeys

    /** The song menu's named dislike for one of [artist]'s credits. */
    fun explicitlyDisliked(artist: String): Boolean = creditsOf(artist).any { it in explicitDislikeKeys }

    /**
     * An artist this person keeps skipping and never named: left out of what is made for them
     * ("resting"). They come back once their skip rate falls, which listening to them does.
     */
    fun restingArtist(artist: String): Boolean {
        val credits = creditsOf(artist)
        if (credits.isEmpty()) return false
        return credits.all { key ->
            if (key in favourites) return@all false
            val skips = artistSkips[key] ?: 0
            if (skips < 3) return@all false
            val rate = skips.toFloat() / maxOf(artistPlays[key] ?: 0, skips, 1)
            rate >= 0.6f
        }
    }

    /** An artist explicitly hidden, inferred by skipping, or resting: left out of a generated list. */
    fun dislikes(artist: String): Boolean = explicitlyDisliked(artist) || restingArtist(artist)

    /** This upload has been heard often in the last [RESTING_DAYS] days: a mix should not repeat it yet. */
    fun resting(track: Track): Boolean {
        val newest = newestDay ?: return false
        val rec = recordingKey(track.title, track.artist)
        var plays = 0
        for (age in 0 until RESTING_DAYS) {
            val day = log.daily[(newest - age).toString()] ?: continue
            for ((key, n) in day.plays) {
                val song = log.songs[key] ?: continue
                if (recordingKey(song.title, song.artist) == rec) plays += n
            }
        }
        return plays >= RESTING_PLAYS
    }

    /** How much this person likes [track]. */
    fun songScore(track: Track): Float = artistScore(track.artist) + loved(track) - skips(track) * 0.6f

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

        /** The trust below which an artist cannot seed a mix on listening alone (one day never does). */
        const val SEED_TRUST = 0.35f

        /** A day of listening counts as evidence for an artist only when it is this long (ms). */
        const val MIN_EVIDENCE_DAY_MS = 60_000L

        /** Days back that "heard a lot lately" looks ([resting]). */
        const val RESTING_DAYS = 3

        /** Plays of a recording in those days that makes it resting. */
        const val RESTING_PLAYS = 3

        /** A title this short or generic is not enough to recognise a cover by. */
        private fun distinctiveTitle(title: String): Boolean {
            if (title.isEmpty()) return false
            val chars = title.length
            val words = title.count { it == ' ' } + 1
            return (chars >= 6 && words >= 2) || chars >= 10
        }
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
