/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

/**
 * Collapses the same recording listed twice by a service (an album upload and a single, or two
 * catalogue entries with the same name): one row per recording, the first kept. A remix, a live
 * take or an edit says so in its title and stays a separate row.
 *
 * Only search answers go through this; a queue built by hand keeps every row it was given.
 */
fun List<Track>.distinctRecordings(): List<Track> =
    distinctBy { recordingKey(it.title, it.artist) }

/**
 * The identity of a recording, independent of the service that lists it.
 *
 * The three catalogues spell the same song differently: "Shape of You", "Shape of You (Official
 * Video)", "Shape of You - Remastered 2011", "Shape of You (feat. …)". Marketing noise is dropped,
 * the artist is reduced to the one who leads the record, and a genuine alternate take (live, remix,
 * acoustic, instrumental, demo, edit, slowed…) is kept as its own key so it never merges with the
 * studio version.
 */
fun recordingKey(title: String, artist: String): String =
    "${normaliseTitle(title)}|${normaliseArtist(artist)}"

/**
 * The song behind a title: marketing and take-words aside. "Song (slowed)", "Song - Live" and
 * "Song (Official Audio)" all come back as "song", so a cover can be recognised by its title even
 * when it never says "cover". Used for generated lists only, never to merge a queue.
 */
fun songTitleKey(title: String): String =
    normaliseTitle(title).split(' ').filter { it.isNotBlank() && !VERSIONS.containsMatchIn(it) }.joinToString(" ")

/** Words that mark a different take rather than a different song. */
private val VERSIONS = Regex(
    """\b(live|remix|rmx|acoustic|instrumental|demo|edit|reprise|sped|slowed|nightcore|karaoke|cover|unplugged)\b""",
)

/** Words that only advertise the upload, never the recording. */
private val NOISE = setOf(
    "official", "officiel", "music", "video", "videoclip", "audio", "lyric", "lyrics", "letra",
    "visualizer", "visualiser", "hd", "hq", "uhd", "4k", "8k", "remaster", "remastered", "explicit",
    "clean", "stereo", "mono", "clip", "full", "song", "album", "single", "version",
)

private val BRACKETED = Regex("""[\(\[\{]([^\)\]\}]*)[\)\]\}]""")
private val FEATURING = Regex("""\b(feat|feat\.|ft|ft\.|featuring)\b.*$""")
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
private val SPACES = Regex("""\s+""")
private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")

private fun normaliseTitle(raw: String): String {
    var text = raw.lowercase().replace('’', '\'')
    // "(Official Video)" is advertising; "(Live at Wembley)" is a different recording.
    text = BRACKETED.replace(text) { match ->
        val inner = match.groupValues[1]
        if (VERSIONS.containsMatchIn(inner)) " ${significantWords(inner)} " else " "
    }
    // "Song - Official Music Video", "Song - Remastered 2011": the tail is noise. "Song - Live"
    // is a version and stays.
    text = text.split(" - ")
        .filterIndexed { index, part -> index == 0 || !isNoise(part) }
        .joinToString(" ")
    text = FEATURING.replace(text, " ")
    return text.replace(NON_WORD, " ").replace(SPACES, " ").trim()
}

private fun normaliseArtist(raw: String): String {
    // A featured artist is not part of the lead artist: "Calvin Harris, Dua Lipa" and
    // "Calvin Harris" are the same record for the purpose of this key.
    val lead = raw.lowercase()
        .replace('’', '\'')
        .substringBefore(" feat")
        .substringBefore(" ft.")
        .substringBefore(" featuring")
        .split(Regex(""",|&|\sx\s"""))
        .first()
    return lead.replace(NON_WORD, " ").replace(SPACES, " ").trim()
}

private fun isNoise(part: String): Boolean {
    val words = part.split(NON_WORD).filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    if (VERSIONS.containsMatchIn(part)) return false
    return words.all { it in NOISE || YEAR.matches(it) }
}

private fun significantWords(text: String): String =
    text.split(NON_WORD).filter { it.isNotBlank() && it !in NOISE }.joinToString(" ")
