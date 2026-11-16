/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What a song file says about itself. */
data class LocalTag(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    /** A picture for the song as a `file://` URI: its embedded cover, or the folder's. */
    val artworkUrl: String? = null,
)

/** Reads a song file's tags. The desktop asks FFmpeg's `ffprobe`; a phone would ask its own reader. */
fun interface LocalTags {
    suspend fun read(file: File): LocalTag
}

/**
 * Tags through `ffprobe` (FFmpeg, already Valen's player on the desktop), the embedded cover
 * through `ffmpeg` into [covers] once per file and change. A machine without FFmpeg still imports:
 * the file's name gives the title (and the artist, for "Artist - Title").
 */
class FfprobeTags(
    private val covers: File,
    private val ffprobe: String? = findTool("ffprobe", "VALEN_FFPROBE_BIN"),
    private val ffmpeg: String? = findTool("ffmpeg", "VALEN_FFMPEG_BIN"),
) : LocalTags {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(file: File): LocalTag = withContext(Dispatchers.IO) {
        val named = fromName(file)
        val probe = ffprobe?.let { runCatching { probe(it, file) }.getOrNull() }
            ?: return@withContext named.copy(artworkUrl = folderCover(file.parentFile))
        val tags = (probe["format"] as? JsonObject)?.get("tags") as? JsonObject
        fun tag(vararg names: String): String? = tags?.entries?.firstOrNull { (k, _) -> names.any { k.equals(it, ignoreCase = true) } }
            ?.value?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
        val seconds = (probe["format"] as? JsonObject)?.get("duration")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
        val embedded = probe["streams"]?.jsonArray?.any { s ->
            val o = s.jsonObject
            o["codec_type"]?.jsonPrimitive?.contentOrNull == "video" &&
                ((o["disposition"] as? JsonObject)?.get("attached_pic")?.jsonPrimitive?.intOrNull == 1)
        } == true
        LocalTag(
            title = tag("title") ?: named.title,
            artist = tag("artist", "album_artist", "albumartist") ?: named.artist,
            album = tag("album"),
            durationMs = (seconds * 1000).toLong(),
            artworkUrl = (if (embedded) embeddedCover(file) else null) ?: folderCover(file.parentFile),
        )
    }

    private fun probe(bin: String, file: File): JsonObject? {
        val process = ProcessBuilder(bin, "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", "file:" + file.absolutePath)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return runCatching { json.parseToJsonElement(out).jsonObject }.getOrNull()
    }

    /** The embedded picture, written once per file and modification under [covers]. */
    private fun embeddedCover(file: File): String? {
        val bin = ffmpeg ?: return null
        val out = File(covers, sha1("${file.absolutePath}|${file.lastModified()}|${file.length()}") + ".img")
        if (out.isFile && out.length() > 0) return out.toURI().toString()
        covers.mkdirs()
        val part = File(covers, out.name + ".part")
        val process = runCatching {
            ProcessBuilder(bin, "-v", "quiet", "-y", "-i", "file:" + file.absolutePath, "-map", "0:v:0", "-frames:v", "1", "-c", "copy", "-f", "image2", part.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrNull() ?: return null
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            part.delete()
            return null
        }
        if (process.exitValue() != 0 || !part.isFile || part.length() == 0L) {
            part.delete()
            return null
        }
        part.renameTo(out)
        return out.toURI().toString()
    }

    companion object {
        /** The names a folder's picture usually has, best first. */
        private val FolderPictures = listOf("cover", "folder", "front", "album", "artwork")
        private val PictureTypes = setOf("jpg", "jpeg", "png", "webp")

        fun folderCover(folder: File?): String? {
            val files = folder?.listFiles()?.filter { it.isFile && it.extension.lowercase() in PictureTypes } ?: return null
            for (name in FolderPictures) {
                files.firstOrNull { it.nameWithoutExtension.equals(name, ignoreCase = true) }?.let { return it.toURI().toString() }
            }
            return null
        }

        /** "Artist - Title.flac" → both; "03 Title.flac" → the title without its number. */
        fun fromName(file: File): LocalTag {
            val base = file.nameWithoutExtension.replace('_', ' ').trim()
            val dash = base.indexOf(" - ")
            val (artist, title) = if (dash > 0) base.substring(0, dash).trim() to base.substring(dash + 3).trim() else null to base
            val untracked = title.replace(Regex("^\\d{1,3}[ .\\-]+"), "").ifBlank { title }
            val cleanArtist = artist?.replace(Regex("^\\d{1,3}[ .\\-]+"), "")?.ifBlank { null }
            return LocalTag(title = untracked, artist = cleanArtist)
        }

        /** `$ENV`, else the program on PATH. */
        fun findTool(name: String, env: String): String? {
            System.getenv(env)?.takeIf { it.isNotBlank() && File(it).canExecute() }?.let { return it }
            return System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { File(it, name) }
                .firstOrNull { it.canExecute() }
                ?.absolutePath
        }

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
