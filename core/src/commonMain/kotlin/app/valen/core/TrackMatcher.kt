/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Which search result is actually the song that was asked for.
 *
 * Ported from Mochy Music's importer (`TrackMatcher`, GPL-3.0, credited in `NOTICE`): a title
 * with the upload's advertising removed is compared by word overlap and edit distance, the lead
 * artist is compared separately, and the length decides between two uploads of one title. A
 * remix, a live take or a sped-up edit the source did not ask for is a different song, so it is
 * penalised rather than accepted because its title matches.
 *
 * Valen's catalogue objects carry no upload kind, so Mochy's "the artist's own topic upload
 * beats a fan video" becomes "a result that knows its album beats one that does not": the album
 * name is what a topic upload always has.
 */
object TrackMatcher {

    /** No result below this is the song. A result this good is taken without further checks. */
    const val ACCEPT = 0.62

    /** A last chance for catalogues that surname the artist differently; still a real match. */
    const val FALLBACK_ACCEPT = 0.5

    /** A title below this cannot be the same song however good the rest is. */
    private const val MIN_TITLE = 0.45

    /**
     * A result whose artist has nothing to do with the source's lead artist is not the song, even
     * when its title and length are exact: a cover with the same title is still a cover. Mochy's
     * weights alone let one through (title 0.5 + length 0.25 carries it), so it is refused here.
     */
    private const val MIN_ARTIST = 0.5

    /** Within this many seconds of the source, a result is the same recording (Mochy's value). */
    const val CLOSE_SECONDS = 3.0

    private const val VERSION_PENALTY = 0.3

    /** A live-album mismatch is held just above the fallback line and never above this cap. */
    private const val LIVE_ALBUM_CAP = 0.55

    /** One candidate and how well it fits, best first for a "choose another version" list. */
    data class Rated(val track: Track, val score: Double)

    /** The best candidate above [accept], or null when nothing is close enough. */
    fun best(source: Track, found: List<Track>, accept: Double = ACCEPT): Track? =
        found.maxByOrNull { score(source, it) }?.takeIf { score(source, it) >= accept }

    /** Everything found, scored and best first; [Rated.score] lets a screen show the weak ones. */
    fun ranked(source: Track, found: List<Track>): List<Rated> =
        found.map { Rated(it, score(source, it)) }.sortedByDescending { it.score }

    /** The query that finds this song: the title without advertising, then the lead artist. */
    fun query(source: Track): String {
        val artist = splitArtists(source.artist).firstOrNull().orEmpty()
        return "${cleanTitleForSearch(source.title)} $artist".trim()
    }

    fun score(source: Track, candidate: Track): Double {
        val targetTitle = normalizeTitle(source.title)
        val candidateTitle = normalizeTitle(candidate.title)
        var titleScore = similarity(targetTitle, candidateTitle)

        val targetArtists = splitArtists(source.artist).map(::normalizeText).filter { it.isNotEmpty() }
        val candidateArtists = splitArtists(candidate.artist).map(::normalizeText).filter { it.isNotEmpty() }
        var artistScore = if (targetArtists.isEmpty()) {
            0.5
        } else {
            targetArtists.maxOf { target -> candidateArtists.maxOfOrNull { similarity(target, it) } ?: 0.0 }
        }

        // A video titled "Artist - Song (official video)" names the artist in the title instead.
        if (artistScore < 0.6 && targetArtists.any { it.isNotEmpty() && candidateTitle.contains(it) }) {
            artistScore = 0.8
            titleScore = max(
                titleScore,
                similarity(targetTitle, targetArtists.fold(candidateTitle) { acc, a -> acc.replace(a, " ") }.trim()),
            )
        }

        val durationScore = durationScore(source.durationMs, candidate.durationMs)

        // A remix, live take or sped-up edit the source did not ask for (or a version the source
        // did ask for and this is not) is a different recording: no weighting can make it the
        // same song, so it is refused outright rather than merely marked down.
        val rawTarget = " ${normalizeText(source.title)} "
        val rawCandidate = " ${normalizeText(candidate.title)} "
        val versionMismatch = VERSION_WORDS.any { word ->
            rawCandidate.contains(" $word ") != rawTarget.contains(" $word ")
        }
        if (versionMismatch) return 0.0

        if (titleScore < MIN_TITLE) return 0.0
        if (targetArtists.isNotEmpty() && artistScore < MIN_ARTIST) return 0.0
        val album = albumScore(source.album, candidate.album)
        val total = titleScore * 0.5 + artistScore * 0.25 + durationScore * 0.25 +
            album + albumPresenceScore(candidate)
        // A live or tour album the source is not on is strong evidence of a different take, but
        // not proof (the upload may be the studio recording on a live compilation), so it is held
        // below the accept line instead of refused outright.
        return if (album < 0) total.coerceAtMost(LIVE_ALBUM_CAP) else total
    }

    /** A small lift for the same album, and a penalty for a live or tour album the source is not on. */
    private fun albumScore(sourceAlbum: String?, candidateAlbum: String?): Double {
        if (candidateAlbum.isNullOrBlank()) return 0.0
        val candidate = " ${normalizeText(candidateAlbum)} "
        val source = " ${normalizeText(sourceAlbum.orEmpty())} "
        val liveAlbum = LIVE_ALBUM_WORDS.any { candidate.contains(" $it ") && !source.contains(" $it ") }
        if (liveAlbum) return -VERSION_PENALTY
        if (sourceAlbum.isNullOrBlank()) return 0.0
        return if (similarity(source.trim(), candidate.trim()) >= 0.8) 0.05 else 0.0
    }

