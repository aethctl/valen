/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

/**
 * Songs and playlists on this device. A local song is an ordinary [Track] on [ProviderId.LOCAL]
 * whose id is the file's absolute path, so it sits in any playlist beside songs from the services
 * and plays through the same queue.
 *
 * Everything here is text: core has no file access. The platform reads the files, the tags and
 * the folders (`providers/native` `LocalProvider`).
 */

/** The audio files Valen reads from a folder or a playlist, by extension (lower case). */
val LocalAudioExtensions: Set<String> = setOf(
    "mp3", "flac", "ogg", "oga", "opus", "m4a", "mp4", "aac", "alac", "wav", "wv", "ape", "aiff", "aif", "mka", "webm",
)

/** Playlist files Valen can import. */
val LocalPlaylistExtensions: Set<String> = setOf("m3u", "m3u8", "pls", "xspf")

/** One line of a playlist file: where the song is, and what the file said about it. */
data class LocalEntry(
    /** An absolute path (relative entries are resolved against the playlist's folder). */
    val path: String,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long = 0L,
)

/** A playlist file read: its own name when it gave one, and its entries in order. */
data class LocalPlaylistText(val name: String?, val entries: List<LocalEntry>)

/**
 * Reads an M3U/M3U8 (extended or plain), PLS or XSPF playlist. [folder] is the playlist's own
 * folder (absolute), which relative entries are read against. Web addresses (a radio stream) are
 * not songs and are left out.
 */
fun parseLocalPlaylist(text: String, folder: String, extension: String): LocalPlaylistText {
    val clean = text.removePrefix("﻿")
    return when (extension.lowercase()) {
        "pls" -> parsePls(clean, folder)
        "xspf" -> parseXspf(clean, folder)
        else -> parseM3u(clean, folder)
    }
}

private fun parseM3u(text: String, folder: String): LocalPlaylistText {
    var name: String? = null
    var pending: Triple<String?, String?, Long>? = null
    val entries = ArrayList<LocalEntry>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        when {
            line.startsWith("#PLAYLIST:", ignoreCase = true) -> name = line.substringAfter(':').trim().ifEmpty { null }
            line.startsWith("#EXTINF:", ignoreCase = true) -> pending = extinf(line.substringAfter(':'))
            line.startsWith("#") -> Unit
            else -> {
                val path = entryPath(line, folder)
                if (path != null) {
                    val (artist, title, seconds) = pending ?: Triple(null, null, 0L)
                    entries += LocalEntry(path, title, artist, seconds.coerceAtLeast(0) * 1000)
                }
                pending = null
            }
        }
    }
    return LocalPlaylistText(name, entries)
}

/** `#EXTINF:215 tvg-x="…",Artist - Title` → (artist, title, seconds). */
private fun extinf(rest: String): Triple<String?, String?, Long> {
    val comma = rest.indexOf(',')
    val head = if (comma >= 0) rest.substring(0, comma) else rest
    val label = if (comma >= 0) rest.substring(comma + 1).trim() else ""
    val seconds = head.trim().substringBefore(' ').toLongOrNull() ?: 0L
    val dash = label.indexOf(" - ")
    return if (dash > 0) {
        Triple(label.substring(0, dash).trim().ifEmpty { null }, label.substring(dash + 3).trim().ifEmpty { null }, seconds)
    } else {
        Triple(null, label.ifEmpty { null }, seconds)
    }
}

private fun parsePls(text: String, folder: String): LocalPlaylistText {
    val files = HashMap<Int, String>()
    val titles = HashMap<Int, String>()
    val lengths = HashMap<Int, Long>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq).trim().lowercase()
        val value = line.substring(eq + 1).trim()
        val n = key.dropWhile { it.isLetter() }.toIntOrNull() ?: continue
        when {
            key.startsWith("file") -> files[n] = value
            key.startsWith("title") -> titles[n] = value
            key.startsWith("length") -> value.toLongOrNull()?.let { lengths[n] = it }
        }
    }
    val entries = files.keys.sorted().mapNotNull { n ->
        val path = entryPath(files.getValue(n), folder) ?: return@mapNotNull null
        val label = titles[n].orEmpty()
        val dash = label.indexOf(" - ")
        LocalEntry(
            path = path,
            title = (if (dash > 0) label.substring(dash + 3) else label).trim().ifEmpty { null },
            artist = if (dash > 0) label.substring(0, dash).trim().ifEmpty { null } else null,
            durationMs = (lengths[n] ?: 0L).coerceAtLeast(0) * 1000,
        )
    }
    return LocalPlaylistText(null, entries)
}

