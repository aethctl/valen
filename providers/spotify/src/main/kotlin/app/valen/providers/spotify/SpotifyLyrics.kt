/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.spotify

import app.myspoti.api.Lyrics as SpotifyLyrics
import app.myspoti.api.LyricsLine as SpotifyLine
import app.myspoti.api.LyricsWord as SpotifyWord
import app.myspoti.spotify.SpotifyApi
import app.valen.core.LyricLine
import app.valen.core.LyricWord
import app.valen.core.Lyrics
import app.valen.core.LyricsFormats
import app.valen.core.LyricsQuery
import app.valen.core.recordingKey
import kotlin.math.abs

/*
 * Spotify's own lyrics (the card under its player), as a source after Lyprio and before LRCLIB.
 * Asked only for a signed-in account whose Web Player is already up: a lyrics lookup never starts
 * a browser or loads the page. A song from another service is matched to its Spotify copy first.
 */

/** The song's Spotify id: its own when it plays from Spotify, otherwise the same recording found by search. */
internal suspend fun SpotifyApi.copyOf(query: LyricsQuery): String? {
    query.spotifyId?.let { return it }
    val wanted = recordingKey(query.title, query.artist)
    val found = search.tracks("${query.title} ${query.artist}", 0, 8).items.mapNotNull { it.toValenTrack() }
    val length = query.durationMs
    return found.firstOrNull { t ->
        val close = length == null || t.durationMs <= 0 || abs(t.durationMs - length) <= LENGTH_SLACK_MS
        close && (recordingKey(t.title, t.artist) == wanted ||
            (recordingKey(t.title, "") == recordingKey(query.title, "") && LyricsFormats.artistsMatch(query.artist, t.artist)))
    }?.id
}

/**
 * Spotify's card read into Valen's lyrics. Its "♪" lines mark where the singing stops, so they
 * end the line before them instead of being shown; syllable timing, where Spotify has it, becomes
 * word timing when the syllables spell the line.
 */
internal fun SpotifyLyrics.toValen(durationMs: Long?): Lyrics? {
    val credit = provider?.trim()?.takeIf { it.isNotEmpty() && !it.equals("spotify", ignoreCase = true) }
        ?.let { if (it.equals("musixmatch", ignoreCase = true)) "Musixmatch" else it }
    val source = listOfNotNull("Spotify", credit).joinToString(" · ")
    val out = mutableListOf<LyricLine>()
    for (line in lines) {
        val text = line.text.trim()
        val start = if (synced) line.startMs else 0L
        if (text.isEmpty() || text == "♪") {
            if (synced) out.lastOrNull()?.takeIf { it.endMs == 0L && start > it.startMs }?.let { out[out.lastIndex] = it.copy(endMs = start) }
            continue
        }
        val end = if (synced) line.endMs?.takeIf { it > start } ?: 0L else 0L
        out += LyricLine(start, end, text, if (synced) syllables(line, text) else emptyList())
    }
    return LyricsFormats.fromLines(out, source, durationMs)
}

/** The syllables as words, each to the next; none when they do not spell [text]. */
private fun syllables(line: SpotifyLine, text: String): List<LyricWord> {
    val parts: List<SpotifyWord> = line.words?.filter { it.text.isNotEmpty() } ?: return emptyList()
    if (parts.isEmpty()) return emptyList()
    val words = mutableListOf<LyricWord>()
    var at = 0
    for ((i, part) in parts.withIndex()) {
        while (at < text.length && text[at] == ' ') at++
        val piece = part.text.trim()
        if (!text.startsWith(piece, at)) return emptyList()
        at += piece.length
        val end = parts.getOrNull(i + 1)?.startMs ?: line.endMs?.takeIf { it > part.startMs } ?: (part.startMs + LAST_SYLLABLE_MS)
        words += LyricWord(piece, part.startMs, end.coerceAtLeast(part.startMs), space = at < text.length && text[at] == ' ')
    }
    return if (at >= text.trimEnd().length) words else emptyList()
}

private const val LENGTH_SLACK_MS = 4_000L
private const val LAST_SYLLABLE_MS = 900L
