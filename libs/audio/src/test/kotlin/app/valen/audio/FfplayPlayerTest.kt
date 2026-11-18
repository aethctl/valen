/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.SoundEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The desktop's real player, run against a real ffplay on SDL's silent dummy driver: the position
 * is the song's own (from ffplay's clock), at any speed; pause holds it; a crowd of commands leaves
 * one process and none after a stop; a stream that stops arriving is reported stalled.
 */
class FfplayPlayerTest {

    @TempDir
    lateinit var dir: Path

    private val ffplay = FfplayPlayer.findBinary()
    private val ffmpeg = DiskShelf.tool("ffmpeg", "VALEN_FFMPEG_BIN")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun after() = scope.cancel()

    private fun player(stallAfterMs: Long = 12_000L) =
        FfplayPlayer(scope, ffplay, dir.resolve("rooms"), mapOf("SDL_AUDIODRIVER" to "dummy"), stallAfterMs)

    /** Two minutes of a tone as Opus in WebM, the shape of a YouTube stream. */
    private fun tone(seconds: Int = 120): ResolvedStream {
        val file = dir.resolve("tone-$seconds.webm")
        if (!Files.exists(file)) {
            val p = ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds", "-c:a", "libopus", "-b:a", "64k", file.toString())
                .redirectErrorStream(true).start()
            p.waitFor(60, TimeUnit.SECONDS)
        }
        return ResolvedStream(url = file.toString(), durationMs = seconds * 1000L)
    }

    /** ffplay processes of this JVM still alive. */
    private fun ffplays(): Int = ProcessHandle.current().descendants().filter { h ->
        h.isAlive && runCatching { Files.readAllBytes(Path.of("/proc/${h.pid()}/cmdline")) }.getOrNull()
            ?.let { String(it).substringBefore('\u0000').endsWith("ffplay") } == true
    }.count().toInt()

    private suspend fun FfplayPlayer.awaitSound(ms: Long = 8_000) =
        assertNotNull(withTimeoutOrNull(ms) { while (!state.value.started) delay(20); true }, "no sound came out")

