/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.ProviderId
import app.valen.core.Track
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Songs kept on disk: fetched whole and right, never half, trimmed oldest first, and used. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiskAudioCacheTest {

    @TempDir
    lateinit var dir: File

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val ranges = AtomicInteger()

    @AfterEach
    fun stop() = server.stop(0)

    /** Serves [bytes] at [path], honouring `Range` unless [ignoreRanges]. */
    private fun serve(path: String, bytes: ByteArray, ignoreRanges: Boolean = false) {
        server.createContext(path) { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null && !ignoreRanges) {
                ranges.incrementAndGet()
                val (a, b) = range.removePrefix("bytes=").split('-').map { it.toLong() }
                val end = minOf(b, bytes.size - 1L)
                val part = bytes.copyOfRange(a.toInt(), end.toInt() + 1)
                ex.responseHeaders.add("Content-Range", "bytes $a-$end/${bytes.size}")
                ex.sendResponseHeaders(206, part.size.toLong())
                ex.responseBody.use { it.write(part) }
            } else {
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
    }

    private fun text(path: String, body: String) = serve(path, body.toByteArray(), ignoreRanges = true)

    private fun cache(max: Long = 100L * 1024 * 1024) = DiskAudioCache(dir, max)

    @Test
    fun `a progressive song is fetched in ranges into one exact file`() {
        val song = Random(1).nextBytes((DiskAudioCache.CHUNK_BYTES * 2 + 12_345).toInt())
        serve("/song.m4a", song)
        val c = cache()
        c.fetch("youtube_music-abc", ResolvedStream("$base/song.m4a"))
        assertArrayEquals(song, c.file("youtube_music-abc").readBytes())
        assertEquals(3, ranges.get(), "in 4 MB ranges")
        assertEquals(c.file("youtube_music-abc").absolutePath, c.offline("youtube_music-abc")?.url)
    }

    @Test
    fun `a server that ignores ranges is kept as it answers`() {
        val song = Random(2).nextBytes(300_000)
        serve("/whole.mp3", song, ignoreRanges = true)
        val c = cache()
        c.fetch("soundcloud-1", ResolvedStream("$base/whole.mp3"))
        assertArrayEquals(song, c.file("soundcloud-1").readBytes())
    }

    @Test
    fun `an HLS song becomes one file, init segment first, and a protected one is refused`() {
        val init = Random(3).nextBytes(20_000)
        val seg = List(3) { Random(10 + it).nextBytes(30_000) }
        serve("/hls/init.mp4", init, ignoreRanges = true)
        seg.forEachIndexed { i, b -> serve("/hls/s$i.m4s", b, ignoreRanges = true) }
        text("/hls/playlist.m3u8", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:10,\ns0.m4s\n#EXTINF:10,\ns1.m4s\n#EXTINF:10,\ns2.m4s\n#EXT-X-ENDLIST\n")
        val c = cache()
        c.fetch("soundcloud-2", ResolvedStream("$base/hls/playlist.m3u8"))
        assertArrayEquals(init + seg[0] + seg[1] + seg[2], c.file("soundcloud-2").readBytes())

        text("/drm/playlist.m3u8", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXTINF:10,\n/hls/s0.m4s\n")
        assertThrows(IOException::class.java) { c.fetch("soundcloud-3", ResolvedStream("$base/drm/playlist.m3u8")) }
        assertNull(c.offline("soundcloud-3"))
    }

    @Test
    fun `a fetch that fails leaves nothing behind, and an error page is not a song`() {
        server.createContext("/gone") { ex -> ex.sendResponseHeaders(403, -1); ex.close() }
        serve("/tiny", ByteArray(100), ignoreRanges = true)
        val c = cache()
        assertThrows(IOException::class.java) { c.fetch("youtube_music-x", ResolvedStream("$base/gone")) }
        assertThrows(IOException::class.java) { c.fetch("youtube_music-y", ResolvedStream("$base/tiny")) }
        assertNull(c.offline("youtube_music-x"))
        assertNull(c.offline("youtube_music-y"))
        assertTrue(dir.listFiles()!!.none { it.name.contains("part") }, dir.listFiles()!!.map { it.name }.toString())
    }

    @Test
    fun `the folder keeps under its size, dropping the songs played longest ago`() {
        val c = cache(max = 250_000)
        repeat(4) { i ->
            serve("/s$i", Random(20 + i).nextBytes(100_000), ignoreRanges = true)
        }
        c.fetch("k-0", ResolvedStream("$base/s0"))
        c.fetch("k-1", ResolvedStream("$base/s1"))
        c.file("k-0").setLastModified(1_000L)
        c.file("k-1").setLastModified(2_000L)
        // Played again: now the most recent.
        assertNotNull(c.offline("k-0"))
        c.fetch("k-2", ResolvedStream("$base/s2"))
        assertNull(c.offline("k-1"), "the one played longest ago went")
        assertNotNull(c.offline("k-0"))
        assertTrue(c.size() <= 250_000)
    }

    @Test
    fun `a kept song plays without being looked up, and a damaged one is dropped and looked up`() = runTest {
        val song = Random(4).nextBytes(50_000)
        serve("/a", song, ignoreRanges = true)
        val c = cache()
        val track = Track(ProviderId.YOUTUBE_MUSIC, "abc", "A", "X", durationMs = 200_000, uri = "ytmusic:track:abc")
        c.fetch("youtube_music-abc", ResolvedStream("$base/a"))

        val sink = object : AudioPlayer {
            val plays = mutableListOf<String>()
            val st = MutableStateFlow(AudioState())
            override val state: StateFlow<AudioState> = st
            override val canSeek = true
            override suspend fun play(stream: ResolvedStream, startMs: Long) {
                plays += stream.url
                st.value = AudioState(hasStream = true, playing = true, positionMs = startMs, durationMs = 200_000)
            }
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun seek(positionMs: Long) {}
            override suspend fun setVolume(volume: Float) {}
            override suspend fun stop() { st.value = AudioState() }
            override suspend fun release() {}
        }
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var lookups = 0
        val playback = NativePlayback(sink, scope, cache = c) { _, _ -> lookups++; ResolvedStream("$base/a") }
        playback.play(track, 0)
        assertEquals(0, lookups, "no lookup for a kept song")
        assertEquals(c.file("youtube_music-abc").absolutePath, sink.plays.single())

        // The kept file will not open: it is dropped, and the song is looked up and streamed.
        sink.st.value = AudioState(hasStream = true, error = "Invalid data found when processing input", durationMs = 200_000)
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        assertEquals(1, lookups)
        assertEquals("$base/a", sink.plays.last())
        assertTrue(!c.file("youtube_music-abc").exists() || sink.plays.size == 2)
        scope.cancel()
    }
}