private fun parseXspf(text: String, folder: String): LocalPlaylistText {
    fun tag(block: String, name: String): String? =
        Regex("<$name>([\\s\\S]*?)</$name>").find(block)?.groupValues?.get(1)?.let(::xmlText)?.trim()?.ifEmpty { null }
    val trackList = text.substringAfter("<trackList>", "")
    val name = tag(text.substringBefore("<trackList>"), "title")
    val entries = Regex("<track>([\\s\\S]*?)</track>").findAll(trackList).mapNotNull { m ->
        val block = m.groupValues[1]
        val location = tag(block, "location") ?: return@mapNotNull null
        val path = entryPath(location, folder) ?: return@mapNotNull null
        LocalEntry(
            path = path,
            title = tag(block, "title"),
            artist = tag(block, "creator"),
            durationMs = tag(block, "duration")?.toLongOrNull() ?: 0L,
        )
    }.toList()
    return LocalPlaylistText(name, entries)
}

private fun xmlText(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

/**
 * A playlist entry as an absolute path: `file://` addresses decoded, `~/` left for the platform,
 * relative paths read against [folder], Windows separators turned round. A web address is null.
 */
fun entryPath(entry: String, folder: String): String? {
    val text = entry.trim()
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("rtsp://") || lower.startsWith("mms://")) return null
    if (lower.startsWith("file://")) {
        val rest = text.substring(7)
        // file:///home/… and file://localhost/home/…
        val path = if (rest.startsWith("/")) rest else "/" + rest.substringAfter('/')
        return normalisePath(percentDecode(path))
    }
    val slashes = text.replace('\\', '/')
    if (slashes.startsWith("/") || slashes.startsWith("~/")) return normalisePath(slashes)
    // A drive letter is another machine's path; kept as written, so the importer can still match it.
    if (slashes.length > 2 && slashes[1] == ':' && slashes[2] == '/') return slashes
    return normalisePath(folder.trimEnd('/') + "/" + slashes)
}

/** `a/./b/../c` → `a/c`, with one slash between parts. */
fun normalisePath(path: String): String {
    val absolute = path.startsWith("/")
    val parts = ArrayList<String>()
    for (part in path.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeAt(parts.lastIndex) else if (!absolute) parts += part
            else -> parts += part
        }
    }
    return (if (absolute) "/" else "") + parts.joinToString("/")
}

private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 <= s.lastIndex) {
            val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes += hex.toByte()
                i += 3
                continue
            }
        }
        c.toString().encodeToByteArray().forEach { bytes += it }
        i++
    }
    return bytes.toByteArray().decodeToString()
}

/** The file's name without its folder or extension: a title when the file has no tags. */
fun fileTitle(path: String): String = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { path }

/** Whether [path]'s extension is one Valen plays. */
fun isAudioPath(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in LocalAudioExtensions

/** Whether [text] looks like a place on this device rather than a web address. */
fun looksLikeLocalPath(text: String): Boolean {
    val t = text.trim()
    return t.startsWith("/") || t.startsWith("~/") || t.startsWith("file://", ignoreCase = true)
}

/** A playlist (`m3u`, `pls`, …) or folder on this device, as an import link. */
fun localPlaylistLink(path: String): PlaylistLink {
    val clean = if (path.startsWith("file://", ignoreCase = true)) entryPath(path, "/") ?: path else path.trim()
    return PlaylistLink(ProviderId.LOCAL, clean, clean)
}
