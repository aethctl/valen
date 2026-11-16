/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Opus taken out of a WebM (Matroska) file and written as an Ogg Opus file (`.opus`, RFC 7845),
 * with its tags and cover in the OpusTags header: what YouTube's best stream becomes on a phone,
 * where there is no FFmpeg. The packets are copied as they are.
 */
object OggOpus {

    /** Writes [input]'s first Opus track to [output]; false when it holds none this can read. */
    fun fromMatroska(input: File, output: File, tags: SongTags, cover: ByteArray?): Boolean {
        if (input.length() > MAX_INPUT) return false
        val track = Matroska.opusTrack(input.readBytes()) ?: return false
        if (track.packets.isEmpty()) return false
        val head = track.head ?: opusHead(track.channels, track.preSkip, track.rate)
        output.outputStream().buffered().use { out ->
            val ogg = OggWriter(out, serial = (System.nanoTime() and 0x7fffffff).toInt())
            ogg.packet(head, granule = 0, flush = true)
            ogg.packet(opusTags(tags, cover), granule = 0, flush = true)
            var granule = 0L
            track.packets.forEachIndexed { i, packet ->
                granule += samples(packet)
                ogg.packet(packet, granule, last = i == track.packets.lastIndex)
            }
        }
        return output.length() > 0
    }

    /** The OpusHead packet for a track that did not carry one. */
    fun opusHead(channels: Int, preSkip: Int, rate: Int): ByteArray {
        val b = ByteArrayOutputStream()
        b.write("OpusHead".toByteArray())
        b.write(1)
        b.write(channels.coerceIn(1, 2))
        le16(b, preSkip)
        le32(b, rate)
        le16(b, 0)
        b.write(0)
        return b.toByteArray()
    }

    /** The comment header: Valen as the vendor, the words, and the cover as a picture block. */
    fun opusTags(tags: SongTags, cover: ByteArray?): ByteArray {
        val comments = buildList {
            add("TITLE=${tags.title}")
            if (tags.artist.isNotBlank()) add("ARTIST=${tags.artist}")
            tags.album?.takeIf { it.isNotBlank() }?.let { add("ALBUM=$it") }
            tags.comment?.takeIf { it.isNotBlank() }?.let { add("COMMENT=$it") }
            cover?.let { add("METADATA_BLOCK_PICTURE=" + DiskShelf.pictureBlock(it)) }
        }
        val b = ByteArrayOutputStream()
        b.write("OpusTags".toByteArray())
        val vendor = "Valen".toByteArray()
        le32(b, vendor.size)
        b.write(vendor)
        le32(b, comments.size)
        for (c in comments) {
            val bytes = c.toByteArray(Charsets.UTF_8)
            le32(b, bytes.size)
            b.write(bytes)
        }
        return b.toByteArray()
    }

    /** How many 48 kHz samples an Opus packet holds, from its table-of-contents byte (RFC 6716 §3.1). */
    fun samples(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xff
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
            config < 16 -> intArrayOf(480, 960)[config % 2]
            else -> intArrayOf(120, 240, 480, 960)[config % 4]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3f else 0
        }
        return frame * frames
    }

    private fun le16(b: ByteArrayOutputStream, v: Int) { b.write(v and 0xff); b.write(v ushr 8 and 0xff) }
    private fun le32(b: ByteArrayOutputStream, v: Int) { for (i in 0 until 4) b.write(v ushr (8 * i) and 0xff) }

    private const val MAX_INPUT = 400L * 1024 * 1024
}

/** Ogg pages around packets (RFC 3533): a packet may run over several pages. */
class OggWriter(private val out: OutputStream, private val serial: Int) {
    private var sequence = 0
    private var first = true
    private val body = ByteArrayOutputStream()
    private val lacing = ByteArrayOutputStream()
    private var segments = 0
    private var granule = -1L
    private var continued = false

    /**
     * One packet whose last sample is [granule]. [flush] ends the page after it (the headers each
     * end theirs); [last] marks the stream's end.
     */
    fun packet(data: ByteArray, granule: Long, flush: Boolean = false, last: Boolean = false) {
        var offset = 0
        var remaining = data.size
        while (true) {
            if (segments == 255) page(eos = false, packetEnded = false)
            val take = minOf(255, remaining)
            lacing.write(take)
            segments++
            body.write(data, offset, take)
            offset += take
            remaining -= take
            if (take < 255) break
            if (remaining == 0) {
                // A packet that fills its last segment exactly ends with an empty one.
                if (segments == 255) page(eos = false, packetEnded = false)
                lacing.write(0)
                segments++
                break
            }
        }
        this.granule = granule
        if (flush || last || body.size() >= PAGE_BYTES) page(eos = last, packetEnded = true)
    }

