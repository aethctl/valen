/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** An MP3 given an ID3v2.3 tag (title, artist, album, comment, cover) in place of any it had. */
object Id3Tags {

    fun write(input: File, output: File, tags: SongTags, cover: ByteArray?): Boolean {
        RandomAccessFile(input, "r").use { file ->
            val skip = existing(file)
            val frames = ByteArrayOutputStream()
            text(frames, "TIT2", tags.title)
            text(frames, "TPE1", tags.artist)
            text(frames, "TALB", tags.album)
            tags.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                val b = ByteArrayOutputStream()
                b.write(1)
                b.write("eng".toByteArray())
                b.write(utf16("")); b.write(0); b.write(0)
                b.write(utf16(comment))
                frame(frames, "COMM", b.toByteArray())
            }
            cover?.let { image ->
                val png = image.size > 3 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte()
                val b = ByteArrayOutputStream()
                b.write(0)
                b.write((if (png) "image/png" else "image/jpeg").toByteArray())
                b.write(0)
                b.write(3) // front cover
                b.write(0) // no description
                b.write(image)
                frame(frames, "APIC", b.toByteArray())
            }
            val body = frames.toByteArray()
            output.outputStream().buffered().use { out ->
                out.write("ID3".toByteArray())
                out.write(byteArrayOf(3, 0, 0))
                out.write(syncsafe(body.size))
                out.write(body)
                file.seek(skip)
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = file.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        }
        return true
    }

    /** The bytes of an ID3v2 tag at the start of the file, footer included; 0 when there is none. */
    private fun existing(file: RandomAccessFile): Long {
        if (file.length() < 10) return 0
        val head = ByteArray(10)
        file.seek(0)
        file.readFully(head)
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        val size = (head[6].toInt() and 0x7f shl 21) or (head[7].toInt() and 0x7f shl 14) or (head[8].toInt() and 0x7f shl 7) or (head[9].toInt() and 0x7f)
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return (10L + size + footer).coerceAtMost(file.length())
    }

    private fun text(out: ByteArrayOutputStream, id: String, value: String?) {
        if (value.isNullOrBlank()) return
        frame(out, id, byteArrayOf(1) + utf16(value))
    }

    private fun frame(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val n = payload.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(byteArrayOf(0, 0))
        out.write(payload)
    }

    /** UTF-16 with its byte-order mark, as ID3v2.3 wants for anything outside Latin-1. */
    private fun utf16(s: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)

    private fun syncsafe(n: Int): ByteArray =
        byteArrayOf((n ushr 21 and 0x7f).toByte(), (n ushr 14 and 0x7f).toByte(), (n ushr 7 and 0x7f).toByte(), (n and 0x7f).toByte())
}
