/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.Playlist
import app.valen.core.ProviderId
import app.valen.core.Track
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Kept playlists: real tagged files in their places, Spotify never asked, a 429 rested through. */
class DiskShelfTest {

    @TempDir
    lateinit var dir: File

    // A thread per request, as a real server: the default answers one request at a time.
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
        it.executor = java.util.concurrent.Executors.newCachedThreadPool()
        it.start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() {
        scope.cancel()
        server.stop(0)
    }

    private fun opus(): ByteArray {
        val ffmpeg = DiskShelf.tool("ffmpeg", "VALEN_FFMPEG_BIN")
        assumeTrue(ffmpeg != null, "needs FFmpeg")
        val out = File(dir, "src.webm")
        val p = ProcessBuilder(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=d=4", "-c:a", "libopus", "-b:a", "160k", out.path)
            .redirectErrorStream(true).start()
        p.waitFor(30, TimeUnit.SECONDS)
        assumeTrue(out.length() > 16 * 1024, "FFmpeg could not make a test song")
        return out.readBytes()
    }

    private fun serve(path: String, bytes: ByteArray, throttleFirst: Int = 0): AtomicInteger {
        val hits = AtomicInteger()
        server.createContext(path) { ex ->
            if (hits.incrementAndGet() <= throttleFirst) {
                ex.responseHeaders.add("Retry-After", "0")
                ex.sendResponseHeaders(429, -1)
                ex.close()
                return@createContext
            }
            val range = ex.requestHeaders.getFirst("Range")
            if (range != null) {
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
        return hits
    }

    @Test
    fun `a kept list becomes tagged files, the spotify song as its youtube copy`() = runBlocking {
        val audio = opus()
        val hits = serve("/song", audio, throttleFirst = 1)
        val spotify = Track(ProviderId.SPOTIFY, "sp1", "Get Lucky", "Daft Punk, Pharrell Williams", album = "Random Access Memories", durationMs = 4_000)
        val youtube = Track(ProviderId.YOUTUBE_MUSIC, "yt1", "Get Lucky", "Daft Punk", durationMs = 4_000)
        val local = Track(ProviderId.LOCAL, "/somewhere/else.flac", "Mine", "Me")
        val asked = mutableListOf<Track>()
        val root = File(dir, "Music/Valen")
        val shelf = DiskShelf(
            root = root,
            indexFile = File(dir, "downloads.json"),
            scope = scope,
            sourceFor = { t -> asked += t; if (t.provider == ProviderId.SPOTIFY) youtube else t },
            resolve = { copy ->
                assertEquals(ProviderId.YOUTUBE_MUSIC, copy.provider, "only a downloadable copy is ever resolved")
                ResolvedStream("$base/song")
            },
            gapMs = 0L..0L,
        )
        val list = Playlist("pl-1", "Night drive", listOf(spotify, local))
        shelf.keep(list)
        withTimeout(60_000) { shelf.state.first { it.playlists["pl-1"]?.complete == true } }

        val file = File(shelf.fileFor(spotify)!!)
        assertEquals(File(root, "Daft Punk/Random Access Memories/Get Lucky.opus"), file)
        assertTrue(file.isFile)
        assertEquals(shelf.fileFor(spotify), shelf.fileFor(youtube), "the copy is kept under its own key too")
        assertEquals(1, shelf.state.value.files, "one file, however many keys name it")
        assertTrue(hits.get() >= 2, "the 429 was rested through and asked again")
        val m3u = File(root, "Playlists/Night drive.m3u8").readText()
        assertTrue("../Daft Punk/Random Access Memories/Get Lucky.opus" in m3u)
        assertTrue("/somewhere/else.flac" in m3u)

        val ffprobe = DiskShelf.tool("ffprobe", "VALEN_FFPROBE_BIN")
        if (ffprobe != null) {
            val p = ProcessBuilder(ffprobe, "-v", "quiet", "-show_entries", "format_tags=title,artist,album:stream_tags=title,artist,album:stream=codec_name", "-of", "default=nw=1", file.path).start()
            val tags = p.inputStream.bufferedReader().readText()
            assertTrue("codec_name=opus" in tags, tags)
            assertTrue("TAG:title=Get Lucky" in tags, tags)
            assertTrue("TAG:album=Random Access Memories" in tags, tags)
        }

        // A second shelf on the same index knows the song is here.
        val again = DiskShelf(File(dir, "Music/Valen"), File(dir, "downloads.json"), scope, { null }, { error("not asked") }, gapMs = 0L..0L)
        assertNotNull(again.fileFor(spotify))

        shelf.forget("pl-1")
        assertFalse(file.exists(), "a forgotten list takes its songs with it")
        assertFalse(File(root, "Daft Punk").exists(), "and the folders it made")
        assertEquals(null, shelf.fileFor(spotify))
    }

    @Test
    fun `a phone's shelf tags with valen's own writers and works outside its music folder`() = runBlocking {
        val audio = opus()
        serve("/phone", audio)
        val song = Track(ProviderId.YOUTUBE_MUSIC, "yt2", "Instant Crush", "Daft Punk", album = "Random Access Memories", durationMs = 4_000)
        val root = File(dir, "storage/Music/Valen")
        val work = File(dir, "cache/downloads")
        val written = java.util.Collections.synchronizedList(mutableListOf<File>())
        val shelf = DiskShelf(
            root = root,
            indexFile = File(dir, "files/downloads.json"),
            scope = scope,
            sourceFor = { it },
            resolve = { ResolvedStream("$base/phone") },
            finisher = PlainFinisher(),
            work = work,
            onWritten = { written += it },
            gapMs = 0L..0L,
        )
        shelf.keep(Playlist("pl-2", "Phone", listOf(song)))
        withTimeout(60_000) { shelf.state.first { it.playlists["pl-2"]?.complete == true } }
        val file = File(shelf.fileFor(song)!!)
        assertEquals(File(root, "Daft Punk/Random Access Memories/Instant Crush.opus"), file)
        assertTrue(String(file.readBytes().copyOfRange(0, 4)) == "OggS", "re-wrapped from WebM into Ogg")
        assertFalse(File(root, ".partial").exists(), "nothing unfinished in the music folder")
        assertTrue(work.listFiles().orEmpty().none { it.isFile }, "and nothing left in the scratch folder")
        assertTrue(file in written && File(root, "Playlists/Phone.m3u8") in written, "the media index hears of both: $written")
    }

    @Test
    fun `a song kept by itself comes and goes, but stays while a kept list has it`() = runBlocking {
        serve("/one", opus())
        val a = Track(ProviderId.YOUTUBE_MUSIC, "a1", "Alone", "Solo", durationMs = 4_000)
        val shelf = DiskShelf(File(dir, "V"), File(dir, "d.json"), scope, { it }, { ResolvedStream("$base/one") }, gapMs = 0L..0L)
        shelf.keepSong(a)
        withTimeout(60_000) { shelf.state.first { a.key in it.kept && it.fetching.isEmpty() } }
        assertTrue(a.key in shelf.state.value.singles)
        val file = File(shelf.fileFor(a)!!)
        assertTrue(file.isFile)
        // Kept by a list too: letting the single go leaves the file.
        shelf.keep(Playlist("p", "P", listOf(a)))
        shelf.forgetSong(a)
        assertTrue(file.isFile, "a kept list still has it")
        shelf.keepSong(a)
        shelf.forget("p")
        assertTrue(file.isFile, "and the single still has it")
        shelf.forgetSong(a)
        assertFalse(file.exists(), "nothing needs it any more")
    }

    @Test
    fun `two songs come in at once, and a copy already here is not fetched again`() = runBlocking {
        val audio = opus()
        val open = AtomicInteger()
        val most = AtomicInteger()
        val hits = AtomicInteger()
        server.createContext("/slow") { ex ->
            hits.incrementAndGet()
            val now = open.incrementAndGet()
            most.accumulateAndGet(now) { x, y -> maxOf(x, y) }
            Thread.sleep(400)
            ex.sendResponseHeaders(200, audio.size.toLong())
            ex.responseBody.use { it.write(audio) }
            open.decrementAndGet()
        }
        val songs = (1..3).map { Track(ProviderId.YOUTUBE_MUSIC, "s$it", "Song $it", "Band", durationMs = 4_000) }
        val spotify = Track(ProviderId.SPOTIFY, "sp", "Song 1", "Band", durationMs = 4_000)
        val shelf = DiskShelf(
            File(dir, "V"), File(dir, "d.json"), scope,
            sourceFor = { if (it.provider == ProviderId.SPOTIFY) songs[0] else it },
            resolve = { ResolvedStream("$base/slow?" + it.id) },
            gapMs = 0L..0L,
        )
        shelf.keep(Playlist("p", "P", songs))
        withTimeout(60_000) { shelf.state.first { it.playlists["p"]?.complete == true } }
        assertEquals(2, most.get(), "two lanes, never more")
        val before = hits.get()
        shelf.keepSong(spotify)
        withTimeout(30_000) { shelf.state.first { spotify.key in it.kept } }
        assertEquals(before, hits.get(), "the Spotify song is the YouTube copy already kept")
        assertEquals(shelf.fileFor(songs[0]), shelf.fileFor(spotify))
        assertEquals(3, shelf.state.value.files)
    }

    @Test
    fun `a song with no copy anywhere is reported, not retried forever`() = runBlocking {
        val shelf = DiskShelf(File(dir, "V"), File(dir, "d.json"), scope, sourceFor = { null }, resolve = { error("never") }, gapMs = 0L..0L)
        val song = Track(ProviderId.SPOTIFY, "x", "Rare", "Nobody")
        shelf.keep(Playlist("p", "P", listOf(song)))
        val state = withTimeout(10_000) { shelf.state.first { it.playlists["p"]?.complete == true } }
        assertEquals(1, state.playlists.getValue("p").missing.size)
    }

    @Test
    fun `names are safe on any disk and covers ask for their largest size`() {
        assertEquals("AC_DC", DiskShelf.clean("AC/DC"))
        assertEquals("hidden", DiskShelf.clean("..hidden"))
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            DiskShelf.largest("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"),
        )
        assertEquals("https://i1.sndcdn.com/artworks-1-t500x500.jpg", DiskShelf.largest("https://i1.sndcdn.com/artworks-1-large.jpg"))
    }
}
