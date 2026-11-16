/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.BASS_HZ
import app.valen.core.EQ_BANDS
import app.valen.core.EQ_Q
import app.valen.core.EqFilterType
import app.valen.core.ParametricBand
import app.valen.core.SoundEffects
import app.valen.core.TREBLE_HZ
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Valen's sound effects as plain arithmetic on samples, for a player that hands its PCM over
 * (Android's ExoPlayer): the same settings as the FFmpeg graph, in the same order — preamp, the
 * cuts, shelves and bands (the same RBJ filters, from [ParametricBand.coefficients]), the parametric
 * EQ, compressor, echo, reverb, width, 8D, a limiter. Speed and pitch are the player's own.
 *
 * The reverb is algorithmic (a Freeverb-style network of combs and all-passes, sized by the room
 * and darkened by its tone) rather than the desktop's convolution: a four-second convolution per
 * sample is more than a phone should spend on it.
 *
 * Not thread-safe across [process] calls; [set] may be called from any thread and is picked up at
 * the start of the next [process].
 */
class SoundDsp {
    @Volatile private var pending: SoundEffects? = SoundEffects.FLAT
    @Volatile private var wantFlat = true
    private var e: SoundEffects = SoundEffects.FLAT
    private var flat = true

    private var rate = 48_000
    private var channels = 2

    private var filters: Array<Filter> = emptyArray()
    private var gain = 1f

    // Compressor.
    private var compressing = false
    private var thresholdDb = 0f
    private var ratio = 1f
    private var makeup = 1f
    private var envelopeDb = -120f
    private var attack = 0f
    private var release = 0f

    // Echo: a delay line per channel, darkened a little every time round.
    private var echoLine = Array(2) { FloatArray(1) }
    private var echoPos = 0
    private var echoLength = 1
    private val echoTone = FloatArray(2)
    private var echoToneCoef = 0f

    // Reverb.
    private var reverb: Freeverb? = null

    // 8D.
    private var phase = 0.0

    // Limiter.
    private var limit = 1f
    private var limitRelease = 0f

    /** The format samples arrive in; everything is set up again for it. */
    fun configure(sampleRate: Int, channelCount: Int) {
        rate = sampleRate.coerceAtLeast(8_000)
        channels = channelCount.coerceIn(1, 2)
        // Everything is laid out again for the new rate: the newest settings if some are waiting.
        if (pending == null) pending = e
        envelopeDb = -120f
        limit = 1f
        phase = 0.0
    }

    /** New settings ([SoundEffects.effective] is taken here: off is flat). */
    fun set(effects: SoundEffects) {
        val next = effects.effective()
        wantFlat = next.isFlat
        pending = next
    }

    /** Whether anything at all is done to the samples now (read on every buffer: no work in it). */
    val active: Boolean get() = !wantFlat

    /**
     * Processes [frames] interleaved frames of [samples] in place ([channels] per frame, the
     * values about ±1). Flat settings leave them exactly as they were.
     */
    fun process(samples: FloatArray, frames: Int) {
        pending?.let { apply(it); pending = null }
        if (flat) return
        val n = channels
        for (f in 0 until frames) {
            val i = f * n
            var l = samples[i] * gain
            var r = if (n == 2) samples[i + 1] * gain else l
            for (filter in filters) {
                l = filter.run(0, l)
                if (n == 2) r = filter.run(1, r)
            }
            if (n == 1) r = l
            if (compressing) {
                val level = max(abs(l), abs(r))
                val db = if (level > 1e-6f) 20f * log10(level) else -120f
                val coef = if (db > envelopeDb) attack else release
                envelopeDb = db + coef * (envelopeDb - db)
                val over = envelopeDb - thresholdDb
                val reduce = when {
                    over <= -KNEE_DB / 2 -> 0f
                    over >= KNEE_DB / 2 -> over * (1f - 1f / ratio)
                    else -> (1f - 1f / ratio) * (over + KNEE_DB / 2).pow(2) / (2 * KNEE_DB)
                }
                val g = 10f.pow(-reduce / 20f) * makeup
                l *= g
                r *= g
            }
            if (e.echo >= 0.005f) {
                val back = echoLine[0][echoPos]
                val backR = echoLine[1][echoPos]
                echoTone[0] += echoToneCoef * (back - echoTone[0])
                echoTone[1] += echoToneCoef * (backR - echoTone[1])
                echoLine[0][echoPos] = l + echoTone[0] * e.echoRepeats
                echoLine[1][echoPos] = r + echoTone[1] * e.echoRepeats
                echoPos = (echoPos + 1) % echoLength
                val scale = 1f / (1f + 0.6f * e.echo)
                l = (l + back * e.echo) * scale
                r = (r + backR * e.echo) * scale
            }
            reverb?.let { room ->
                val (wl, wr) = room.run(l, r)
                val dry = 1f - 0.35f * e.reverb
                l = l * dry + wl
                r = r * dry + wr
            }
            if (n == 2 && abs(e.width - 1f) >= 0.01f) {
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f * e.width
                l = mid + side
                r = mid - side
            }
            if (n == 2 && e.rotate > 0f) {
                // The Web Audio stereo panner, swept by a slow sine: the same 8D as the page engine.
                phase += 2.0 * PI / (e.rotate * rate)
                if (phase > 2 * PI) phase -= 2 * PI
                val pan = (0.85 * sin(phase)).toFloat()
                if (pan <= 0f) {
                    val x = (pan + 1f) * (PI.toFloat() / 2f)
                    val nl = l + r * cos(x)
                    val nr = r * sin(x)
                    l = nl
                    r = nr
                } else {
                    val x = pan * (PI.toFloat() / 2f)
                    val nl = l * cos(x)
                    val nr = r + l * sin(x)
                    l = nl
                    r = nr
                }
            }
            // Nothing leaves louder than the limit: the gain drops at once and comes back slowly.
            limit += (1f - limit) * limitRelease
            val peak = max(abs(l), abs(r)) * limit
            if (peak > LIMIT) limit *= LIMIT / peak
            l *= limit
            r *= limit
            samples[i] = l
            if (n == 2) samples[i + 1] = r
        }
    }

    private fun apply(next: SoundEffects) {
        val s = next.normalized()
        e = s
        flat = s.isFlat
        if (flat) return
        val sr = rate.toFloat()
        val list = mutableListOf<Filter>()
        if (s.lowCut > 0f) list += Filter(ParametricBand(EqFilterType.HIGH_PASS, s.lowCut, 0f, 0.7071f), sr)
        if (s.highCut > 0f) list += Filter(ParametricBand(EqFilterType.LOW_PASS, s.highCut, 0f, 0.7071f), sr)
        if (abs(s.bass) >= 0.05f) list += Filter(ParametricBand(EqFilterType.LOW_SHELF, BASS_HZ, s.bass, 0.7071f), sr)
        if (abs(s.treble) >= 0.05f) list += Filter(ParametricBand(EqFilterType.HIGH_SHELF, TREBLE_HZ, s.treble, 0.7071f), sr)
        s.bands.forEachIndexed { i, g -> if (abs(g) >= 0.05f) list += Filter(ParametricBand(EqFilterType.PEAKING, EQ_BANDS[i], g, EQ_Q), sr) }
        val parametric = s.parametricEq.normalized()
        if (parametric.enabled) parametric.bands.filter { it.active }.forEach { list += Filter(it, sr) }
        // A filter still in the chain keeps its memory, so a change in one band does not click in the others.
        val old = filters
        filters = list.map { f -> old.firstOrNull { it.same(f) }?.let { f.carryOn(it) } ?: f }.toTypedArray()
        gain = 10f.pow((s.preamp + if (parametric.enabled) parametric.preamp else 0f) / 20f)

        compressing = s.evenOut >= 0.005f
        thresholdDb = -10f - 18f * s.evenOut
        ratio = 1.5f + 6.5f * s.evenOut
        makeup = 10f.pow((-thresholdDb * (1f - 1f / ratio) * 0.55f) / 20f)
        attack = exp(-1f / (0.008f * sr))
        release = exp(-1f / (0.18f * sr))

        val length = (s.echoMs / 1000f * sr).roundToInt().coerceAtLeast(1)
        if (length != echoLength || echoLine[0].size != length) {
            echoLength = length
            echoLine = Array(2) { FloatArray(length) }
            echoPos = 0
        }
        echoToneCoef = 1f - exp(-2f * PI.toFloat() * 6000f / sr)

        reverb = if (s.reverb >= 0.005f) {
            (reverb?.takeIf { it.rate == rate } ?: Freeverb(rate)).also { it.set(s.room, s.brightness, s.reverb) }
        } else null
        limitRelease = 1f - exp(-1f / (0.06f * sr))
    }

    /** One RBJ biquad, two channels of memory (transposed direct form II). */
    private class Filter(val band: ParametricBand, private val sr: Float) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private val z1 = DoubleArray(2)
        private val z2 = DoubleArray(2)

        init {
            val c = band.coefficients(sr)
            b0 = c.b0 / c.a0; b1 = c.b1 / c.a0; b2 = c.b2 / c.a0; a1 = c.a1 / c.a0; a2 = c.a2 / c.a0
        }

        /** The same kind of filter at the same place (its gain or Q may have changed). */
        fun same(other: Filter) = sr == other.sr && band.type == other.band.type && abs(band.frequency - other.band.frequency) < 0.5f

        /** This filter, going on from [old]'s memory. */
        fun carryOn(old: Filter): Filter {
            old.z1.copyInto(z1)
            old.z2.copyInto(z2)
            return this
        }

        fun run(ch: Int, x: Float): Float {
            val y = b0 * x + z1[ch]
            z1[ch] = b1 * x - a1 * y + z2[ch]
            z2[ch] = b2 * x - a2 * y
            return y.toFloat()
        }
    }

    /** Eight combs and four all-passes per side, the right side a little longer (Freeverb's tuning). */
    private class Freeverb(val rate: Int) {
        private val scale = rate / 44_100f
        private val combs = Array(2) { side -> COMBS.map { Comb(((it + side * SPREAD) * scale).roundToInt()) } }
        private val passes = Array(2) { side -> PASSES.map { AllPass(((it + side * SPREAD) * scale).roundToInt()) } }
        private var wet = 0f

        fun set(room: Float, brightness: Float, amount: Float) {
            val feedback = 0.7f + 0.28f * room
            val damp = 0.15f + 0.65f * (1f - brightness)
            combs.forEach { side -> side.forEach { it.feedback = feedback; it.damp = damp } }
            wet = amount * 1.15f * 3f / COMBS.size
        }

        fun run(l: Float, r: Float): Pair<Float, Float> {
            val input = (l + r) * 0.015f
            var ol = 0f
            var or = 0f
            for (c in combs[0]) ol += c.run(input)
            for (c in combs[1]) or += c.run(input)
            for (a in passes[0]) ol = a.run(ol)
            for (a in passes[1]) or = a.run(or)
            return ol * wet to or * wet
        }

        private class Comb(size: Int) {
            private val line = FloatArray(size.coerceAtLeast(1))
            private var pos = 0
            private var low = 0f
            var feedback = 0.84f
            var damp = 0.2f
            fun run(x: Float): Float {
                val y = line[pos]
                low = y * (1f - damp) + low * damp
                line[pos] = x + low * feedback
                pos = (pos + 1) % line.size
                return y
            }
        }

        private class AllPass(size: Int) {
            private val line = FloatArray(size.coerceAtLeast(1))
            private var pos = 0
            fun run(x: Float): Float {
                val y = line[pos]
                line[pos] = x + y * 0.5f
                pos = (pos + 1) % line.size
                return y - x
            }
        }

        private companion object {
            val COMBS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            val PASSES = intArrayOf(556, 441, 341, 225)
            const val SPREAD = 23
        }
    }

    private companion object {
        const val KNEE_DB = 4f
        const val LIMIT = 0.95f
    }
}
