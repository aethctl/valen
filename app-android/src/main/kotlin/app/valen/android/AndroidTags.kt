/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.android

import android.media.MediaMetadataRetriever
import app.valen.providers.native.FfprobeTags
import app.valen.providers.native.LocalTag
import app.valen.providers.native.LocalTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * A phone's song file read by Android's own reader: the tags, the length, and the embedded cover
 * (written once per file and change under [covers], shown as a `file://` picture). A file it cannot
 * read still imports, named from its file name.
 */
class AndroidTags(private val covers: File) : LocalTags {

    override suspend fun read(file: File): LocalTag = withContext(Dispatchers.IO) {
        val named = FfprobeTags.fromName(file)
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            fun tag(key: Int) = reader.extractMetadata(key)?.trim()?.ifEmpty { null }
            LocalTag(
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: named.title,
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: named.artist,
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                artworkUrl = cover(file, reader) ?: FfprobeTags.folderCover(file.parentFile),
            )
        } catch (_: Exception) {
            named.copy(artworkUrl = FfprobeTags.folderCover(file.parentFile))
        } finally {
            runCatching { reader.release() }
        }
    }

    private fun cover(file: File, reader: MediaMetadataRetriever): String? {
        val out = File(covers, sha1("${file.absolutePath}|${file.lastModified()}|${file.length()}") + ".img")
        if (out.isFile && out.length() > 0) return out.toURI().toString()
        val picture = runCatching { reader.embeddedPicture }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        covers.mkdirs()
        val part = File(covers, out.name + ".part")
        part.writeBytes(picture)
        if (!part.renameTo(out)) return null
        return out.toURI().toString()
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