    @Test
    @Timeout(60)
    fun `the position is the song's own, from where it started`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val p = player()
        p.play(tone(), 30_000)
        p.awaitSound()
        val from = p.state.value.positionMs
        assertTrue(from in 29_900..30_400, "started at $from")
        delay(1_500)
        val moved = p.state.value.positionMs - from
        assertTrue(abs(moved - 1_500) < 300, "moved $moved ms in 1500")
        p.stop()
    }

    @Test
    @Timeout(60)
    fun `at twice the speed and on a turntable at half, the position counts the song's time`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        for ((effects, expected) in listOf(SoundEffects(speed = 2f) to 3_000L, SoundEffects(speed = 0.5f, vinyl = true) to 750L)) {
            val p = player()
            p.setEffects(effects)
            p.play(tone(), 20_000)
            p.awaitSound()
            val from = p.state.value.positionMs
            delay(1_500)
            val moved = p.state.value.positionMs - from
            assertTrue(abs(moved - expected) < 350, "$effects moved $moved ms, expected $expected")
            p.stop()
        }
    }

    @Test
    @Timeout(60)
    fun `pause holds the place and resume goes on from it`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val p = player()
        p.play(tone(), 10_000)
        p.awaitSound()
        delay(500)
        p.pause()
        val held = p.state.value.positionMs
        delay(1_000)
        assertEquals(held, p.state.value.positionMs)
        p.resume()
        delay(1_200)
        val moved = p.state.value.positionMs - held
        assertTrue(abs(moved - 1_200) < 350, "moved $moved after resume")
        assertTrue(!p.state.value.stalled)
        p.stop()
    }

    @Test
    @Timeout(120)
    fun `a crowd of seeks, pauses and effect changes leaves one process, and a stop none`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val p = player()
        val random = Random(42)
        p.play(tone(), 0)
        repeat(80) {
            when (random.nextInt(6)) {
                0, 1 -> p.seek(random.nextLong(0, 110_000))
                2 -> p.pause()
                3 -> p.resume()
                4 -> p.setEffects(if (random.nextBoolean()) SoundEffects() else SoundEffects(reverb = random.nextFloat(), speed = 0.8f + random.nextFloat() * 0.4f))
                else -> p.setVolume(random.nextFloat())
            }
            delay(random.nextLong(5, 80))
        }
        p.resume()
        val target = 60_000L
        p.seek(target)
        p.awaitSound()
        delay(1_200)
        assertEquals(1, ffplays(), "ffplay processes after the crowd")
        val s = p.state.value
        assertNull(s.error)
        assertTrue(s.playing && !s.stalled)
        assertTrue(s.positionMs in target..target + 3_000, "position ${s.positionMs}")
        p.stop()
        delay(1_500)
        assertEquals(0, ffplays(), "ffplay processes after a stop")
    }

    @Test
    @Timeout(60)
    fun `a short stream ends as an end, and one that cannot open is an error`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val p = player()
        p.play(tone(2), 0)
        assertNotNull(withTimeoutOrNull(8_000) { while (!p.state.value.ended) delay(50); true })
        assertNull(p.state.value.error)

        p.play(ResolvedStream(url = dir.resolve("missing.webm").toString(), durationMs = 1_000), 0)
        assertNotNull(withTimeoutOrNull(8_000) { while (p.state.value.error == null) delay(50); true })
        assertTrue(!p.state.value.ended, "a stream that never opened is not an end")
        assertEquals("No such file or directory", p.state.value.error)
    }

    @Test
    @Timeout(20)
    fun `a cached file without catalogue duration learns its length and can seek`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val p = player()
        p.play(tone(12).copy(durationMs = 0), 0)
        p.awaitSound()
        assertTrue(p.state.value.durationMs in 12_000..12_100, "missing container duration: ${p.state.value}")
        p.seek(6_000)
        p.awaitSound()
        assertTrue(p.state.value.durationMs in 12_000..12_100)
        assertTrue(p.state.value.positionMs in 4_500..7_000)
        assertFalse(p.state.value.ended)
        p.stop()
    }

    @Test
    @Timeout(10)
    fun `exit zero without a clock is a failure and never an end`() = runBlocking {
        val refused = dir.resolve("refused-ffplay").toFile()
        refused.writeText("#!/bin/sh\necho '[error] stream refused'\nexit 0\n")
        assertTrue(refused.setExecutable(true))
        val p = FfplayPlayer(scope, binary = refused.absolutePath)
        p.play(ResolvedStream("unused", durationMs = 200_000), 0)
        assertNotNull(withTimeoutOrNull(5_000) { while (p.state.value.error == null) delay(20); true })
        assertFalse(p.state.value.ended)
        assertEquals("stream refused", p.state.value.error)
        assertEquals(0, p.state.value.positionMs)
        p.stop()
    }

    @Test
    @Timeout(30)
    fun `an early stream end retries and never advances the queue`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val p = player()
        val short = tone(2).copy(durationMs = 30_000, checkEarlyEnd = true)
        p.play(short, 0)
        assertNotNull(withTimeoutOrNull(12_000) { while (p.state.value.error == null) delay(50); true }, "no failure after a truncated stream")
        assertTrue(!p.state.value.ended, "a truncated stream was reported as a natural end")
        assertTrue(p.state.value.positionMs < 6_000, "retry did not resume where it stopped")
        p.stop()
    }

    @Test
    @Timeout(30)
    fun `a preview with the full track duration ends normally`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val p = player()
        p.play(tone(2).copy(durationMs = 30_000), 0)
        assertNotNull(withTimeoutOrNull(8_000) { while (!p.state.value.ended) delay(50); true })
        assertNull(p.state.value.error)
        p.stop()
    }

    @Test
    @Timeout(30)
    fun `volume drags are coalesced rather than restarting on every move`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val p = player()
        p.play(tone(), 0)
        p.awaitSound()
        repeat(8) {
            p.setVolume(0.1f + it * 0.1f)
            assertTrue(p.state.value.started, "a slider move restarted playback immediately")
            delay(30)
        }
        assertEquals(0.8f, p.state.value.volume, 0.001f)
        assertNotNull(withTimeoutOrNull(2_000) { while (p.state.value.started) delay(10); true }, "no settled restart")
        p.awaitSound()
        assertEquals(0.8f, p.state.value.volume, 0.001f)
        p.stop()
    }

    @Test
    @Timeout(30)
    fun `the old speed remains in effect while a new preset is settling`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val p = player()
        p.play(tone(), 10_000)
        p.awaitSound()
        delay(500)
        val before = p.state.value.positionMs
        p.setEffects(SoundEffects(speed = 2f))
        delay(120)
        p.pause()
        val moved = p.state.value.positionMs - before
        assertTrue(moved in 0..380, "position jumped $moved ms before the new speed was applied")
        p.stop()
    }

    @Test
    @Timeout(60)
    fun `a stream that stops arriving is reported stalled`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        // A pipe that gives eight seconds of sound and then nothing, as a dead connection does (more
        // than ffplay reads to recognise the stream before it starts).
        val fifo = dir.resolve("live.wav")
        ProcessBuilder("mkfifo", fifo.toString()).start().waitFor(5, TimeUnit.SECONDS)
        val writer = thread(isDaemon = true) {
            runCatching {
                FileOutputStream(fifo.toFile()).use { out ->
                    out.write(wavHeader(48_000, 600))
                    out.write(pcm(48_000, 8))
                    out.flush()
                    Thread.sleep(30_000)
                }
            }
        }
        val p = player(stallAfterMs = 1_500)
        p.play(ResolvedStream(url = fifo.toString(), durationMs = 600_000), 0)
        p.awaitSound()
        assertNotNull(withTimeoutOrNull(16_000) { while (!p.state.value.stalled) delay(50); true }, "never noticed the stall")
        assertTrue(p.state.value.positionMs in 6_000..8_500, "stalled at ${p.state.value.positionMs}")
        p.stop()
        writer.interrupt()
    }

    /** A tone in [container] whose seek points are [clusterMs] apart (WebM's clusters, as YouTube's are). */
    private fun coarse(container: String, clusterMs: Int = 5_000, seconds: Int = 120): ResolvedStream {
        val file = dir.resolve("coarse-$seconds.$container")
        if (!Files.exists(file)) {
            val codec = if (container == "webm") listOf("-c:a", "libopus", "-b:a", "64k", "-cluster_time_limit", "$clusterMs")
            else listOf("-c:a", "aac", "-b:a", "128k", "-f", "mpegts")
            val p = ProcessBuilder(listOf(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds") + codec + file.toString())
                .redirectErrorStream(true).start()
            p.waitFor(60, TimeUnit.SECONDS)
        }
        return ResolvedStream(url = file.toString(), durationMs = seconds * 1000L)
    }

    // ffplay's -ss lands on a seek point. The lyrics follow the position, so it must be where sound
    // really began (the cluster before 37.3 s), not the time asked for — at any speed, on a turntable,
    // and in a stream whose clock starts at 1.4 s (MPEG-TS).
    @Test
    @Timeout(90)
    fun `the position is where sound really began, not where it was asked to`() = runBlocking {
        assumeTrue(ffplay != null && ffmpeg != null)
        val cases = listOf(
            Triple(coarse("webm"), SoundEffects(), 34_700L..35_300L),
            Triple(coarse("webm"), SoundEffects(speed = 1.25f), 34_700L..35_300L),
            Triple(coarse("webm"), SoundEffects(speed = 1.25f, vinyl = true), 34_700L..35_300L),
            Triple(coarse("ts"), SoundEffects(), 36_900L..37_500L),
        )
        for ((stream, effects, expected) in cases) {
            val p = player()
            p.setEffects(effects)
            p.play(stream, 37_300)
            p.awaitSound()
            // The first reading anchors the position; a moment later it has moved on from there.
            delay(150)
            val at = p.state.value.positionMs - (150 * FfmpegEffects.rate(effects)).toLong()
            assertTrue(at in expected, "${stream.url.substringAfterLast('.')} $effects began at $at, expected $expected")
            p.stop()
        }
    }

    @Test
    fun `ffplay's tagged lines and its first clock give the song's time`() {
        assertEquals("info" to "  Duration: 00:02:59.93, start: 1.400000, bitrate: 159 kb/s",
            FfplayPlayer.splitLevel("[info]   Duration: 00:02:59.93, start: 1.400000, bitrate: 159 kb/s"))
        assertEquals(null to "plain line", FfplayPlayer.splitLevel("plain line"))
        assertEquals(1.4, FfplayPlayer.streamStartOf("  Duration: 00:02:59.93, start: 1.400000, bitrate: 159 kb/s"))
        assertNull(FfplayPlayer.streamStartOf("  Stream #0:0: Audio: opus, 48000 Hz"))
        assertEquals(179_930L, FfplayPlayer.durationOf("  Duration: 00:02:59.93, start: 1.400000, bitrate: 159 kb/s"))
        assertEquals(3_723_456L, FfplayPlayer.durationOf("Duration: 01:02:03.456, start: 0.000000"))
        assertNull(FfplayPlayer.durationOf("Duration: N/A, start: 0.000000"))
        assertNull(FfplayPlayer.durationOf("Duration: 00:00:00.00"))
        // WebM at a cluster, a turntable (timestamps scaled), an MPEG-TS (clock from 1.4 s).
        assertEquals(34_980L, FfplayPlayer.soundedAt(34.98, 0.0, 1.0, 37_300))
        assertEquals(34_950L, FfplayPlayer.soundedAt(27.96, 0.0, 1.25, 37_300))
        assertEquals(37_160L, FfplayPlayer.soundedAt(38.56, 1.4, 1.0, 37_300))
        // Unknown start, or an answer nowhere near the request: the request stands.
        assertNull(FfplayPlayer.soundedAt(34.98, Double.NaN, 1.0, 37_300))
        assertNull(FfplayPlayer.soundedAt(500.0, 0.0, 1.0, 37_300))
        assertEquals(1.25, FfmpegEffects.clockScale("aresample=48000,asetrate=60000,aresample=48000,volume=1"), 1e-9)
        assertEquals(1.0, FfmpegEffects.clockScale("aresample=48000,atempo=1.25"), 1e-9)
        assertEquals(1.0, FfmpegEffects.clockScale(null), 1e-9)
    }

    private fun wavHeader(rate: Int, seconds: Int): ByteArray {
        val data = rate * 2 * 2 * seconds
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.array()
    }

    private fun pcm(rate: Int, seconds: Int): ByteArray {
        val frames = rate * seconds
        val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 440 * i / rate) * 8000).toInt().toShort()
            buf.putShort(v); buf.putShort(v)
        }
        return buf.array()
    }
}
