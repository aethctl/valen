/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.valen.audio.AudioPlayer
import app.valen.audio.AudioState
import app.valen.audio.ResolvedStream
import app.valen.core.PlaylistLink
import app.valen.core.ProviderId
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

private class Sink : AudioPlayer {
    val opened = mutableListOf<String>()
    override val state = MutableStateFlow(AudioState())
    override val canSeek = true
    override suspend fun play(stream: ResolvedStream, startMs: Long) {
        opened += stream.url
        state.value = AudioState(hasStream = true, playing = true, started = true)
    }
    override suspend fun pause() {}
    override suspend fun resume() {}
    override suspend fun seek(positionMs: Long) {}
    override suspend fun setVolume(volume: Float) {}
    override suspend fun stop() {}
    override suspend fun release() {}
}

/** Songs and lists from this device: tags read, missing files marked for matching, kept songs played. */
class LocalProviderTest {

    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() = scope.cancel()

    private fun song(file: File, title: String, artist: String) {
        val ffmpeg = FfprobeTags.findTool("ffmpeg", "VALEN_FFMPEG_BIN")
        assumeTrue(ffmpeg != null, "needs FFmpeg")
        file.parentFile.mkdirs()
        ProcessBuilder(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=d=2", "-metadata", "title=$title", "-metadata", "artist=$artist", "-metadata", "album=Tests", file.path)
            .redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS)
        assumeTrue(file.isFile)
    }

    private fun provider(sink: Sink = Sink(), kept: (Track) -> String? = { null }, known: List<Track> = emptyList()) = LocalProvider(
        playback = LocalProvider.playback(sink, scope, kept),
        tags = FfprobeTags(File(dir, "covers")),
        known = { known },
        musicFolder = dir,
    )

    @Test
    fun `a folder is a playlist of its songs, tags read, in natural order`() = runBlocking {
        val album = File(dir, "Album")
        song(File(album, "10 Ten.mp3"), "Ten", "Band")
        song(File(album, "2 Two.mp3"), "Two", "Band")
        File(album, "cover.jpg").writeBytes(ByteArray(10))
        val read = provider().read(PlaylistLink(ProviderId.LOCAL, album.path, album.path))
        assertEquals("Album", read.name)
        assertEquals(listOf("Two", "Ten"), read.tracks.map { it.title })
        assertEquals("Band", read.tracks.first().artist)
        assertEquals("Tests", read.tracks.first().album)
        assertTrue(read.tracks.first().durationMs in 1_500..2_500)
        assertEquals(File(album, "cover.jpg").toURI().toString(), read.tracks.first().artworkUrl)
        assertTrue(read.blocked.isEmpty())
    }

    @Test
    fun `a playlist's missing files stay listed and are marked for the services`() = runBlocking {
        song(File(dir, "here.mp3"), "Here", "Someone")
        val m3u = File(dir, "Mix.m3u8")
        m3u.writeText("#EXTM3U\n#EXTINF:200,Daft Punk - Get Lucky\n/gone/get-lucky.flac\nhere.mp3\n")
        val p = provider()
        val read = p.read(PlaylistLink(ProviderId.LOCAL, m3u.path, m3u.path))
        assertEquals("Mix", read.name)
        assertEquals(2, read.tracks.size)
        val gone = read.tracks[0]
        assertEquals("Get Lucky", gone.title)
        assertEquals("Daft Punk", gone.artist)
        assertEquals(setOf(gone.key), read.blocked)
        assertNotNull(read.note)
        assertEquals(listOf("Mix"), p.playlists().map { it.name })
    }

    @Test
    fun `a kept song of any service plays from its file, under its own name`() = runBlocking {
        val file = File(dir, "kept.opus").also { it.writeBytes(ByteArray(20_000)) }
        val spotify = Track(ProviderId.SPOTIFY, "sp", "Song", "Artist", uri = "spotify:track:sp")
        val sink = Sink()
        val p = provider(sink, kept = { if (it.key == spotify.key) file.path else null })
        p.play(spotify, 0)
        assertEquals(listOf(file.absolutePath), sink.opened)
        assertEquals(spotify, p.player.value?.track)
    }

    @Test
    fun `search looks through the device songs the library knows`() = runBlocking {
        val a = Track(ProviderId.LOCAL, "/m/a.flac", "Kerala", "Bonobo", album = "Migration")
        val b = Track(ProviderId.LOCAL, "/m/b.flac", "Cirrus", "Bonobo")
        val p = provider(known = listOf(a, b, Track(ProviderId.SPOTIFY, "x", "Kerala", "Bonobo")))
        assertEquals(listOf(a), p.search("kerala bonobo", 10))
        assertEquals(2, p.search("bonobo", 10).size)
    }
}
