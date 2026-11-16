/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.AudioQuality
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Against the real services, only with `VALEN_LIVE=1`: a YouTube Music song resolved and kept
 * whole, then read back by ffprobe as a song of its stated length. Never part of a normal run.
 */
class LiveCacheTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a real song is kept whole and plays from the file`() = runBlocking {
        assumeTrue(System.getenv("VALEN_LIVE") == "1")
        val stream = YoutubeAudio().resolve("FGBhQbmPwH8", AudioQuality.AUTO)
        val cache = DiskAudioCache(dir, 512L * 1024 * 1024)
        val started = System.nanoTime()
        cache.fetch("youtube_music-FGBhQbmPwH8", stream)
        val seconds = (System.nanoTime() - started) / 1e9
        val file = cache.file("youtube_music-FGBhQbmPwH8")
        println("kept ${file.length()} bytes in ${"%.1f".format(seconds)} s")
        val probe = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file.absolutePath)
            .redirectErrorStream(true).start()
        val out = probe.inputStream.bufferedReader().readText().trim()
        probe.waitFor(30, TimeUnit.SECONDS)
        val duration = out.toDouble()
        println("ffprobe: $duration s (stated ${stream.durationMs / 1000} s)")
        assertTrue(kotlin.math.abs(duration - stream.durationMs / 1000.0) < 3.0)
    }
}
