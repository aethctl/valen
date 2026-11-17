/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.EqFilterType
import app.valen.core.ParametricBand
import app.valen.core.ParametricEq
import app.valen.core.SoundEffects
import app.valen.core.SoundPresets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Android's effects ([SoundDsp], inside ExoPlayer) measured on plain samples: the tone settings
 * must do what the notebook's curve says (the same curve the desktop's FFmpeg graph is held to),
 * the rest must do their part, nothing may clip, and it must be cheap enough for a phone.
 */
class SoundDspTest {
    @Test
    fun `bass bursts do not modulate another tone with peak limiter disabled`() {
        val high = sine(4000.0, level = .1f)
        val bass = sine(60.0, level = .8f).also { signal ->
            for (i in signal.indices) if ((i / 2) % 24000 >= 4800) signal[i] = 0f
        }
        val effects = SoundEffects(bass = 9f, peakLimiter = false)
        val together = run(effects, FloatArray(high.size) { high[it] + bass[it] })
        val highOnly = run(effects, high)
        val bassOnly = run(effects, bass)
        for (i in together.indices) {
            assertEquals(highOnly[i] + bassOnly[i], together[i], .0001f)
        }
    }

    private val rate = 48_000

    /** [seconds] of a [hz] sine at [level], stereo interleaved. */
    private fun sine(hz: Double, seconds: Double = 2.0, level: Float = 0.1f): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * 2) { i -> (level * sin(2 * PI * hz * (i / 2) / rate)).toFloat() }
    }

    private fun run(effects: SoundEffects, input: FloatArray, channels: Int = 2): FloatArray {
        val dsp = SoundDsp()
        dsp.configure(rate, channels)
        dsp.set(effects)
        val out = input.copyOf()
        // In buffers the size ExoPlayer hands over, so state carried between them is exercised.
        val step = 1024 * channels
        var at = 0
        while (at < out.size) {
            val n = minOf(step, out.size - at)
            val chunk = out.copyOfRange(at, at + n)
            dsp.process(chunk, n / channels)
            chunk.copyInto(out, at)
            at += n
        }
        return out
    }

    private fun left(x: FloatArray) = FloatArray(x.size / 2) { x[it * 2] }
    private fun right(x: FloatArray) = FloatArray(x.size / 2) { x[it * 2 + 1] }

    /** RMS in dB over the middle half (no edges, no settling). */
    private fun rmsDb(x: FloatArray): Double {
        val from = x.size / 4
        val to = x.size * 3 / 4
        var sum = 0.0
        for (i in from until to) sum += x[i].toDouble() * x[i]
        return 20 * log10(sqrt(sum / (to - from)))
    }

    @Test
    fun `flat settings leave every sample as it was`() {
        val input = sine(440.0)
        assertArrayEquals(input, run(SoundEffects(), input))
        assertArrayEquals(input, run(SoundEffects(on = false, reverb = 1f, bands = List(10) { 9f }), input))
    }

    @Test
    fun `the tone settings follow the notebook's curve`() {
        val cases = listOf(
            SoundEffects(bands = SoundEffects.FLAT_BANDS.toMutableList().also { it[5] = 6f }),
            SoundEffects(bass = 6f),
            SoundEffects(treble = -6f),
            SoundEffects(lowCut = 400f),
            SoundEffects(highCut = 2_000f),
            SoundEffects(preamp = -4f, bands = SoundEffects.FLAT_BANDS.toMutableList().also { it[4] = 3f; it[6] = -5f }),
            SoundEffects(parametricEq = ParametricEq(true, preamp = -2f, bands = listOf(ParametricBand(EqFilterType.PEAKING, 900f, 7f, 2f)))),
        )
        for (hz in listOf(100.0, 1_000.0, 6_000.0)) {
            val flat = rmsDb(left(sine(hz)))
            for (e in cases) {
                val measured = rmsDb(left(run(e, sine(hz)))) - flat
                assertEquals(e.responseDb(hz.toFloat()).toDouble(), measured, 0.3, "$e at $hz Hz")
            }
        }
    }

    @Test
    fun `every preset runs, stays finite and never leaves louder than the limit`() {
        val loud = FloatArray(rate * 2 * 2) { i -> (0.95 * sin(2 * PI * 80.0 * (i / 2) / rate) + 0.04 * sin(i * 0.37)).toFloat() }
        SoundPresets.all.forEach { preset ->
            val out = run(preset.effects.copy(preamp = 12f), loud)
            assertTrue(out.all { it.isFinite() }, "${preset.id} made a NaN")
            assertTrue(out.maxOf { abs(it) } <= 0.951f, "${preset.id} peaked at ${out.maxOf { abs(it) }}")
        }
    }

    @Test
    fun `reverb rings on after the sound stops, and echo comes back on time`() {
        val burst = FloatArray(rate * 2 * 2).also { x -> for (i in 0 until rate / 5 * 2) x[i] = (0.3 * sin(i * 0.2)).toFloat() }
        val wet = left(run(SoundEffects(reverb = 0.6f, room = 0.8f), burst))
        val tail = wet.copyOfRange(rate / 2, rate)
        assertTrue(tail.maxOf { abs(it) } > 0.001f, "no tail half a second after the sound")

        val click = FloatArray(rate * 2).also { it[0] = 0.5f; it[1] = 0.5f }
        val e = SoundEffects(echo = 0.5f, echoMs = 250f, echoRepeats = 0.4f)
        val out = left(run(e, click))
        val at = (0.25 * rate).toInt()
        val back = (at - 3..at + 3).maxOf { abs(out[it]) }
        assertEquals(0.5f * 0.5f / (1f + 0.6f * 0.5f), back, 0.02f, "the first echo")
        val second = (2 * at - 3..2 * at + 3).maxOf { abs(out[it]) }
        assertTrue(second in 0.03f..0.09f, "the second echo is a repeat of the first: $second")
    }

    @Test
    fun `width zero is mono, and 8D moves the sound between the ears`() {
        val wide = FloatArray(rate * 2) { i -> if (i % 2 == 0) (0.2 * sin(i * 0.01)).toFloat() else (0.2 * sin(i * 0.013)).toFloat() }
        val mono = run(SoundEffects(width = 0f), wide)
        for (f in 0 until mono.size / 2) assertEquals(mono[f * 2], mono[f * 2 + 1], 1e-6f)

        val centre = sine(500.0, seconds = 4.0)
        val turned = run(SoundEffects(rotate = 4f), centre)
        // A quarter turn in, the sound is mostly on one side; three quarters in, on the other.
        val q = rate
        val l1 = rmsDb(left(turned).copyOfRange(q - 2400, q + 2400))
        val r1 = rmsDb(right(turned).copyOfRange(q - 2400, q + 2400))
        val l3 = rmsDb(left(turned).copyOfRange(3 * q - 2400, 3 * q + 2400))
        val r3 = rmsDb(right(turned).copyOfRange(3 * q - 2400, 3 * q + 2400))
        assertTrue(r1 - l1 > 6 && l3 - r3 > 6, "8D: $l1/$r1 then $l3/$r3")
    }

    @Test
    fun `a change of rate or settings mid-stream takes effect at once`() {
        val dsp = SoundDsp()
        dsp.configure(44_100, 2)
        dsp.set(SoundEffects(bands = SoundEffects.FLAT_BANDS.toMutableList().also { it[5] = 6f }))
        dsp.configure(48_000, 2)
        val x = sine(1_000.0)
        dsp.process(x, x.size / 2)
        assertEquals(6.0, rmsDb(left(x)) - rmsDb(left(sine(1_000.0))), 0.3, "the settings set before the new rate were kept")
        assertTrue(dsp.active)
        dsp.set(SoundEffects())
        assertTrue(!dsp.active)
    }

    @Test
    fun `everything at once costs a small part of real time`() {
        val heavy = SoundEffects(
            bands = List(10) { if (it % 2 == 0) 3f else -2f }, bass = 3f, treble = 2f, lowCut = 40f, highCut = 15_000f,
            reverb = 0.5f, echo = 0.3f, width = 1.4f, rotate = 8f, evenOut = 0.5f,
            parametricEq = ParametricEq(true, bands = List(6) { ParametricBand(EqFilterType.PEAKING, 200f * (it + 1), 2f, 1.5f) }),
        )
        val tenSeconds = sine(440.0, seconds = 10.0, level = 0.3f)
        run(heavy, tenSeconds.copyOf(rate * 2)) // warm-up
        val start = System.nanoTime()
        run(heavy, tenSeconds)
        val ms = (System.nanoTime() - start) / 1e6
        println("SoundDsp: 10 s of stereo with every effect in $ms ms")
        assertTrue(ms < 1_000, "10 s of sound took $ms ms")
    }
}
