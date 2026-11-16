/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.SoundEffects
import app.valen.core.SoundPresets
import app.valen.core.EqFilterType
import app.valen.core.ParametricBand
import app.valen.core.ParametricEq
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The graph is checked against the FFmpeg on this machine: every preset must be a graph FFmpeg
 * accepts, and what comes out of a sine must be what the settings say — the band's gain, the
 * octave, the length. Skipped where there is no `ffmpeg`.
 */
class FfmpegEffectsTest {

    @TempDir
    lateinit var dir: Path

    private val ffmpeg: String? = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { Files.isExecutable(Path.of(it)) }

    private fun filters() = FfmpegEffects.filters(ffmpeg!!)

    /**
     * Runs the graph on [seconds] of a [hz] sine (stereo, 48 kHz) and returns the left channel.
     * ffplay's `[in]`/`[out]` become ffmpeg's input and output labels.
     */
    private fun render(graph: String?, hz: Int = 1000, seconds: Double = 2.0): FloatArray {
        val complex = (graph ?: "[in]anull[out]").replace("[in]", "[0:a]").replace("[out]", "[o]")
        val out = dir.resolve("out-${System.nanoTime()}.raw")
        val proc = ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "sine=frequency=$hz:sample_rate=48000:duration=$seconds,pan=stereo|c0=c0|c1=c0",
            "-filter_complex", complex, "-map", "[o]", "-f", "f32le", "-ac", "2", "-ar", "48000", out.toString(),
        ).redirectErrorStream(true).start()
        val said = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "ffmpeg did not finish")
        assertEquals(0, proc.exitValue(), "ffmpeg refused the graph: $said\n$graph")
        val bytes = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN)
        val frames = bytes.remaining() / 8
        return FloatArray(frames) { bytes.float.also { bytes.float } }
    }

    /** RMS in dB over the middle of the signal (no edges, no reverb tails). */
    private fun rmsDb(x: FloatArray): Double {
        val from = x.size / 4
        val to = x.size * 3 / 4
        var sum = 0.0
        for (i in from until to) sum += x[i] * x[i]
        return 20 * log10(sqrt(sum / (to - from)))
    }

    /** Frequency from zero crossings over the middle of the signal. */
    private fun hz(x: FloatArray): Double {
        val from = x.size / 4
        val to = x.size * 3 / 4
        var crossings = 0
        for (i in from + 1 until to) if ((x[i - 1] < 0f) != (x[i] < 0f)) crossings++
        return crossings / 2.0 / ((to - from) / 48000.0)
    }

    @Test
    fun `nothing set is no filter at all`() {
        assertNull(FfmpegEffects.graph(SoundEffects(), emptySet()) { null })
        assertNull(FfmpegEffects.graph(SoundEffects(on = false, reverb = 1f), emptySet()) { null })
    }

    @Test
    fun `every preset is a graph FFmpeg runs`() {
        assumeTrue(ffmpeg != null)
        SoundPresets.all.drop(1).forEach { preset ->
            val graph = FfmpegEffects.graph(preset.effects, filters()) { FfmpegEffects.room(it, dir) }
            render(graph, seconds = 1.0)
        }
    }

    @Test
    fun `a band lifts its own frequency by its gain`() {
        assumeTrue(ffmpeg != null)
        val flat = rmsDb(render(null))
        val bands = SoundEffects.FLAT_BANDS.toMutableList().also { it[5] = 6f }
        val lifted = rmsDb(render(FfmpegEffects.graph(SoundEffects(bands = bands), filters()) { null }))
        assertEquals(6.0, lifted - flat, 0.3)
    }

    @Test
    fun `parametric biquads match response including shelf Q and coexist with fixed bands`() {
        assumeTrue(ffmpeg != null)
        val flat = rmsDb(render(null))
        EqFilterType.entries.forEach { type ->
            val band = ParametricBand(type, frequency = 500f, gain = 6f, q = 2f)
            val eq = ParametricEq(enabled = true, bands = listOf(band))
            val effects = SoundEffects(parametricEq = eq)
            val measured = rmsDb(render(FfmpegEffects.graph(effects, filters()) { null })) - flat
            assertEquals(effects.responseDb(1000f).toDouble(), measured, .15, "$type")
        }
        val effects = SoundEffects(bands = SoundEffects.FLAT_BANDS.toMutableList().also { it[5] = 3f },
            parametricEq = ParametricEq(true, bands = listOf(ParametricBand(frequency = 1000f, gain = 3f))))
        assertEquals(6.0, rmsDb(render(FfmpegEffects.graph(effects, filters()) { null })) - flat, .15)
    }

    @Test
    fun `pitch moves the note and speed changes the length`() {
        assumeTrue(ffmpeg != null)
        val up = render(FfmpegEffects.graph(SoundEffects(pitch = 12f), filters()) { null }, hz = 440)
        assertEquals(880.0, hz(up), 12.0)
        val slow = render(FfmpegEffects.graph(SoundEffects(speed = 0.5f), filters()) { null }, hz = 440, seconds = 1.0)
        assertEquals(96000.0, slow.size.toDouble(), 2400.0)
        assertEquals(440.0, hz(slow), 8.0)
        // A turntable: half speed is an octave down, too.
        val vinyl = render(FfmpegEffects.graph(SoundEffects(speed = 0.5f, vinyl = true), filters()) { null }, hz = 440, seconds = 1.0)
        assertEquals(220.0, hz(vinyl), 6.0)
        // Without rubberband (another machine's FFmpeg) the same settings still hold.
        val plain = filters() - "rubberband"
        assertEquals(880.0, hz(render(FfmpegEffects.graph(SoundEffects(pitch = 12f), plain) { null }, hz = 440)), 12.0)
        val both = render(FfmpegEffects.graph(SoundEffects(pitch = -12f, speed = 2f), plain) { null }, hz = 440, seconds = 2.0)
        assertEquals(220.0, hz(both), 6.0)
        assertEquals(48000.0, both.size.toDouble(), 2400.0)
    }

    @Test
    fun `the room is written once and the limiter keeps a loud setting under full scale`() {
        assumeTrue(ffmpeg != null)
        val e = SoundEffects(reverb = 1f, room = 1f, bands = List(10) { 12f }, preamp = 12f)
        val first = FfmpegEffects.room(e, dir)!!
        assertEquals(first, FfmpegEffects.room(e, dir))
        val out = render(FfmpegEffects.graph(e, filters()) { FfmpegEffects.room(it, dir) })
        assertTrue(out.maxOf { kotlin.math.abs(it) } <= 0.96f)
    }

    @Test
    fun `a room in a folder named with the graph's own characters is still found`() {
        assumeTrue(ffmpeg != null)
        val odd = dir.resolve("it's [odd], a:b; c")
        val e = SoundEffects(reverb = 0.5f)
        val graph = FfmpegEffects.graph(e, filters()) { FfmpegEffects.room(it, odd) }
        assertTrue(graph!!.contains("amovie"))
        render(graph, seconds = 1.0)
    }
}
