/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.spotify

import app.myspoti.api.Playlist as SpotifyPlaylist
import app.myspoti.api.PlaylistSort
import app.myspoti.api.best
import app.myspoti.spotify.SpotifyApi
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLink
import app.valen.core.PlaylistRef
import app.valen.core.ProviderId
import app.valen.core.ReadPlaylist
import app.valen.core.Track
import app.valen.core.parsePlaylistLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Spotify playlists, read on the signed-in Web Player session (the vendored API's GraphQL reads).
 * The account's lists are the library's own; a pasted link is read whether or not it is in the
 * library, and a short `spotify.link` address is followed to the playlist it names.
 */
class SpotifyPlaylistCatalog(
    private val api: SpotifyApi,
    private val http: OkHttpClient,
    /** Waits for the Web Player (waking it first where it loads late); true once it is ready. */
    private val awaitReady: suspend () -> Boolean = {
        if (!api.ready.value) withTimeoutOrNull(8_000) { api.ready.first { it } }
        api.ready.value
    },
) : PlaylistCatalog {

    override val provider: ProviderId = ProviderId.SPOTIFY

    override suspend fun playlists(): List<PlaylistRef> {
        ready()
        return api.playlists.mine().map { it.toRef() }
    }

    override suspend fun read(link: PlaylistLink): ReadPlaylist {
        ready()
        val id = link.playlistId?.takeIf { it.isNotBlank() }
            ?: parsePlaylistLink(link.url)?.playlistId
            ?: resolveShortLink(link.url)
        val uri = "spotify:playlist:$id"
        val playlist = api.playlists.playlist(uri)
        val rows = api.playlists.sortedTracks(uri, PlaylistSort.CUSTOM)
        val tracks = ArrayList<Track>(rows.size)
        var skipped = 0
        for (row in rows) {
            val track = row.toValenTrack()
            if (track != null) tracks += track else skipped++
        }
        return ReadPlaylist(
            name = playlist.name,
            tracks = tracks,
            artworkUrl = playlist.images.best(300)?.url,
            note = when (skipped) {
                0 -> null
                1 -> "1 podcast episode in this playlist was skipped"
                else -> "$skipped podcast episodes in this playlist were skipped"
            },
            link = PlaylistLink(provider, id, "https://open.spotify.com/playlist/$id"),
        )
    }

    /** The same wait a search does: a search at startup often beats the Web Player's own start. */
    private suspend fun ready() {
        if (!awaitReady()) throw IllegalStateException("Spotify is still starting; try again in a moment")
    }

    /**
     * `spotify.link/…` answers with a redirect to the playlist's own page. The request is a plain
     * GET whose body is never read: only the address it lands on matters.
     */
    private suspend fun resolveShortLink(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", BROWSER_USER_AGENT).build()
        val final = http.newCall(request).execute().use { it.request.url.toString() }
        parsePlaylistLink(final)?.playlistId
            ?: throw IllegalStateException("That link does not lead to a Spotify playlist")
    }

    private fun SpotifyPlaylist.toRef(): PlaylistRef {
        val id = uri.substringAfterLast(':')
        return PlaylistRef(
            name = name,
            trackCount = trackCount,
            artworkUrl = images.best(300)?.url,
            owner = owner,
            link = PlaylistLink(provider, id, "https://open.spotify.com/playlist/$id"),
        )
    }

    private companion object {
        /** A plain desktop browser, so the short-link service answers with its redirect. */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}
