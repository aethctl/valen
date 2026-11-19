/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CancellationException
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** A related artist exactly as one service's artist page named them: id = that artist there. */
data class RelatedArtist(
    val name: String,
    val provider: ProviderId,
    val id: String,
    /** Position in that service's own related list, 0 = best. */
    val rank: Int,
)

/** Where a candidate in a mix came from, so scoring knows how much of the person's taste stands behind it. */
enum class Origin { SEED, RELATED, GENRE, LOVED }

/**
 * What the candidate list knows about itself: how known each recording is, where each was found, and
 * which artists are the seeds' own or new discoveries. [pick] scores only from this and the taste; the
 * network work is done before it, in [SmartMixer.build].
 */
class Context(
    /** By recording key: how known the recording is (1 for an artist's first page hit, falling). */
    val known: Map<String, Float> = emptyMap(),
    /** By recording key: the layer that offered it. */
    val origins: Map<String, Origin> = emptyMap(),
    /** Artist keys that may only be in the mix as discoveries (related artists, genre finds). */
    val newArtists: Set<String> = emptySet(),
    /** The seed artists' keys: their songs are the person's own choice. */
    val seedArtists: Set<String> = emptySet(),
)

/**
 * Makes song lists from a taste: Home's "For you", artist mixes, a radio and smart playlists.
 *
 * Every layer is entity-exact, so the next mix cannot be polluted by this one:
 * - a **seed artist's** songs come only from its merged artist page's popular songs ([artistTop]) and
 *   from the person's own loved/on-repeat recordings, never from an unfiltered name search (which is
 *   how fan uploads and unrelated same-named acts used to get in);
 * - **new artists** come from the services' own related lists, carried as [RelatedArtist] with the
 *   service and its id ([related]), and their songs only from that exact service page ([relatedSongs]).
 *   A related list can still name a genuinely unrelated act, so a discovery is accepted only when it
 *   is corroborated: 2+ seed artists named it, or it is rank 0 for one, or (single-seed mixes where
 *   corroboration is impossible) it is in the first [SINGLE_SEED_MAX_RELATED_RANK] places;
 * - **genres** search, but a stranger's track is accepted only when the artist's own page confirms the
 *   recording belongs to them (catalogue membership) or the same artist appears twice in the search.
 *
 * Then the hard gates (hidden, disliked, derivative, cover-by-title, skipped away, recording dedupe)
 * and the anti-slop quotas (at most half new artists scaled by discovery, at most two songs per new
 * artist, at least a quarter from familiar artists, an artist cap) run before scoring, and the list is
 * spread so no artist plays twice in a row. Songs already served in the last rebuilds ([avoid]) are
 * left out when enough candidates remain. Listening from a generated list never promotes its own
 * artists: [Taste.trust] leaves it out.
 */
