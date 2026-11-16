/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/** The words a kept song carries inside its file. */
data class SongTags(
    val title: String,
    val artist: String,
    val album: String? = null,
    val comment: String? = null,
)

/**
 * Turns a fetched stream into a music file other players read: says what it is ([probe]) and
 * re-wraps it with the tags and the cover ([wrap]), never encoding it again.
 */
interface SongFinisher {

    /** What a fetched file holds: its codec, rate and container ("webm", "mp4", "ogg", …). */
    data class Probe(val codec: String?, val kbps: Int?, val container: String?)

    fun probe(file: File, durationMs: Long = 0L): Probe

    /**
     * [part] as a tagged [ext] file at [out] (a scratch path the caller then moves into place).
     * False when this finisher cannot: the stream is then kept as it came, in its own container.
     */
    fun wrap(part: File, out: File, ext: String, tags: SongTags, cover: ByteArray?): Boolean

    companion object {
        /** FFmpeg when the machine has it, else Valen's own writers. */
        fun default(): SongFinisher {
            val ffmpeg = DiskShelf.tool("ffmpeg", "VALEN_FFMPEG_BIN")
            return if (ffmpeg != null) FfmpegFinisher(ffmpeg, DiskShelf.tool("ffprobe", "VALEN_FFPROBE_BIN")) else PlainFinisher()
        }

        /** The file name ending for a codec once re-wrapped. */
        fun extFor(codec: String?): String? = when (codec) {
            "opus" -> "opus"
            "aac", "alac" -> "m4a"
            "mp3" -> "mp3"
            "vorbis" -> "ogg"
            "flac" -> "flac"
            else -> null
        }

        /** The file name ending for a stream kept exactly as it came. */
        fun rawExt(probe: Probe): String = when (probe.container) {
            "webm" -> "webm"
            "mp4" -> "m4a"
            "ogg" -> if (probe.codec == "opus") "opus" else "ogg"
            "mp3" -> "mp3"
            "flac" -> "flac"
            "adts" -> "aac"
            "ts" -> "ts"
            "matroska" -> "mka"
            else -> extFor(probe.codec) ?: "mka"
        }

        /** Moves a file, copying when the two places are on different disks (a phone's cache and its music). */
        fun move(from: File, to: File) {
            to.parentFile?.mkdirs()
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                try {
                    Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    from.copyTo(to, overwrite = true)
                    from.delete()
                }
            }
        }
    }
}

/**
 * Knows a file by its first bytes: enough for the containers the services send (WebM from
 * YouTube, MP4 or MPEG-TS from SoundCloud's HLS, MP3, Ogg). The rate is the size over the length.
 */
object AudioSniff {
    fun probe(file: File, durationMs: Long): SongFinisher.Probe {
        val head = ByteArray(minOf(file.length(), 256L * 1024).toInt())
        RandomAccessFile(file, "r").use { it.readFully(head) }
        val (container, codec) = kind(head)
        val kbps = if (durationMs > 0) ((file.length() * 8) / durationMs).toInt() else null
        return SongFinisher.Probe(codec, kbps, container)
    }

