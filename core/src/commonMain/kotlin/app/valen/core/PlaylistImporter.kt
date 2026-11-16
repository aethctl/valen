/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CancellationException

/** A playlist as a service reader found it, before any track is matched or stored. */
data class ReadPlaylist(
    val name: String,
    val tracks: List<Track>,
    val artworkUrl: String? = null,
    /** Something the person should know: a link that only shares part of the list, a capped page. */
    val note: String? = null,
    /**
     * The address this list answered under, when the reader had to resolve it: a short link
     * becomes the playlist's own id, so importing the same list again from any address refreshes
     * one entry instead of adding another.
     */
    val link: PlaylistLink? = null,
    /**
     * Rows the service itself says cannot be played here (greyed out, region-locked). They are
     * still listed so the playlist keeps its shape, but they are matched elsewhere rather than
     * kept as songs that would fail at the first play.
     */
    val blocked: Set<String> = emptySet(),
)

/** How far an import has got; [label] is the song being looked up right now. */
data class ImportProgress(
    val processed: Int = 0,
    val total: Int = 0,
    val matched: Int = 0,
    val label: String = "",
)

/** What an import produced, ready for [PlaylistLibrary.add]. */
data class ImportedPlaylist(
    val name: String,
    val tracks: List<Track>,
    val unmatched: List<UnmatchedEntry> = emptyList(),
    val artworkUrl: String? = null,
    val notes: List<String> = emptyList(),
)

/**
 * Turns a playlist read from one service into a playlist Valen can play.
 *
 * A track on a service Valen can play is kept exactly as it is: no search, no guessing. Only a
 * track that cannot be played as read (a Spotify list while Spotify is signed out, a link to a
 * service no adapter can play) is looked for on the other services, and only a result good
 * enough by [TrackMatcher] is accepted — a wrong song is worse than an honest gap, so the gap is
 * kept in [ImportedPlaylist.unmatched].
 *
 * Matching is one song at a time on purpose: a burst of searches across several services is the
 * pattern a service flags, and a playlist is read once, not on every start.
 */
class PlaylistImporter(
    private val search: suspend (provider: ProviderId, query: String, limit: Int) -> List<Track>,
    private val canPlay: (Track) -> Boolean,
) {
    /** Search answers already seen in this run, so a repeated song costs one search, not two. */
    private val cache = HashMap<String, Track?>()

    /**
     * @param providers the services to look on, in the user's priority order.
     */
    suspend fun import(
        source: ReadPlaylist,
        providers: List<ProviderId>,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportedPlaylist {
        val total = source.tracks.size
        val kept = ArrayList<Track>(total)
        val unmatched = ArrayList<UnmatchedEntry>()
        var processed = 0

        for (track in source.tracks) {
            processed++
            if (canPlay(track) && track.key !in source.blocked) {
                kept += track
            } else {
                val match = find(track, providers)
                if (match != null) {
                    kept += match
                } else {
                    unmatched += UnmatchedEntry(track.title, track.artist)
                }
            }
            // Progress is emitted after the song is decided, so "12 of 300, 11 matched" describes
            // the work actually done rather than the one still in flight.
            onProgress(ImportProgress(processed, total, kept.size, "${track.title} — ${track.artist}"))
        }

        return ImportedPlaylist(
            name = source.name,
            tracks = kept,
            unmatched = unmatched,
            artworkUrl = source.artworkUrl,
            notes = listOfNotNull(source.note),
        )
    }

    /** The first service in priority order that has an acceptable answer wins. */
    private suspend fun find(track: Track, providers: List<ProviderId>): Track? {
        val query = TrackMatcher.query(track)
        if (query.isBlank()) return null
        for (provider in providers) {
            if (provider == track.provider) continue
            val key = cacheKey(provider, query, track.durationMs)
            val hit = if (cache.containsKey(key)) {
                cache[key]
            } else {
                val found = findOn(provider, query)
                val best = TrackMatcher.best(track, found)
                cache[key] = best
                best
            }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * One service's answer. A failing service is an empty answer, not a failed import: the other
     * services may still have the song, and a refusal must not lose the playlist. A cancellation
     * is the caller's and is never swallowed.
     */
    private suspend fun findOn(provider: ProviderId, query: String): List<Track> =
        try {
            search(provider, query, MATCH_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Five-second buckets, as Mochy does: two uploads of one title with lengths either side of a
     * bucket edge still share an answer, and the cache stays small on a long playlist.
     */
    private fun cacheKey(provider: ProviderId, query: String, durationMs: Long): String =
        "${provider.name}|$query|${durationMs.coerceAtLeast(0) / 5_000}"

    private companion object {
        /** Enough to beat a cover buried under the original, small enough not to look like scraping. */
        const val MATCH_LIMIT = 10
    }
}