class SmartMixer(
    private val search: suspend (String) -> List<Track>,
    private val follows: suspend () -> List<String> = { emptyList() },
    /** Related artists of a seed artist, across services, with their identity and rank. */
    private val related: suspend (String) -> List<RelatedArtist> = { emptyList() },
    /** EXACT-entity artist page top tracks (never a name search; no other artist with that name). */
    private val relatedSongs: suspend (RelatedArtist) -> List<Track> = { emptyList() },
    /** Verified catalogue of an artist by name (merged artist page popular tracks). */
    private val artistTop: suspend (String) -> List<Track> = { emptyList() },
    private val random: Random = Random.Default,
) {
    /** The last [pick]'s knowledge of which artists are new, for [spread] to space them apart. */
    private var lastPlan: Plan? = null

    /**
     * The artists a [spec] asks for (best first) and the genre words it asks for. Seed artists are
     * drawn by weight, not always the same top-N, so "For you" rebuilds differ without losing taste.
     */
    suspend fun seeds(spec: SmartSpec, taste: Taste): Pair<List<String>, List<String>> {
        val named = ArrayList<String>()
        val found = ArrayList<String>()
        fun add(out: MutableList<String>, name: String) {
            val key = Taste.artistKey(name)
            if (key.isEmpty()) return
            if (out.any { Taste.artistKey(it) == key }) return
            out += Taste.cleanName(name).ifEmpty { name }
        }
        // Named on purpose (an artist mix, a radio seed): kept ahead of everything, dislike or not.
        if (SmartSource.Artists in spec.sources) spec.artists.forEach { add(named, it) }
        if (SmartSource.Favourites in spec.sources) {
            taste.favouriteArtists.forEach { add(found, it) }
            taste.topArtists(12).filter { taste.artistScore(it) >= 3f }.forEach { add(found, it) }
        }
        if (SmartSource.MostPlayed in spec.sources) {
            // The slop shield: an artist heard on one day only (or only from generated lists) does not
            // seed a mix, however many songs that one session added. Favourites are always trusted.
            taste.topArtists(16).filter { taste.trust(it) >= Taste.SEED_TRUST }.forEach { add(found, it) }
        }
        if (SmartSource.SpotifyFollows in spec.sources) {
            safe(emptyList<String>()) { follows() }.take(15).forEach { add(found, it) }
        }
        // A fresh user (or old log): nothing has passed the trust gate yet, so fall back to the score.
        if (named.size + found.size < 3) taste.topArtists(8).forEach { add(found, it) }
        val disliked = found.filter { taste.dislikes(it) }.toSet()
        val audible = found.filterNot { it in disliked }
        val genres = if (SmartSource.Genres in spec.sources) spec.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct() else emptyList()
        // Named artists keep their order; the rest are sampled by weight, so each rebuild varies.
        return (named + weightedOrder(audible, taste)).distinctBy(Taste::artistKey) to genres
    }

    /**
     * Builds the candidate layers, then [pick]s and [spread]s them. Each source runs inside its own
     * guard: a service that fails is skipped, not fatal, so a mix still comes back from what answered.
     */
    suspend fun build(
        spec: SmartSpec,
        taste: Taste,
        onProgress: (Float) -> Unit = {},
        avoid: Set<String> = emptySet(),
    ): List<Track> {
        val d = spec.discovery.coerceIn(0f, 1f)
        val size = spec.size.coerceIn(1, MAX_SIZE)
        fun report(value: Float) {
            try {
                onProgress(value.coerceIn(0f, 1f))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Progress is a courtesy; a listener that fails must not fail the mix.
            }
        }
        report(0f)
        val (seedNames, genres) = seeds(spec, taste)
        val seedArtists = seedNames.take(MAX_ARTIST_SEEDS)
        report(0.05f)
        if (seedArtists.isEmpty() && genres.isEmpty()) {
            report(1f)
            return emptyList()
        }
        val seedKeys = seedArtists.map(Taste::artistKey).filter { it.isNotEmpty() }.toSet()
        val candidates = ArrayList<Track>()
        val known = HashMap<String, Float>()
        val origins = HashMap<String, Origin>()

        fun offer(track: Track, origin: Origin, rank: Float) {
            val rec = recordingKey(track.title, track.artist)
            if (rec.isEmpty()) return
            val score = rank.coerceIn(0f, 1f)
            val previous = origins[rec]
            if (previous == null) {
                origins[rec] = origin
                known[rec] = score
                candidates += track
            } else if (score > (known[rec] ?: 0f)) {
                known[rec] = score
            }
        }

        // 1. The seeds' own songs: their artist page's popular tracks, never a name search — unless the
        // page answered nothing at all (offline, or a test/old caller with only `search`), where a
        // credit-filtered search for this seed is the only layer left.
        seedArtists.forEachIndexed { index, seed ->
            val key = Taste.artistKey(seed)
            val top = safe(emptyList<Track>()) { artistTop(seed) }
            val fromTop = top.isNotEmpty()
            val pool = if (fromTop) top else safe(emptyList<Track>()) { search(seed) }
            var kept = 0
            for ((position, track) in pool.withIndex()) {
                if (kept >= PER_SEED_SONGS) break
                if (key !in Taste.creditsOf(track.artist)) continue
                if (!taste.played(track) && derivative(track, taste)) continue
                val rank = if (fromTop) 1f - position / PER_SEED_SONGS.toFloat() else 1f - position / (PER_SEED_SONGS * 1.5f)
                offer(track, Origin.SEED, rank)
                kept++
            }
            report(0.05f + 0.25f * (index + 1) / seedArtists.size)
        }

        // 2. New artists from the related lists, aggregated by artist identity and accepted only when
        // corroborated. Its songs come from the exact service artist page and nowhere else.
        val newArtists = LinkedHashSet<String>()
        val newCap = newSongBudget(size, d)
        if (d > 0f && newCap > 0 && seedArtists.isNotEmpty()) {
            val byArtist = LinkedHashMap<String, RelatedCandidate>()
            val seedsAsked = seedArtists.take(RELATED_FROM)
            for ((index, seed) in seedsAsked.withIndex()) {
                val seedKey = Taste.artistKey(seed)
                for (entry in safe(emptyList<RelatedArtist>()) { related(seed) }) {
                    val key = Taste.artistKey(entry.name)
                    if (key.isEmpty() || key in seedKeys) continue
                    if (channelish(entry.name)) continue
                    if (taste.explicitlyDisliked(entry.name) || taste.restingArtist(entry.name)) continue
                    // Someone the taste already knows is not a discovery: they come back through love/on-repeat.
                    if (taste.trust(entry.name) >= CONFIDENT_TRUST) continue
                    val previous = byArtist[key]
                    if (previous == null) {
                        byArtist[key] = RelatedCandidate(key, entry.name, entry, entry.rank, mutableSetOf(seedKey))
                    } else {
                        if (entry.rank < previous.rank) {
                            previous.best = entry
                            previous.rank = entry.rank
                        }
                        previous.seeds.add(seedKey)
                    }
                }
                report(0.30f + 0.15f * (index + 1) / seedsAsked.size)
            }
            val multiSeed = seedsAsked.size >= 2
            val accepted = byArtist.values
                .filter { candidate ->
                    if (!multiSeed) {
                        candidate.rank <= SINGLE_SEED_MAX_RELATED_RANK
                    } else {
                        candidate.seeds.size >= 2 || candidate.rank == 0
                    }
                }
                .sortedWith(compareByDescending<RelatedCandidate> { it.seeds.size }.thenBy { it.rank }.thenBy { it.key })
            // Spend the discovery budget on the most corroborated peers first.
            val wantedArtists = minOf((2 + (6 * d).roundToInt()).coerceIn(2, MAX_RELATED), (newCap + 1) / 2)
            var admitted = 0
            for (candidate in accepted) {
                if (admitted >= wantedArtists) break
                val songs = safe(emptyList<Track>()) { relatedSongs(candidate.best) }
                // The related list named an artist we cannot read exactly: drop them (empty beats slop).
                if (songs.isEmpty()) continue
                // The rank in the related list is the only quality signal for a new artist; being
                // named by two seeds is worth more than a good rank in one list.
                val relation = (1f / (1f + candidate.rank)) * (1.5f / candidate.seeds.size.coerceAtLeast(1).toFloat()).coerceIn(1f, 1.5f)
                var taken = 0
                for ((position, track) in songs.withIndex()) {
                    if (taken >= RELATED_SONGS_PER_ARTIST) break
                    if (candidate.key !in Taste.creditsOf(track.artist)) continue
                    if (!taste.played(track) && derivative(track, taste)) continue
                    offer(track, Origin.RELATED, relation * (1f - position * 0.25f))
                    taken++
                }
                if (taken > 0) {
                    admitted++
                    newArtists.add(candidate.key)
                }
                report(0.45f + 0.20f * admitted / wantedArtists)
            }
        }

        // 3. The person's own loved/on-repeat recordings that belong to the mix, so it is never only
        // strangers (and a fresh user with no artist page still gets something).
        var lovedOffered = 0
        for (track in taste.onRepeat(MAX_LOVED) + taste.favouriteTracks) {
            if (lovedOffered >= MAX_LOVED) break
            if (seedKeys.isNotEmpty() && Taste.creditsOf(track.artist).none { it in seedKeys }) continue
            offer(track, Origin.LOVED, 1f)
            lovedOffered++
        }
        report(0.65f)

        // 4. Genres: search-based, and a stranger is only accepted with the artist page's backing or a
        // second hit in the same search — never a one-off match.
        if (genres.isNotEmpty()) {
            val asked = genres.take(MAX_GENRES)
            for ((index, genre) in asked.withIndex()) {
                val hits = safe(emptyList<Track>()) { search(genre) }.take(GENRE_HITS)
                val usable = hits.filter { !taste.hidden(it) && (taste.played(it) || (!derivative(it, taste) && !taste.isCover(it))) }
                    .filter { track -> Taste.creditsOf(track.artist).none(::channelish) }
                val byArtist = usable.groupBy { Taste.artistKey(it.artist) }
                val accepted = ArrayList<Track>()
                for (track in usable) {
                    if (accepted.size >= PER_GENRE_SONGS) break
                    val key = Taste.artistKey(track.artist)
                    val familiar = key in seedKeys || taste.trust(track.artist) >= CONFIDENT_TRUST || taste.artistScore(track.artist) >= 1f
                    // One genre search turning a name up twice can be a compilation channel or a
                    // coincidence of tags; a real new artist is asked for its own page below.
                    val repeated = (byArtist[key]?.size ?: 0) >= 3
                    if (familiar || repeated) accepted += track
                }
                if (accepted.size < PER_GENRE_SONGS) {
                    // Catalogue membership, asked once per artist page: the recording is really theirs.
                    val checked = HashMap<String, Set<String>>()
                    for (track in usable) {
                        if (accepted.size >= PER_GENRE_SONGS) break
                        if (track in accepted) continue
                        val key = Taste.artistKey(track.artist)
                        if (key in seedKeys || taste.trust(track.artist) >= CONFIDENT_TRUST) continue
                        val rec = recordingKey(track.title, track.artist)
                        val top = checked.getOrPut(key) {
                            safe(emptyList<Track>()) { artistTop(track.artist) }.mapTo(HashSet()) { recordingKey(it.title, it.artist) }
                        }
                        if (rec in top) accepted += track
                    }
                }
                accepted.forEachIndexed { position, track -> offer(track, Origin.GENRE, 1f - position / GENRE_HITS.toFloat()) }
                report(0.65f + 0.25f * (index + 1) / asked.size)
            }
        }

        report(0.9f)
        if (candidates.isEmpty()) {
            report(1f)
            return emptyList()
        }
        val context = Context(known = known, origins = origins, newArtists = newArtists, seedArtists = seedKeys)
        // The served history is left out when enough is left without it; never enough to empty a mix.
        val preferred = candidates.filter { it.key !in avoid }
        val picked = if (preferred.size >= size) {
            pick(preferred, spec, taste, context)
        } else {
            val fromPreferred = pick(preferred, spec, taste, context)
            if (fromPreferred.size >= preferred.size) pick(candidates, spec, taste, context) else fromPreferred
        }
        val result = spread(picked)
        report(1f)
        return result
    }

    /**
     * Scores, gates and fills [candidates] into at most [SmartSpec.size] songs, best first. This is
     * pure (no network): all candidate finding happened in [build].
     *
     * The anti-slop quotas are kept here, before any ordering: a new artist (one the person's own
     * listening never chose) may take at most two songs and at most `min(0.5, 0.6 × discovery)` of the
     * list, at most a quarter of the list is one artist, and at least a quarter comes from the seeds
     * and loved songs when there are that many.
     */
    fun pick(candidates: List<Track>, spec: SmartSpec, taste: Taste, context: Context = Context()): List<Track> {
        val size = spec.size.coerceIn(1, MAX_SIZE)
        val d = spec.discovery.coerceIn(0f, 1f)
        val named = spec.artists.map(Taste::artistKey).filter { it.isNotEmpty() }.toSet()
        val gated = candidates.asSequence()
            .filter { Taste.creditsOf(it.artist).isNotEmpty() }
            .filter { !taste.hidden(it) }
            .filter { taste.played(it) || !derivative(it, taste) }
            .filter { taste.played(it) || !taste.isCover(it) }
            .filter { track ->
                val credits = Taste.creditsOf(track.artist)
                // A feature by an artist the person said "less like this" to does not ride in on a
                // collaborator they named; only naming that very artist overrides the dislike.
                if (credits.any { taste.explicitlyDisliked(it) && it !in named }) return@filter false
                credits.any { it in named } || !taste.dislikes(track.artist)
            }
            // Skipped away: two or more early skips and never as many plays. A low rate is just taste.
            .filter { taste.skips(it) < 2 || taste.skips(it) < taste.plays(it) }
            // One copy per recording, the first found (a seed page's copy before a genre hit's).
            .distinctBy { recordingKey(it.title, it.artist) }
            .toList()
        if (gated.isEmpty()) {
            lastPlan = null
            return emptyList()
        }
        fun familiar(artist: String): Boolean = Taste.creditsOf(artist).any { credit ->
            credit in context.seedArtists || taste.trust(credit) >= CONFIDENT_TRUST || taste.artistScore(credit) >= 1f
        }
        val scored = gated.map { track ->
            val rec = recordingKey(track.title, track.artist)
            val origin = context.origins[rec] ?: Origin.SEED
            val isNew = !familiar(track.artist)
            // The taste's own score, but only as far as its listening really chose the artist.
            val affinity = taste.artistScore(track.artist).coerceIn(0f, 5f) * taste.trust(track.artist).coerceIn(0f, 1f)
            val love = taste.loved(track) * (1f - d)
            val fresh = !taste.heard(track)
            val trustMix = when {
                !isNew -> 1f
                // A discovery is only as good as the relationship behind it; a genre find of an
                // unknown artist is weakest.
                origin == Origin.RELATED -> 0.35f + 0.65f * (0.55f + 0.45f * (context.known[rec] ?: 0f)).coerceIn(0f, 1f)
                else -> 0.35f + 0.65f * 0.5f * (context.known[rec] ?: 0f)
            }
            val novelty = (if (fresh) d else 1f - d) * 2.4f * trustMix
            val quality = (context.known[rec] ?: 0f) * 0.8f + when (origin) {
                Origin.SEED -> 0.6f
                Origin.LOVED -> 0.5f
                Origin.RELATED -> 0.3f
                Origin.GENRE -> 0.2f
            }
            val fatigue = if (taste.resting(track)) -0.8f else 0f
            val score = affinity + love + novelty + quality + fatigue - taste.skips(track) * 0.5f + random.nextFloat() * 0.25f
            Scored(track, score, new = isNew, strong = origin == Origin.SEED || origin == Origin.LOVED || Taste.creditsOf(track.artist).any { it in context.seedArtists })
        }.sortedByDescending { it.score }

        val artistCap = (size / 4).coerceAtLeast(3)
        val newSongs = newSongBudget(size, d)
        val counts = HashMap<String, Int>()
        val newCounts = HashMap<String, Int>()
        val chosen = ArrayList<Scored>(size)
        var newTaken = 0
        for (scoredTrack in scored) {
            if (chosen.size >= size) break
            val key = Taste.artistKey(scoredTrack.track.artist)
            if ((counts[key] ?: 0) >= artistCap) continue
            if (scoredTrack.new) {
                if (newTaken >= newSongs) continue
                if ((newCounts[key] ?: 0) >= RELATED_SONGS_PER_ARTIST) continue
            }
            chosen += scoredTrack
            counts[key] = (counts[key] ?: 0) + 1
            if (scoredTrack.new) {
                newTaken++
                newCounts[key] = (newCounts[key] ?: 0) + 1
            }
        }
        // The familiar floor: at least a quarter of the list from the seeds/loved songs, even when
        // novelty scores the discoveries higher. The weakest guesses make way, never a good song.
        val strongTarget = minOf(size / 4, scored.count { it.strong })
        if (strongTarget > 0 && chosen.count { it.strong } < strongTarget) {
            val weak = chosen.filter { !it.strong }.sortedBy { it.score }.toMutableList()
            val better = scored.filter { it.strong && it !in chosen }
            var shortfall = strongTarget - chosen.count { it.strong }
            for (candidate in better) {
                if (shortfall <= 0) break
                if (weak.isEmpty()) break
                val replacement = weak.removeAt(0)
                val candidateKey = Taste.artistKey(candidate.track.artist)
                if ((counts[candidateKey] ?: 0) >= artistCap) continue
                chosen.remove(replacement)
                val replacementKey = Taste.artistKey(replacement.track.artist)
                counts[replacementKey] = (counts[replacementKey] ?: 1) - 1
                if (replacement.new) {
                    newTaken--
                    newCounts[replacementKey] = (newCounts[replacementKey] ?: 1) - 1
                }
                chosen += candidate
                counts[candidateKey] = (counts[candidateKey] ?: 0) + 1
                shortfall--
            }
        }
        // Fill short lists with what the gates already cleared: familiar first, then known artists'
        // finds. A new artist is never added here, so the discovery budget holds however few songs
        // the seeds produced.
        if (chosen.size < size) {
            val have = chosen.mapTo(HashSet()) { recordingKey(it.track.title, it.track.artist) }
            fun fill(pool: List<Scored>) {
                for (scoredTrack in pool) {
                    if (chosen.size >= size) break
                    val rec = recordingKey(scoredTrack.track.title, scoredTrack.track.artist)
                    if (rec in have) continue
                    if (scoredTrack.new) continue
                    val key = Taste.artistKey(scoredTrack.track.artist)
                    if ((counts[key] ?: 0) >= artistCap) continue
                    chosen += scoredTrack
                    have += rec
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
            fill(scored.filter { it.strong })
            fill(scored.filter { !it.new })
        }
        lastPlan = Plan(chosen.filter { it.new }.mapTo(HashSet()) { Taste.artistKey(it.track.artist) })
        return chosen.sortedByDescending { it.score }.map { it.track }
    }

    /**
     * The same songs, reordered so artists and new discoveries are spaced apart: no artist twice in a
     * row, no two new artists in a row and no two takes of one song back to back where the list allows
     * it; it opens on the strongest familiar song when it has one.
     */
    fun spread(tracks: List<Track>): List<Track> {
        if (tracks.size < 2) return tracks
        val newArtists = lastPlan?.newArtists.orEmpty()
        fun artist(track: Track) = Taste.artistKey(track.artist)
        fun isNew(track: Track) = artist(track) in newArtists
        fun sameSong(a: Track, b: Track): Boolean {
            val one = songTitleKey(a.title)
            return one.isNotEmpty() && one == songTitleKey(b.title)
        }
        val left = tracks.toMutableList()
        val out = ArrayList<Track>(tracks.size)
        if (newArtists.isNotEmpty()) {
            val strongestFamiliar = left.indexOfFirst { !isNew(it) }
            if (strongestFamiliar > 0) out += left.removeAt(strongestFamiliar)
        }
        while (left.isNotEmpty()) {
            val last = out.lastOrNull()
            val lastArtist = last?.let(::artist)
            val lastNew = last?.let(::isNew) ?: false
            var index = left.indexOfFirst { track ->
                artist(track) != lastArtist && !(lastNew && isNew(track)) && (last == null || !sameSong(track, last))
            }
            if (index < 0) index = left.indexOfFirst { artist(it) != lastArtist }
            if (index < 0) index = left.indexOfFirst { last == null || !sameSong(it, last) }
            if (index < 0) index = 0
            out += left.removeAt(index)
        }
        return out
    }

    private class Plan(val newArtists: Set<String>)

    private class Scored(val track: Track, val score: Float, val new: Boolean, val strong: Boolean)

    private class RelatedCandidate(
        val key: String,
        val name: String,
        var best: RelatedArtist,
        var rank: Int,
        val seeds: MutableSet<String>,
    )

    /** Efraimidis–Spirakis weighted sampling: each seed can rise, the taste still weighs. */
    private fun weightedOrder(names: List<String>, taste: Taste): List<String> {
        if (names.size < 2) return names
        return names.map { name ->
            val weight = (1.0 + taste.artistScore(name).coerceAtLeast(0f)) * (0.5 + 0.5 * taste.trust(name))
            name to random.nextDouble().pow(1.0 / weight.coerceAtLeast(0.05))
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** Songs a new artist may take of a list of [size] at [d]iscovery: `min(0.5, 0.6 × d)`, floored. */
    private fun newSongBudget(size: Int, d: Float): Int = (size * min(MAX_NEW_SHARE, NEW_SHARE_PER_DISCOVERY * d)).toInt()

    /** A channel's name, not an artist's: a playlist/compilation archive or a word glued to "music". */
    private fun channelish(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        if (CHANNELISH.containsMatchIn(trimmed)) return true
        val lower = trimmed.lowercase()
        return lower.length > 5 && lower.endsWith("music") && !lower.endsWith(" music")
    }

    private suspend fun <T> safe(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    companion object {
        /** One artist's first page hit is 1, the twelfth 0: the quality prior for a page's songs. */
        private const val PER_SEED_SONGS = 12
        private const val MAX_ARTIST_SEEDS = 14
        private const val PER_GENRE_SONGS = 8
        private const val GENRE_HITS = 15
        private const val MAX_GENRES = 8
        private const val MAX_LOVED = 40
        private const val MAX_SIZE = 200

        /** Related artists asked for at most (at full discovery), from the first seeds. */
        private const val RELATED_FROM = 6
        private const val MAX_RELATED = 8
        /** Songs one related artist may contribute. */
        private const val RELATED_SONGS_PER_ARTIST = 2

        /** A single-seed mix (one artist) can only take the first few places of its related list. */
        private const val SINGLE_SEED_MAX_RELATED_RANK = 5

        /** An artist the taste already trusts this much is not a discovery but an old friend. */
        private const val CONFIDENT_TRUST = 0.5f

        /** Discovery 1 may make at most half the list new artists; the share grows with discovery. */
        private const val MAX_NEW_SHARE = 0.5f
        private const val NEW_SHARE_PER_DISCOVERY = 0.6f

        /** A clip shorter than a minute, or an upload longer than twelve: not a song. */
        private const val MIN_SONG_MS = 60_000L
        private const val MAX_SONG_MS = 12 * 60_000L

        /** An upload that is not the song as released: offered only once that very upload was played here. */
        private val DERIVATIVE = Regex(
            """\b(cover|covered by|karaoke|nightcore|slowed|sped ?up|speed ?up|8d|8d audio|type beat|reaction|""" +
                """tutorial|lesson|backing track|1 hour|10 hours?|hour loop|full album|mashup|unreleased|""" +
                """lyric video|audio|visuali[sz]er|teaser|snippet|preview|instrumental|acapella|a cappella|""" +
                """mix ?tape|mixtape|tiktok|soundcloud exclusive)\b""",
        )
        private val CHANNELISH = Regex("""(#\d+|archive|playlist|compilation|records? channel)""", RegexOption.IGNORE_CASE)

        /**
         * Too short, too long or a derivative upload of the song: kept out of generated lists unless
         * this very upload was played here. A cover whose title looks ordinary is caught by
         * [Taste.isCover], so re-uploads cannot ride in on the song's own title.
         */
        fun derivative(track: Track, taste: Taste): Boolean {
            if (taste.played(track)) return false
            if (DERIVATIVE.containsMatchIn(track.title.lowercase())) return true
            if (taste.isCover(track)) return true
            val ms = track.durationMs
            return ms in 1 until MIN_SONG_MS || ms > MAX_SONG_MS
        }
    }
}