    private fun page(eos: Boolean, packetEnded: Boolean) {
        if (segments == 0) return
        val header = ByteArrayOutputStream()
        header.write("OggS".toByteArray())
        header.write(0)
        var type = 0
        if (continued) type = type or 1
        if (first) type = type or 2
        if (eos) type = type or 4
        header.write(type)
        // A page on which no packet ends has no position of its own.
        val position = if (packetEnded) granule else -1L
        for (i in 0 until 8) header.write((position ushr (8 * i) and 0xff).toInt())
        for (i in 0 until 4) header.write(serial ushr (8 * i) and 0xff)
        for (i in 0 until 4) header.write(sequence ushr (8 * i) and 0xff)
        header.write(byteArrayOf(0, 0, 0, 0))
        header.write(segments)
        header.write(lacing.toByteArray())
        val page = header.toByteArray() + body.toByteArray()
        val crc = crc(page)
        page[22] = (crc and 0xff).toByte()
        page[23] = (crc ushr 8 and 0xff).toByte()
        page[24] = (crc ushr 16 and 0xff).toByte()
        page[25] = (crc ushr 24 and 0xff).toByte()
        out.write(page)
        sequence++
        first = false
        continued = !packetEnded
        body.reset()
        lacing.reset()
        segments = 0
    }

    companion object {
        private const val PAGE_BYTES = 4_096

        private val TABLE = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
            r
        }

        fun crc(data: ByteArray): Int {
            var c = 0
            for (b in data) c = (c shl 8) xor TABLE[((c ushr 24) xor (b.toInt() and 0xff)) and 0xff]
            return c
        }
    }
}

/** Just enough Matroska (EBML) to take one Opus track's packets out of a WebM file. */
object Matroska {

    class OpusTrack(
        val head: ByteArray?,
        val channels: Int,
        val rate: Int,
        val preSkip: Int,
        val packets: List<ByteArray>,
    )

    private const val SEGMENT = 0x18538067L
    private const val CLUSTER = 0x1F43B675L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val CODEC_ID = 0x86L
    private const val CODEC_PRIVATE = 0x63A2L
    private const val CODEC_DELAY = 0x56AAL
    private const val AUDIO = 0xE1L
    private const val CHANNELS = 0x9FL
    private const val SAMPLING = 0xB5L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L

    /** The elements found directly in a Segment: one of them ends a Cluster of unknown size. */
    private val TOP = setOf(CLUSTER, TRACKS, 0x1549A966L, 0x114D9B74L, 0x1C53BB6BL, 0x1254C367L, 0x1941A469L, 0x1043A770L)

    private class Reader(val b: ByteArray) {
        var p = 0

        fun id(): Long {
            val first = b[p].toInt() and 0xff
            val len = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (len !in 1..4) throw IllegalStateException("bad id")
            var v = 0L
            repeat(len) { v = (v shl 8) or (b[p++].toLong() and 0xff) }
            return v
        }

        /** A size; -1 when the element says its size is unknown. */
        fun size(): Long {
            val first = b[p].toInt() and 0xff
            val len = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (len !in 1..8) throw IllegalStateException("bad size")
            var v = (first and (0xff ushr len)).toLong()
            var allOnes = v == (0xff ushr len).toLong()
            p++
            repeat(len - 1) {
                val x = b[p++].toLong() and 0xff
                if (x != 0xffL) allOnes = false
                v = (v shl 8) or x
            }
            return if (allOnes) -1 else v
        }

