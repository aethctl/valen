/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Valen's own writers (a phone's, with no FFmpeg): each file FFmpeg made is re-wrapped, then read
 * back by FFmpeg — the tags and cover there, the audio decoding cleanly to its full length.
 */
class PlainFinisherTest {

    @TempDir
    lateinit var dir: File

    private val ffmpeg by lazy { DiskShelf.tool("ffmpeg", "VALEN_FFMPEG_BIN").also { assumeTrue(it != null, "needs FFmpeg") }!! }
    private val ffprobe by lazy { DiskShelf.tool("ffprobe", "VALEN_FFPROBE_BIN").also { assumeTrue(it != null, "needs ffprobe") }!! }

    private val tags = SongTags("Ünïcode Sóng", "Daft Punk", "Random Access Memories", "Kept by Valen from YouTube Music")

    private fun run(vararg args: String): String {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), out)
        return out
    }

    private fun make(name: String, vararg codec: String): File {
        val out = File(dir, name)
        run(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=f=440:d=6", "-ac", "2", *codec, out.path)
        return out
    }

    private fun cover(): ByteArray {
        val out = File(dir, "cover.jpg")
        run(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=s=300x300:d=1", "-frames:v", "1", out.path)
        return out.readBytes().also { assertTrue(it.size > 512) }
    }

    /** Tag names in lower case: Vorbis comments are case-blind, FFmpeg prints them as written. */
    private fun probe(file: File): String =
        Regex("TAG:([A-Za-z_]+)=").replace(run(ffprobe, "-v", "error", "-show_entries", "format=duration:format_tags:stream=codec_name,codec_type:stream_tags:stream_disposition=attached_pic", "-of", "default=nw=1", file.path)) { "TAG:" + it.groupValues[1].lowercase() + "=" }

    /** FFmpeg decodes the whole file with nothing to say about it. */
    private fun decodesCleanly(file: File) {
        val out = run(ffmpeg, "-v", "error", "-i", file.path, "-f", "null", "-")
        assertEquals("", out.trim(), "decoding said: $out")
    }

    private fun duration(text: String): Double = Regex("duration=([0-9.]+)").find(text)!!.groupValues[1].toDouble()

    @Test
    fun `youtube's webm opus becomes an ogg opus file with tags and cover`() {
        val webm = make("in.webm", "-c:a", "libopus", "-b:a", "160k")
        assertEquals(SongFinisher.Probe("opus", null, "webm"), AudioSniff.probe(webm, 0))
        val out = File(dir, "out.opus")
        assertTrue(PlainFinisher().wrap(webm, out, "opus", tags, cover()))
        val text = probe(out)
        assertTrue("codec_name=opus" in text, text)
        assertTrue("TAG:title=Ünïcode Sóng" in text, text)
        assertTrue("TAG:artist=Daft Punk" in text, text)
        assertTrue("TAG:album=Random Access Memories" in text, text)
        assertEquals(6.0, duration(text), 0.05)
        decodesCleanly(out)
        // The cover rides in the comment header as a picture block FFmpeg shows as a picture.
        assertTrue("attached_pic=1" in text, text)
    }

    @Test
    fun `an m4a gets its tags whether its moov comes first or last`() {
        for (faststart in listOf(true, false)) {
            val m4a = make("in-$faststart.m4a", "-c:a", "aac", "-b:a", "160k", *(if (faststart) arrayOf("-movflags", "+faststart") else emptyArray()))
            val out = File(dir, "out-$faststart.m4a")
            assertTrue(PlainFinisher().wrap(m4a, out, "m4a", tags, cover()), "faststart=$faststart")
            val text = probe(out)
            assertTrue("TAG:title=Ünïcode Sóng" in text, text)
            assertTrue("TAG:album=Random Access Memories" in text, text)
            assertTrue("attached_pic=1" in text, text)
            assertEquals(6.0, duration(text), 0.1)
            decodesCleanly(out)
        }
    }

    @Test
    fun `an mp3 gets an id3 tag in place of the one it had`() {
        val mp3 = make("in.mp3", "-c:a", "libmp3lame", "-b:a", "192k", "-metadata", "title=Old")
        val out = File(dir, "out.mp3")
        assertTrue(PlainFinisher().wrap(mp3, out, "mp3", tags, cover()))
        val text = probe(out)
        assertTrue("TAG:title=Ünïcode Sóng" in text, text)
        assertFalse("TAG:title=Old" in text, text)
        assertTrue("attached_pic=1" in text, text)
        decodesCleanly(out)
    }

    @Test
    fun `a stream it cannot re-wrap is refused, and kept under its own container's name`() {
        val ogg = make("in.ogg", "-c:a", "libvorbis")
        val out = File(dir, "out.ogg")
        assertFalse(PlainFinisher().wrap(ogg, out, "ogg", tags, null))
        assertFalse(out.exists())
        val probe = AudioSniff.probe(ogg, 6_000)
        assertEquals("vorbis", probe.codec)
        assertEquals("ogg", SongFinisher.rawExt(probe))
        assertEquals("webm", SongFinisher.rawExt(SongFinisher.Probe("opus", null, "webm")))
    }

    @Test
    fun `opus packet lengths come from their first byte`() {
        assertEquals(960, OggOpus.samples(byteArrayOf((31 shl 3).toByte())))          // CELT 20 ms
        assertEquals(1920, OggOpus.samples(byteArrayOf(((31 shl 3) or 1).toByte())))  // two of them
        assertEquals(2880, OggOpus.samples(byteArrayOf((3 shl 3).toByte())))          // SILK 60 ms
        assertEquals(4 * 120, OggOpus.samples(byteArrayOf(((16 shl 3) or 3).toByte(), 4)))
    }
}
