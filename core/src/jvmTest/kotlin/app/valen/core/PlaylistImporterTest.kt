/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Porting a playlist into Valen: what can be played is kept, what cannot is looked for, and what
 * is not found stays a gap instead of becoming a wrong song.
 */
class PlaylistImporterTest {

    private fun track(
        provider: ProviderId,
        id: String,
        title: String,
        artist: String,
        album: String? = "Album",
        durationMs: Long = 369_000,
    ) = Track(provider, id, title, artist, album = album, durationMs = durationMs)

    private val youtube = track(ProviderId.YOUTUBE_MUSIC, "yt-1", "Get Lucky", "Daft Punk")
    private val soundcloud = track(ProviderId.SOUNDCLOUD, "sc-1", "Get Lucky", "Daft Punk")
    private val spotify = track(ProviderId.SPOTIFY, "sp-1", "Get Lucky", "Daft Punk")

    @Test
    fun `a track its own service can play is kept without a search`() = runBlocking {
        var searches = 0
        val importer = PlaylistImporter(
            search = { _, _, _ -> searches++; emptyList() },
            canPlay = { it.provider != ProviderId.SPOTIFY },
        )

        val result = importer.import(ReadPlaylist("Discovery", listOf(youtube, soundcloud)), ProviderId.entries)

        assertEquals(listOf(youtube, soundcloud), result.tracks)
        assertEquals(0, searches, "a playable track must not cost a search")
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun `a track that cannot be played is matched on the first service that has it`() = runBlocking {
        val wrong = track(ProviderId.YOUTUBE_MUSIC, "yt-cover", "Get Lucky", "The Lounge Kittens")
        val right = track(ProviderId.YOUTUBE_MUSIC, "yt-2", "Get Lucky (Official Video)", "Daft Punk", durationMs = 370_000)
        val asked = mutableListOf<ProviderId>()
        val importer = PlaylistImporter(
            search = { provider, _, _ -> asked += provider; if (provider == ProviderId.YOUTUBE_MUSIC) listOf(wrong, right) else emptyList() },
            canPlay = { it.provider != ProviderId.SPOTIFY },
        )

        val result = importer.import(ReadPlaylist("Mine", listOf(spotify)), listOf(ProviderId.YOUTUBE_MUSIC, ProviderId.SOUNDCLOUD))

        assertEquals(listOf(right), result.tracks)
        assertEquals(listOf(ProviderId.YOUTUBE_MUSIC), asked, "the first service with a match ends the search")
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun `a song no service has is reported, not replaced by a wrong one`() = runBlocking {
        val importer = PlaylistImporter(
            search = { _, _, _ -> listOf(track(ProviderId.YOUTUBE_MUSIC, "x", "Unrelated", "Nobody")) },
            canPlay = { it.provider != ProviderId.SPOTIFY },
        )

        val result = importer.import(ReadPlaylist("Mine", listOf(spotify)), listOf(ProviderId.YOUTUBE_MUSIC))

        assertTrue(result.tracks.isEmpty())
        assertEquals(listOf(UnmatchedEntry("Get Lucky", "Daft Punk")), result.unmatched)
    }

    @Test
    fun `a service that fails does not fail the import`() = runBlocking {
        val importer = PlaylistImporter(
            search = { provider, _, _ ->
                if (provider == ProviderId.SOUNDCLOUD) throw IllegalStateException("SoundCloud is down")
                listOf(youtube)
            },
            canPlay = { it.provider != ProviderId.SPOTIFY },
        )

        val result = importer.import(
            ReadPlaylist("Mine", listOf(spotify)),
            listOf(ProviderId.SOUNDCLOUD, ProviderId.YOUTUBE_MUSIC),
        )

        assertEquals(listOf(youtube), result.tracks)
        assertTrue(result.unmatched.isEmpty())
    }

    @Test
    fun `the same song twice costs one search`() = runBlocking {
        var searches = 0
        val importer = PlaylistImporter(
            search = { _, _, _ -> searches++; listOf(youtube) },
            canPlay = { it.provider != ProviderId.SPOTIFY },
        )

        val result = importer.import(ReadPlaylist("Mine", listOf(spotify, spotify)), listOf(ProviderId.YOUTUBE_MUSIC))

        assertEquals(2, result.tracks.size, "a playlist may list a song twice; the import keeps both")
        assertEquals(1, searches)
    }

    @Test
    fun `a cancellation stays a cancellation`() = runBlocking {
        val importer = PlaylistImporter(
            search = { _, _, _ -> throw CancellationException("the screen went away") },
            canPlay = { it.provider != ProviderId.SPOTIFY },
        )

        val thrown = runCatching {
            importer.import(ReadPlaylist("Mine", listOf(spotify)), listOf(ProviderId.YOUTUBE_MUSIC))
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException, "a cancelled import must not look like a finished one")
    }

    @Test
    fun `progress counts every source song and every one kept`() = runBlocking {
        val seen = mutableListOf<ImportProgress>()
        val importer = PlaylistImporter(
            search = { _, _, _ -> listOf(youtube) },
            canPlay = { it.provider != ProviderId.SPOTIFY },
        )

        importer.import(
            ReadPlaylist("Mine", listOf(spotify, youtube, spotify)),
            listOf(ProviderId.YOUTUBE_MUSIC),
        ) { seen += it }

        assertEquals(3, seen.size)
        assertEquals(ImportProgress(3, 3, 3, seen.last().label), seen.last())
        assertEquals(1, seen.first().processed)
    }

    @Test
    fun `a reader's note and cover travel with the import`() = runBlocking {
        val importer = PlaylistImporter(search = { _, _, _ -> emptyList() }, canPlay = { true })

        val result = importer.import(
            ReadPlaylist("Mine", listOf(youtube), artworkUrl = "https://example.test/cover.jpg", note = "Only 30 songs are shared"),
            ProviderId.entries,
        )

        assertEquals("https://example.test/cover.jpg", result.artworkUrl)
        assertEquals(listOf("Only 30 songs are shared"), result.notes)
    }

    @Test
    fun `a row the service cannot play is matched elsewhere instead of kept`() = runBlocking {
        val blocked = track(ProviderId.YOUTUBE_MUSIC, "yt-gone", "Get Lucky", "Daft Punk")
        var searches = 0
        val importer = PlaylistImporter(
            search = { _, _, _ -> searches++; listOf(soundcloud) },
            canPlay = { it.provider != ProviderId.SPOTIFY },
        )

        val result = importer.import(
            ReadPlaylist("Mine", listOf(blocked), blocked = setOf(blocked.key)),
            listOf(ProviderId.SOUNDCLOUD),
        )

        assertEquals(listOf(soundcloud), result.tracks)
        assertEquals(1, searches)
    }
}
