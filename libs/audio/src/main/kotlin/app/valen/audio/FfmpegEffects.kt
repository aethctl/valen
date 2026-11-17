/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.BASS_HZ
import app.valen.core.EQ_BANDS
import app.valen.core.EQ_Q
import app.valen.core.SoundEffects
import app.valen.core.TREBLE_HZ
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Valen's sound effects as one FFmpeg filter graph for `ffplay -af`, labelled `[in]`…`[out]` the
 * way ffplay links it. Everything runs at 48 kHz in stereo, in this order:
 *
 * time (rubberband, or a turntable's resample) → preamp → cuts, shelves, the ten bands →
 * compressor → echo → reverb (a convolution with a room Valen writes itself, mixed beside the dry
 * sound) → width → 8D → a limiter, so no setting can clip.
 *
 * The equalizer uses FFmpeg's biquads with the same corner, Q and shelf slope as the curve Valen
 * draws ([SoundEffects.responseDb]) and as Android's Web Audio graph.
 */
object FfmpegEffects {
    const val RATE = 48_000

    /** The filter graph for [effects], or null when they change nothing (no filter at all then). */
    fun graph(effects: SoundEffects, filters: Set<String>, room: (SoundEffects) -> Path?): String? {
        val e = effects.effective()
        if (e.isFlat) return null
        val chain = mutableListOf("aresample=$RATE", "aformat=sample_fmts=dblp:channel_layouts=stereo")
        chain += time(e, filters)
        val headroom = e.outputHeadroomDb(RATE.toFloat())
        if (headroom != 0f) chain += "volume=${num(headroom)}dB:precision=double"
        if (abs(e.preamp) >= 0.05f) chain += "volume=${num(e.preamp)}dB:precision=double"
        if (e.lowCut > 0f) chain += "highpass=f=${num(e.lowCut)}:t=q:w=0.7071:p=2"
        if (e.highCut > 0f) chain += "lowpass=f=${num(e.highCut)}:t=q:w=0.7071:p=2"
        if (abs(e.bass) >= 0.05f) chain += "bass=g=${num(e.bass)}:f=${num(BASS_HZ)}:t=q:w=0.7071"
        if (abs(e.treble) >= 0.05f) chain += "treble=g=${num(e.treble)}:f=${num(TREBLE_HZ)}:t=q:w=0.7071"
        e.bands.forEachIndexed { i, g ->
            if (abs(g) >= 0.05f) chain += "equalizer=f=${num(EQ_BANDS[i])}:t=q:w=${num(EQ_Q)}:g=${num(g)}"
        }
        if (e.parametricEq.enabled) {
            if (e.parametricEq.preamp != 0f) chain += "volume=${num(e.parametricEq.preamp)}dB:precision=double"
            e.parametricEq.bands.filter { it.active }.forEach { band ->
                val c = band.coefficients(RATE.toFloat())
                fun coefficient(value: Double) = String.format(Locale.ROOT, "%.15f", value)
                chain += "biquad=b0=${coefficient(c.b0)}:b1=${coefficient(c.b1)}:b2=${coefficient(c.b2)}:a0=${coefficient(c.a0)}:a1=${coefficient(c.a1)}:a2=${coefficient(c.a2)}:precision=f64:transform=tdii"
            }
        }
        if (e.evenOut >= 0.005f) chain += compressor(e.evenOut)
        if (e.echo >= 0.005f) chain += echo(e)

        val graph = StringBuilder("[in]").append(chain.joinToString(","))
        val impulse = if (e.reverb >= 0.005f && "afir" in filters && "amovie" in filters) room(e) else null
        if (impulse != null) {
            // The room is heard beside the dry sound, never instead of it; the dry side gives way a
            // little as the room grows, as it does when the microphone moves back.
            val dry = 1f - 0.35f * e.reverb
            val wet = e.reverb * 1.15f
            graph.append(",asplit=2[dry][wet];")
                .append("amovie=filename=${escape(impulse.toString())},aresample=$RATE,aformat=sample_fmts=fltp:channel_layouts=stereo[ir];")
                .append("[wet][ir]afir=irnorm=1:irgain=1[room];")
                .append("[dry][room]amix=inputs=2:weights=${num(dry)} ${num(wet)}:normalize=0")
        } else if (e.reverb >= 0.005f) {
            // No convolution here: a few early reflections stand in for the room.
            graph.append(",aecho=1:${num(1f / (1f + e.reverb))}:23|41|67|97:${num(0.5f * e.reverb)}|${num(0.4f * e.reverb)}|${num(0.3f * e.reverb)}|${num(0.2f * e.reverb)}")
        }
        val tail = mutableListOf<String>()
        if (abs(e.width - 1f) >= 0.01f) {
            tail += if (e.width < 0.02f) "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1" else "stereotools=mlev=1:slev=${num(e.width.coerceAtLeast(0.016f))}"
        }
        if (e.rotate > 0f) tail += "apulsator=mode=sine:hz=${num(1f / e.rotate, 4)}:amount=0.85:offset_l=0:offset_r=0.5"
        if (e.peakLimiter) tail += "alimiter=limit=0.95:attack=5:release=60:level=0"
        if (tail.isNotEmpty()) graph.append(",").append(tail.joinToString(","))
        graph.append("[out]")
        return graph.toString()
    }

    /** Seconds of song per second heard: the speed, whichever way time is changed. */
    fun rate(effects: SoundEffects): Double = effects.effective().speed.toDouble()

    /**
     * How [graph] scales the stream's timestamps: each `asetrate` after the graph's resample to
     * [RATE] stretches them by its rate over [RATE] (a turntable at 1.25× reads 27.96 s where the
     * song is at 34.95 s); tempo filters (atempo, rubberband) keep them. ffplay's clock starts from
     * those timestamps, so this is what turns its first reading back into the song's time.
     */
    fun clockScale(graph: String?): Double =
        ASETRATE.findAll(graph ?: return 1.0).fold(1.0) { k, m -> k * (m.groupValues[1].toDouble() / RATE) }

    private val ASETRATE = Regex("asetrate=(\\d+)")

    /**
     * Speed and pitch. A turntable resamples (pitch and speed together, exact); otherwise
     * rubberband keeps them apart at its best quality, and without it a resample for the pitch is
     * evened back to the right speed with atempo.
     */
    private fun time(e: SoundEffects, filters: Set<String>): List<String> {
        val speed = e.speed
        if (e.vinyl) {
            if (abs(speed - 1f) < 0.005f) return emptyList()
            return listOf("asetrate=${(RATE * speed).roundToInt()}", "aresample=$RATE")
        }
        val ratio = e.pitchRatio
        val tempo = abs(speed - 1f) >= 0.005f
        val pitched = abs(e.pitch) >= 0.05f
        if (!tempo && !pitched) return emptyList()
        if ("rubberband" in filters) {
            return listOf("rubberband=tempo=${num(speed, 4)}:pitch=${num(ratio, 5)}:pitchq=quality:transients=smooth:window=standard")
        }
        val out = mutableListOf<String>()
        if (pitched) out += listOf("asetrate=${(RATE * ratio).roundToInt()}", "aresample=$RATE")
        out += atempo(speed / (if (pitched) ratio else 1f))
        return out
    }

    /** atempo takes 0.5 to 100 at a time: a slower tempo is reached in steps. */
    private fun atempo(factor: Float): List<String> {
        if (abs(factor - 1f) < 0.002f) return emptyList()
        val steps = mutableListOf<String>()
        var left = factor.toDouble()
        while (left < 0.5) {
            steps += "atempo=0.5"
            left /= 0.5
        }
        steps += "atempo=${num(left.toFloat(), 4)}"
        return steps
    }

    /** "Even out": the more, the lower it starts, the harder it presses, the more it lifts after. */
    private fun compressor(amount: Float): String {
        val thresholdDb = -10f - 18f * amount
        val ratio = 1.5f + 6.5f * amount
        val makeupDb = -thresholdDb * (1f - 1f / ratio) * 0.55f
        return "acompressor=threshold=${num(dbToLinear(thresholdDb), 5)}:ratio=${num(ratio)}:attack=8:release=180:knee=4:makeup=${num(dbToLinear(makeupDb).coerceIn(1f, 64f))}"
    }

    /** Echoes on the whole sound: each one [SoundEffects.echoRepeats] of the last, until they fade. */
    private fun echo(e: SoundEffects): String {
        val delays = mutableListOf<String>()
        val decays = mutableListOf<String>()
        var level = e.echo
        var k = 1
        while (level >= 0.02f && k <= 8) {
            delays += num(e.echoMs * k)
            decays += num(level.coerceAtMost(1f), 4)
            level *= e.echoRepeats
            if (e.echoRepeats < 0.02f) break
            k++
        }
        return "aecho=1:${num(1f / (1f + 0.6f * e.echo))}:${delays.joinToString("|")}:${decays.joinToString("|")}"
    }

    // ── The room ──

    /**
     * A room's impulse response, written once per size and brightness (rounded to twentieths) into
     * [dir]: early reflections, then a dense tail of decorrelated noise that falls 60 dB over the
     * room's length and darkens as it goes — air and walls take the highs first.
     */
    fun room(e: SoundEffects, dir: Path): Path? = runCatching {
        val size = (e.room * 20).roundToInt()
        val bright = (e.brightness * 20).roundToInt()
        val file = dir.resolve("room-$size-$bright.wav")
        if (Files.isRegularFile(file)) return@runCatching file
        Files.createDirectories(dir)
        val (left, right) = impulse(size / 20f, bright / 20f, RATE)
        val tmp = Files.createTempFile(dir, "room", ".wav")
        Files.write(tmp, wav(left, right, RATE))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        file
    }.getOrNull()

    /** The two channels of a room of [size] and [brightness] (both 0 to 1), at [rate]. */
    fun impulse(size: Float, brightness: Float, rate: Int): Pair<FloatArray, FloatArray> {
        val seconds = 0.35f + size * 4.2f
        val n = (seconds * rate).toInt()
        val preDelay = ((0.004f + size * 0.03f) * rate).toInt()
        // The tail starts with this much of the top end and ends with this much.
        val startHz = 7000f + brightness * 11000f
        val endHz = 600f + brightness * 6000f
        fun channel(seed: Int): FloatArray {
            val random = Random(seed)
            val out = FloatArray(n)
            // Early reflections: a few walls, the nearest loudest, a little different on each side.
            repeat(8) { k ->
                val at = preDelay + ((0.006f + k * (0.006f + size * 0.012f)) * rate * (0.8f + random.nextFloat() * 0.4f)).toInt()
                if (at < n) out[at] += (0.55f / (1 + k * 0.6f)) * (if (random.nextBoolean()) 1f else -1f)
            }
            var low = 0f
            val ln60 = ln(1000f) // 60 dB of amplitude
            for (i in preDelay until n) {
                val t = (i - preDelay).toFloat() / (n - preDelay)
                val env = exp(-ln60 * t)
                val cutoff = startHz * (endHz / startHz).pow(t)
                val a = 1f - exp(-2f * PI.toFloat() * cutoff / rate)
                low += a * ((random.nextFloat() * 2f - 1f) - low)
                // The first few milliseconds swell in, so the tail does not start with a click.
                val fade = ((i - preDelay) / (0.012f * rate)).coerceAtMost(1f)
                out[i] += low * env * fade * 0.6f
            }
            return out
        }
        val l = channel(17)
        val r = channel(71)
        // One energy for every room, so the amount of reverb means the same in a small room and a cave.
        var energy = 0.0
        for (i in 0 until n) energy += l[i] * l[i] + r[i] * r[i]
        val scale = (1.0 / sqrt(energy / 2)).toFloat()
        for (i in 0 until n) {
            l[i] *= scale
            r[i] *= scale
        }
        return l to r
    }

    /** A 32-bit float stereo WAV. */
    private fun wav(left: FloatArray, right: FloatArray, rate: Int): ByteArray {
        val frames = left.size
        val data = frames * 2 * 4
        val buf = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(2).putInt(rate).putInt(rate * 8).putShort(8).putShort(32)
        buf.put("data".toByteArray()).putInt(data)
        for (i in 0 until frames) {
            buf.putFloat(left[i])
            buf.putFloat(right[i])
        }
        return buf.array()
    }

    // ── The binary ──

    private val known = ConcurrentHashMap<String, Set<String>>()

    /** The audio filters [binary] was built with, asked once per binary. */
    fun filters(binary: String): Set<String> = known.getOrPut(binary) {
        runCatching {
            val proc = ProcessBuilder(binary, "-hide_banner", "-filters").redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, TimeUnit.SECONDS)
            text.lineSequence()
                .mapNotNull { line -> line.trim().split(Regex("\\s+")).getOrNull(1) }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /** Where the rooms are kept: `$XDG_CACHE_HOME/valen/rooms`, `~/.cache/valen/rooms` by default. */
    fun defaultRoomDir(): Path {
        val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".cache")
        return cache.resolve("valen").resolve("rooms")
    }

    private fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    private fun num(v: Float, decimals: Int = 3): String {
        val text = String.format(Locale.ROOT, "%.${decimals}f", v).trimEnd('0').trimEnd('.')
        return if (text == "-0") "0" else text
    }

    /**
     * A path as one filter option value. It is read twice — by the graph (which takes `\ ' [ ] , ;`)
     * and then by the filter's options (which take `\ ' :`) — so it is escaped for both, in that order.
     */
    internal fun escape(path: String): String {
        val option = buildString { for (c in path) { if (c in "\\':") append('\\'); append(c) } }
        return buildString { for (c in option) { if (c in "\\'[],;") append('\\'); append(c) } }
    }
}