    fun kind(head: ByteArray): Pair<String?, String?> {
        fun at(i: Int, text: String) = i + text.length <= head.size && text.indices.all { head[i + it] == text[it].code.toByte() }
        fun has(text: String) = indexOf(head, text.toByteArray(Charsets.ISO_8859_1)) >= 0
        return when {
            at(0, "OggS") -> "ogg" to when {
                has("OpusHead") -> "opus"
                has("\u0001vorbis") -> "vorbis"
                has("fLaC") -> "flac"
                else -> null
            }
            at(0, "fLaC") -> "flac" to "flac"
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() ->
                (if (has("webm")) "webm" else "matroska") to when {
                    has("A_OPUS") -> "opus"
                    has("A_VORBIS") -> "vorbis"
                    has("A_AAC") -> "aac"
                    has("A_MPEG/L3") -> "mp3"
                    has("A_FLAC") -> "flac"
                    else -> null
                }
            at(4, "ftyp") || at(4, "styp") -> "mp4" to when {
                has("mp4a") -> "aac"
                has("alac") -> "alac"
                has("Opus") -> "opus"
                has("fLaC") -> "flac"
                else -> null
            }
            at(0, "ID3") -> "mp3" to "mp3"
            head.size > 188 && head[0] == 0x47.toByte() && head[188] == 0x47.toByte() -> "ts" to "aac"
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xF6) == 0xF0 -> "adts" to "aac"
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3" to "mp3"
            else -> null to null
        }
    }

    fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return from
        outer@ for (i in from..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * Valen's own writers, for a machine without FFmpeg (every phone): Opus from WebM into an Ogg
 * file with its tags and cover, an MP4's tags written into it, an MP3 given an ID3 tag. The audio
 * bytes are copied untouched.
 */
open class PlainFinisher : SongFinisher {
    override fun probe(file: File, durationMs: Long): SongFinisher.Probe = AudioSniff.probe(file, durationMs)

    override fun wrap(part: File, out: File, ext: String, tags: SongTags, cover: ByteArray?): Boolean {
        val probe = AudioSniff.probe(part, 0L)
        return runCatching {
            when {
                ext == "opus" && probe.container == "webm" || ext == "opus" && probe.container == "matroska" ->
                    OggOpus.fromMatroska(part, out, tags, cover)
                ext == "m4a" && probe.container == "mp4" -> Mp4Tags.write(part, out, tags, cover)
                ext == "mp3" && probe.container == "mp3" -> Id3Tags.write(part, out, tags, cover)
                else -> false
            }
        }.getOrDefault(false).also { if (!it) out.delete() }
    }
}

/** FFmpeg's own re-wrap (`-c copy`) and `ffprobe`'s reading, on a desktop that has them. */
class FfmpegFinisher(private val ffmpeg: String, private val ffprobe: String?) : SongFinisher {

    override fun probe(file: File, durationMs: Long): SongFinisher.Probe {
        val bin = ffprobe ?: return AudioSniff.probe(file, durationMs)
        return runCatching {
            val p = ProcessBuilder(bin, "-v", "quiet", "-select_streams", "a:0", "-show_entries", "stream=codec_name,bit_rate:format=format_name,bit_rate", "-of", "default=nw=1", "file:" + file.absolutePath)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(15, TimeUnit.SECONDS)
            val values = out.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 } }.groupBy({ it[0] }, { it[1] })
            val bitRate = values["bit_rate"]?.firstNotNullOfOrNull { it.toLongOrNull() }
            val sniffed = AudioSniff.probe(file, durationMs)
            SongFinisher.Probe(
                codec = values["codec_name"]?.firstOrNull() ?: sniffed.codec,
                kbps = bitRate?.let { (it / 1000).toInt() } ?: sniffed.kbps,
                // ffprobe names WebM "matroska,webm"; the first bytes tell the two apart.
                container = sniffed.container ?: values["format_name"]?.firstOrNull()?.substringBefore(','),
            )
        }.getOrElse { AudioSniff.probe(file, durationMs) }
    }

    /** Tags and the cover, by re-wrapping (`-c copy`); Opus keeps its cover as a picture tag. */
    override fun wrap(part: File, out: File, ext: String, tags: SongTags, cover: ByteArray?): Boolean {
        val meta = File(part.parentFile, part.name + ".meta")
        val coverFile = cover?.let { File(part.parentFile, part.name + ".cover").apply { writeBytes(it) } }
        try {
            val values = linkedMapOf(
                "title" to tags.title,
                "artist" to tags.artist,
                "album" to (tags.album ?: ""),
                "comment" to (tags.comment ?: ""),
            )
            if (ext == "opus" || ext == "ogg" || ext == "flac") {
                cover?.let { values["METADATA_BLOCK_PICTURE"] = DiskShelf.pictureBlock(it) }
            }
            meta.writeText(";FFMETADATA1\n" + values.filterValues { it.isNotBlank() }.entries.joinToString("") { (k, v) -> "$k=${escapeMeta(v)}\n" })
            val command = mutableListOf(ffmpeg, "-v", "error", "-y", "-i", "file:" + part.absolutePath, "-f", "ffmetadata", "-i", meta.absolutePath)
            val picture = coverFile != null && (ext == "m4a" || ext == "mp3")
            if (picture) command += listOf("-i", "file:" + coverFile!!.absolutePath)
            command += listOf("-map", "0:a:0", "-map_metadata", "1")
            if (picture) command += listOf("-map", "2:0", "-disposition:v:0", "attached_pic")
            command += listOf("-c", "copy")
            command += when (ext) {
                "m4a" -> listOf("-movflags", "+faststart", "-f", "ipod")
                "mp3" -> listOf("-id3v2_version", "3", "-f", "mp3")
                "opus" -> listOf("-f", "opus")
                "ogg" -> listOf("-f", "ogg")
                "flac" -> listOf("-f", "flac")
                else -> listOf("-f", "matroska")
            }
            command += out.absolutePath
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            val done = process.waitFor(2, TimeUnit.MINUTES)
            if (!done) process.destroyForcibly()
            if (!done || process.exitValue() != 0 || !out.isFile || out.length() < DiskAudioCache.MIN_BYTES) {
                out.delete()
                return false
            }
            return true
        } finally {
            meta.delete()
            coverFile?.delete()
        }
    }

    private fun escapeMeta(v: String): String =
        v.replace("\\", "\\\\").replace("=", "\\=").replace(";", "\\;").replace("#", "\\#").replace("\n", "\\\n")
}
