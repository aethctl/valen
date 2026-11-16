/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.AudioQuality
import app.valen.core.Playlist
import app.valen.core.ProviderId
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Against YouTube Music, only with `VALEN_LIVE=1`: a "Spotify" song kept as its YouTube Music copy
 * at the best quality, in a temporary folder. Never part of a normal run.
 */
class LiveShelfTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a spotify song is downloaded as its youtube music copy at the best quality`() = runBlocking {
        assumeTrue(System.getenv("VALEN_LIVE") == "1")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val youtube = YoutubeAudio()
        val song = Track(ProviderId.SPOTIFY, "live", "Live check", "Valen", album = "Tests", durationMs = 0)
        val copy = Track(ProviderId.YOUTUBE_MUSIC, "FGBhQbmPwH8", "Live check", "Valen")
        val shelf = DiskShelf(
            root = File(dir, "Valen"),
            indexFile = File(dir, "downloads.json"),
            scope = scope,
            sourceFor = { copy },
            resolve = { youtube.resolve(it.id, AudioQuality.HIGH) },
            gapMs = 0L..0L,
        )
        val started = System.nanoTime()
        shelf.keep(Playlist("live", "Live", listOf(song)))
        val state = withTimeout(180_000) { shelf.state.first { it.playlists["live"]?.complete == true } }
        val file = shelf.fileFor(song)?.let(::File)
        println("missing: ${state.playlists["live"]?.missing}")
        println("kept ${file?.name} ${file?.length()} bytes in ${"%.1f".format((System.nanoTime() - started) / 1e9)} s")
        val index = File(dir, "downloads.json").readText()
        println(index.lines().filter { "codec" in it || "kbps" in it }.joinToString("\n"))
        assertTrue(file?.isFile == true)
        scope.cancel()
    }
}