    /** A result that knows its album is a catalogue entry, not a loose video upload. */
    private fun albumPresenceScore(candidate: Track): Double =
        if (!candidate.album.isNullOrBlank()) 0.05 else 0.0

    private fun durationScore(sourceMs: Long, candidateMs: Long): Double {
        if (sourceMs <= 0 || candidateMs <= 0) return 0.5
        val diff = abs(sourceMs / 1000.0 - candidateMs / 1000.0)
        return when {
            diff <= CLOSE_SECONDS -> 1.0
            diff <= 8 -> 0.6
            diff <= 20 -> 0.1
            diff <= 60 -> -0.3
            else -> -1.0
        }
    }

    fun splitArtists(artist: String): List<String> =
        artist.replace('\u00A0', ' ')
            .split(ARTIST_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** The title without the advertising, ready for a search field. */
    fun cleanTitleForSearch(title: String): String =
        title.replace(NOISE_IN_BRACKETS, " ")
            .replace(DASH_SUFFIX, "")
            .replace(FEAT_TAIL, "")
            .replace(SPACES, " ")
            .trim()
            .ifEmpty { title }

    fun normalizeTitle(title: String): String = normalizeText(cleanTitleForSearch(title))

    /**
     * Case, accents, punctuation and spacing folded away, so "Beyoncé — Halo (Live)" and
     * "beyonce halo live" compare as the same words. [Normalizer] does not exist in shared
     * Kotlin, so the accents of the Latin blocks are folded by hand; anything outside them keeps
     * its letters, which is right for scripts that never carried these marks.
     */
    fun normalizeText(text: String): String {
        val folded = buildString(text.length) {
            for (ch in text.lowercase()) {
                if (ch in '\u0300'..'\u036f') continue
                append(FOLD[ch] ?: ch)
            }
        }
        return folded.replace(NON_WORD, " ").trim()
    }

    /**
     * Best of token overlap and character edit distance, so both reordered words and scripts
     * without spaces score fairly, plus a fixed 0.8 when one title fully contains the other
     * ("song" versus "song pt. 1": close, not exact).
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val tokensA = a.split(' ').toSet()
        val tokensB = b.split(' ').toSet()
        val intersection = tokensA.intersect(tokensB).size
        val jaccard = intersection.toDouble() / (tokensA.size + tokensB.size - intersection)
        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        val edit = 1.0 - levenshtein(compactA, compactB).toDouble() / max(compactA.length, compactB.length)
        val containment = if (compactA.length >= 3 && compactB.length >= 3 &&
            (compactA.contains(compactB) || compactB.contains(compactA))
        ) {
            0.8
        } else {
            0.0
        }
        return maxOf(jaccard, edit, containment)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.length > 200 || b.length > 200) return max(a.length, b.length) - min(a.length, b.length)
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val swap = prev
            prev = curr
            curr = swap
        }
        return prev[b.length]
    }

    private val VERSION_WORDS = listOf(
        "remix", "live", "instrumental", "inst", "acoustic", "karaoke", "cover", "sped up", "slowed",
        "nightcore", "reverb", "8d", "stage", "dance practice", "practice", "performance", "fancam",
        "concert", "rehearsal", "reaction", "1 hour", "hour", "loop", "extended",
        "japanese", "korean", "english", "chinese",
    )
    private val LIVE_ALBUM_WORDS = listOf("live", "tour", "concert", "unplugged", "session", "sessions")

    private val NOISE_IN_BRACKETS = Regex(
        "[(\\[][^)\\]]*\\b(feat|ft|with|remaster|remastered|version|edit|mono|stereo|deluxe|explicit|clean|bonus|from|official|audio|video|lyrics?|visuali[sz]er|hd|hq|4k)\\b[^)\\]]*[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val DASH_SUFFIX = Regex(
        "\\s[-–—]\\s.*(remaster|version|edit|mono|stereo|mix\\b|from |live at|bonus).*$",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT_TAIL = Regex("\\s(feat\\.?|ft\\.?|featuring)\\s.*$", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex(
        "\\s*(,|&|;|/|\\sx\\s|\\sand\\s|\\sfeat\\.?\\s|\\sft\\.?\\s|\\sfeaturing\\s|\\swith\\s)\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")

    /**
     * The accented Latin letters a title or an artist name actually uses, and their base. Built
     * from groups rather than two parallel strings: a missing letter would silently shift every
     * mapping after it.
     */
    private val FOLD: Map<Char, Char> = listOf(
        "àáâãäåāăą" to 'a',
        "çćč" to 'c',
        "ďđ" to 'd',
        "èéêëēėęě" to 'e',
        "ğ" to 'g',
        "ìíîïīįı" to 'i',
        "ł" to 'l',
        "ñńň" to 'n',
        "òóôõöøōő" to 'o',
        "ŕř" to 'r',
        "śšş" to 's',
        "ť" to 't',
        "ùúûüūůű" to 'u',
        "ýÿ" to 'y',
        "źżž" to 'z',
    ).flatMap { (letters, base) -> letters.map { it to base } }.toMap()
}
