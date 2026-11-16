/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.android

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import app.valen.audio.AudioSniff
import app.valen.audio.Mp4Tags
import app.valen.audio.PlainFinisher
import app.valen.audio.SongTags
import java.io.File
import java.nio.ByteBuffer

/**
 * A phone's finisher: Valen's own writers, and Android's muxer for what they cannot take as it
 * comes — SoundCloud's AAC arrives as HLS (MPEG-TS or fragmented MP4 segments) and is re-muxed
 * into a plain MP4 (the packets copied, nothing encoded) before its tags are written.
 */
class AndroidFinisher : PlainFinisher() {

    override fun wrap(part: File, out: File, ext: String, tags: SongTags, cover: ByteArray?): Boolean {
        if (super.wrap(part, out, ext, tags, cover)) return true
        if (ext != "m4a") return false
        val container = AudioSniff.probe(part, 0L).container
        if (container !in setOf("mp4", "ts", "adts")) return false
        val plain = File(part.parentFile, part.name + ".mp4")
        return try {
            remux(part, plain) && Mp4Tags.write(plain, out, tags, cover)
        } catch (_: Exception) {
            out.delete()
            false
        } finally {
            plain.delete()
        }
    }

    /** The first audio track's samples into a new MP4, as they are. */
    private fun remux(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return false
            extractor.selectTrack(index)
            val m = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val track = m.addTrack(format)
            m.start()
            val buffer = ByteBuffer.allocate(
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024) else 256 * 1024,
            )
            val info = MediaCodec.BufferInfo()
            var written = 0
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(track, buffer, info)
                written++
                extractor.advance()
            }
            m.stop()
            return written > 0 && output.length() > 0
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