        fun uint(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or (b[p++].toLong() and 0xff) }; return v }
    }

    fun opusTrack(bytes: ByteArray): OpusTrack? {
        val r = Reader(bytes)
        var opusNumber = -1L
        var head: ByteArray? = null
        var channels = 2
        var rate = 48_000
        var preSkip = 312
        val packets = ArrayList<ByteArray>()

        fun block(start: Int, end: Int) {
            val br = Reader(bytes).also { it.p = start }
            val number = br.size()
            br.p += 2 // timecode
            val flags = bytes[br.p++].toInt() and 0xff
            if (number != opusNumber) return
            val frames = frames(bytes, br.p, end, (flags shr 1) and 3) ?: return
            frames.forEach { (s, len) -> packets += bytes.copyOfRange(s, s + len) }
        }

        fun trackEntry(start: Int, end: Int) {
            var number = -1L
            var codec: String? = null
            var priv: ByteArray? = null
            var delayNs = -1L
            var ch = 2
            var sr = 48_000.0
            r.p = start
            while (r.p < end) {
                val id = r.id()
                val size = r.size()
                val dataStart = r.p
                val dataEnd = (dataStart + size).toInt()
                when (id) {
                    TRACK_NUMBER -> number = r.uint(size.toInt())
                    CODEC_ID -> codec = String(bytes, dataStart, size.toInt(), Charsets.US_ASCII).trimEnd('\u0000')
                    CODEC_PRIVATE -> priv = bytes.copyOfRange(dataStart, dataEnd)
                    CODEC_DELAY -> delayNs = r.uint(size.toInt())
                    AUDIO -> {
                        val ar = Reader(bytes).also { it.p = dataStart }
                        while (ar.p < dataEnd) {
                            val aid = ar.id()
                            val asz = ar.size().toInt()
                            when (aid) {
                                CHANNELS -> ch = ar.uint(asz).toInt()
                                SAMPLING -> sr = if (asz == 4) java.lang.Float.intBitsToFloat(ar.uint(4).toInt()).toDouble()
                                else java.lang.Double.longBitsToDouble(ar.uint(8))
                                else -> ar.p += asz
                            }
                            ar.p = ar.p.coerceAtMost(dataEnd)
                        }
                    }
                }
                r.p = dataEnd
            }
            if (codec == "A_OPUS" && opusNumber < 0) {
                opusNumber = number
                head = priv?.takeIf { it.size >= 19 && String(it, 0, 8, Charsets.US_ASCII) == "OpusHead" }
                channels = ch
                rate = sr.toInt().takeIf { it > 0 } ?: 48_000
                if (delayNs >= 0) preSkip = (delayNs * 48_000 / 1_000_000_000L).toInt()
                head?.let { preSkip = (it[10].toInt() and 0xff) or ((it[11].toInt() and 0xff) shl 8) }
            }
        }

        /** Walks [start, end); an unknown size runs until the next top-level element. */
        fun walk(start: Int, end: Int, inCluster: Boolean) {
            r.p = start
            while (r.p < end && r.p < bytes.size - 1) {
                val at = r.p
                val id = r.id()
                if (inCluster && id in TOP) { r.p = at; return }
                val size = r.size()
                val dataStart = r.p
                val dataEnd = if (size < 0) end else minOf(end.toLong(), dataStart + size).toInt()
                when (id) {
                    SEGMENT -> { walk(dataStart, dataEnd, false); if (size < 0) return }
                    TRACKS -> {
                        val tr = Reader(bytes).also { it.p = dataStart }
                        while (tr.p < dataEnd) {
                            val tid = tr.id()
                            val tsz = tr.size()
                            val ts = tr.p
                            val te = (ts + tsz).toInt()
                            if (tid == TRACK_ENTRY) {
                                val keep = r.p
                                trackEntry(ts, te)
                                r.p = keep
                            }
                            tr.p = te
                        }
                        r.p = dataEnd
                    }
                    CLUSTER -> {
                        walk(dataStart, dataEnd, true)
                        if (size >= 0) r.p = dataEnd
                        // An unknown-size cluster leaves r.p at the element that ended it.
                    }
                    SIMPLE_BLOCK -> { block(dataStart, dataEnd); r.p = dataEnd }
                    BLOCK_GROUP -> {
                        val gr = Reader(bytes).also { it.p = dataStart }
                        while (gr.p < dataEnd) {
                            val gid = gr.id()
                            val gsz = gr.size()
                            val gs = gr.p
                            val ge = (gs + gsz).toInt()
                            if (gid == BLOCK) block(gs, ge)
                            gr.p = ge
                        }
                        r.p = dataEnd
                    }
                    else -> r.p = dataEnd
                }
            }
        }

        return runCatching {
            walk(0, bytes.size, false)
            if (opusNumber < 0) null else OpusTrack(head, channels, rate, preSkip, packets)
        }.getOrNull()
    }

    /** The frames of a block's payload [start, end) under its lacing (none, Xiph, fixed, EBML). */
    private fun frames(b: ByteArray, start: Int, end: Int, lacing: Int): List<Pair<Int, Int>>? {
        if (lacing == 0) return listOf(start to (end - start))
        var p = start
        val count = (b[p++].toInt() and 0xff) + 1
        val sizes = IntArray(count)
        when (lacing) {
            1 -> for (i in 0 until count - 1) {
                var s = 0
                while (true) { val v = b[p++].toInt() and 0xff; s += v; if (v != 255) break }
                sizes[i] = s
            }
            3 -> {
                val r = Reader(b).also { it.p = p }
                sizes[0] = r.size().toInt()
                for (i in 1 until count - 1) {
                    val at = r.p
                    val first = b[at].toInt() and 0xff
                    val len = Integer.numberOfLeadingZeros(first) - 24 + 1
                    val raw = r.size()
                    val bias = (1L shl (7 * len - 1)) - 1
                    sizes[i] = (sizes[i - 1] + (raw - bias)).toInt()
                }
                p = r.p
            }
            2 -> {
                val each = (end - p) / count
                return (0 until count).map { (p + it * each) to each }
            }
        }
        val used = sizes.take(count - 1).sum()
        sizes[count - 1] = end - p - used
        if (sizes.any { it < 0 }) return null
        var at = p
        return sizes.map { s -> (at to s).also { at += s } }
    }
}
