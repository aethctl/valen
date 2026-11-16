/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * An MP4 (`.m4a`) given iTunes-style tags and a cover (`moov/udta/meta/ilst`), the rest of the
 * file copied byte for byte. When the `moov` comes before the audio, the chunk offsets are moved
 * by the size it grew. A fragmented MP4 (HLS, DASH) is refused: it is re-muxed first.
 */
object Mp4Tags {

    private class Box(val type: String, val start: Long, val header: Int, val size: Long) {
        val end get() = start + size
    }

    fun write(input: File, output: File, tags: SongTags, cover: ByteArray?): Boolean {
        RandomAccessFile(input, "r").use { file ->
            val top = boxes(file, 0, file.length())
            if (top.any { it.type == "moof" }) return false
            val moov = top.firstOrNull { it.type == "moov" } ?: return false
            val mdat = top.firstOrNull { it.type == "mdat" } ?: return false
            if (moov.size > MAX_MOOV) return false
            val old = ByteArray(moov.size.toInt()).also { file.seek(moov.start); file.readFully(it) }
            val rebuilt = rebuildMoov(old, ilst(tags, cover))
            val delta = rebuilt.size - old.size
            if (moov.start < mdat.start && delta != 0) shiftOffsets(rebuilt, delta.toLong()) ?: return false
            output.outputStream().buffered().use { out ->
                val buffer = ByteArray(256 * 1024)
                for (box in top) {
                    if (box === moov) {
                        out.write(rebuilt)
                        continue
                    }
                    file.seek(box.start)
                    var left = box.size
                    while (left > 0) {
                        val n = file.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        left -= n
                    }
                }
            }
        }
        return true
    }

    private fun boxes(file: RandomAccessFile, from: Long, to: Long): List<Box> {
        val list = ArrayList<Box>()
        var p = from
        val head = ByteArray(16)
        while (p + 8 <= to) {
            file.seek(p)
            file.readFully(head, 0, 8)
            var size = ByteBuffer.wrap(head, 0, 4).int.toLong() and 0xffffffffL
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                file.readFully(head, 8, 8)
                size = ByteBuffer.wrap(head, 8, 8).long
                header = 16
            } else if (size == 0L) {
                size = to - p
            }
            if (size < header || p + size > to) throw IllegalStateException("bad box $type")
            list += Box(type, p, header, size)
            p += size
        }
        return list
    }

    private fun children(b: ByteArray, from: Int, to: Int): List<Box> {
        val list = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = ByteBuffer.wrap(b, p, 4).int.toLong() and 0xffffffffL
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = ByteBuffer.wrap(b, p + 8, 8).long; header = 16 }
            else if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) throw IllegalStateException("bad box $type")
            list += Box(type, p.toLong(), header, size)
            p += size.toInt()
        }
        return list
    }

    /** The `moov` with its `udta` replaced by one holding Valen's tags. */
    private fun rebuildMoov(moov: ByteArray, ilst: ByteArray): ByteArray {
        val header = if ((ByteBuffer.wrap(moov, 0, 4).int) == 1) 16 else 8
        val body = ByteArrayOutputStream()
        for (c in children(moov, header, moov.size)) {
            if (c.type == "udta") continue
            body.write(moov, c.start.toInt(), c.size.toInt())
        }
        body.write(box("udta", box("meta", ByteArray(4) + box("hdlr", hdlr()) + ilst)))
        return box("moov", body.toByteArray())
    }

    private fun hdlr(): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(ByteArray(4)) // version, flags
        b.write(ByteArray(4)) // pre_defined
        b.write("mdir".toByteArray())
        b.write("appl".toByteArray())
        b.write(ByteArray(8))
        b.write(0) // empty name
        return b.toByteArray()
    }

    private fun ilst(tags: SongTags, cover: ByteArray?): ByteArray {
        val b = ByteArrayOutputStream()
        fun text(type: String, value: String?) {
            if (value.isNullOrBlank()) return
            b.write(box(type, data(1, value.toByteArray(Charsets.UTF_8))))
        }
        text("©nam", tags.title)
        text("©ART", tags.artist)
        text("©alb", tags.album)
        text("©cmt", tags.comment)
        cover?.let {
            val png = it.size > 3 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte()
            b.write(box("covr", data(if (png) 14 else 13, it)))
        }
        return box("ilst", b.toByteArray())
    }

    private fun data(kind: Int, payload: ByteArray): ByteArray =
        box("data", ByteBuffer.allocate(8).putInt(kind).putInt(0).array() + payload)

    private fun box(type: String, payload: ByteArray): ByteArray {
        val bytes = ByteBuffer.allocate(8 + payload.size)
        bytes.putInt(8 + payload.size)
        bytes.put(type.toByteArray(Charsets.ISO_8859_1))
        bytes.put(payload)
        return bytes.array()
    }

    /** Every `stco`/`co64` in the moov moved by [delta]; null when one would overflow. */
    private fun shiftOffsets(moov: ByteArray, delta: Long): Unit? {
        val containers = setOf("moov", "trak", "mdia", "minf", "stbl")
        fun visit(from: Int, to: Int): Boolean {
            for (c in children(moov, from, to)) {
                val s = c.start.toInt()
                val dataStart = s + c.header
                when (c.type) {
                    in containers -> if (!visit(dataStart, (c.start + c.size).toInt())) return false
                    "stco" -> {
                        val n = ByteBuffer.wrap(moov, dataStart + 4, 4).int
                        val buf = ByteBuffer.wrap(moov)
                        for (i in 0 until n) {
                            val at = dataStart + 8 + i * 4
                            val v = (buf.getInt(at).toLong() and 0xffffffffL) + delta
                            if (v > 0xffffffffL || v < 0) return false
                            buf.putInt(at, v.toInt())
                        }
                    }
                    "co64" -> {
                        val n = ByteBuffer.wrap(moov, dataStart + 4, 4).int
                        val buf = ByteBuffer.wrap(moov)
                        for (i in 0 until n) {
                            val at = dataStart + 8 + i * 8
                            buf.putLong(at, buf.getLong(at) + delta)
                        }
                    }
                }
            }
            return true
        }
        return if (visit(0, moov.size)) Unit else null
    }

    private const val MAX_MOOV = 64L * 1024 * 1024
}
