/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CancellationException

/** The answer to an import attempt: the playlist now in the library, or why there is none. */
sealed interface ImportResult {
    data class Imported(val playlist: Playlist) : ImportResult

    /** A sentence for the person; nothing was stored. */
    data class Failed(val reason: String) : ImportResult
}

/**
 * Gets playlists into Valen's library: reads a pasted address or an account list through the
 * service that owns it, matches what cannot be played here onto the services that can, and stores
 * the result under the source's identity so importing the same list again refreshes it.
 *
 * It is the only place that knows both sides, so the UI never has to: a screen asks for the
 * account's playlists, imports one, and reads [library] for what exists.
 */
class PlaylistManager(
    val library: PlaylistLibrary,
    catalogs: List<PlaylistCatalog> = emptyList(),
    private val search: suspend (provider: ProviderId, query: String, limit: Int) -> List<Track>,
    private val playable: (Track) -> Boolean,
    /** The services to look on, in the user's order: the first one with the song wins. */
    private val priority: () -> List<ProviderId> = { ProviderId.services },
) {
    private val catalogs = catalogs.associateBy { it.provider }

    /** Whether a stored song's service can play it right now (a signed-out service cannot). */
    fun canPlay(track: Track): Boolean = playable(track)

    /** Whether this service's playlists can be read here at all. */
    fun canRead(provider: ProviderId): Boolean = provider in catalogs

    /**
     * One service's playlists, or the reason it could not be asked. A service that fails is
     * reported, never hidden behind an empty list: "no playlists" and "could not load" are
     * different things to the person looking at them.
     */
    suspend fun accountPlaylists(provider: ProviderId): PlaylistChoices {
        val catalog = catalogs[provider] ?: return PlaylistChoices(
            provider,
            error = "Reading ${provider.label} playlists is not available on this device",
        )
        return try {
            PlaylistChoices(provider, catalog.playlists())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlaylistChoices(provider, error = e.message ?: "Could not read your ${provider.label} playlists")
        }
    }

    /** Imports whatever playlist the pasted address names. */
    suspend fun importLink(input: String, onProgress: (ImportProgress) -> Unit = {}): ImportResult {
        val link = parsePlaylistLink(input) ?: return ImportResult.Failed(
            "That is not a Spotify, YouTube Music or SoundCloud playlist link, or a playlist file on this device",
        )
        return importFrom(link, onProgress)
    }

    /** Imports a playlist the service itself listed. */
    suspend fun importPlaylist(ref: PlaylistRef, onProgress: (ImportProgress) -> Unit = {}): ImportResult =
        importFrom(ref.link, onProgress)

    /** Reads a stored playlist's source again, keeping its place and id in the library. */
    suspend fun refresh(id: String, onProgress: (ImportProgress) -> Unit = {}): ImportResult {
        val playlist = library.get(id) ?: return ImportResult.Failed("That playlist is not in the library any more")
        val link = playlist.source?.link() ?: return ImportResult.Failed(
            "This playlist was made in Valen, so there is no source to refresh it from",
        )
        return importFrom(link, onProgress)
    }

    private suspend fun importFrom(link: PlaylistLink, onProgress: (ImportProgress) -> Unit): ImportResult {
        val catalog = catalogs[link.provider] ?: return ImportResult.Failed(
            "Reading ${link.provider.label} playlists is not available on this device",
        )
        val source = try {
            catalog.read(link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ImportResult.Failed(e.message ?: if (link.provider == ProviderId.LOCAL) "Could not read that playlist" else "Could not read that ${link.provider.label} playlist")
        }

        // A fresh importer per run: its cache is only good for the songs of this one import.
        val imported = PlaylistImporter(search, playable).import(source, priority(), onProgress)
        if (imported.tracks.isEmpty() && imported.unmatched.isEmpty()) {
            return ImportResult.Failed(
                if (link.provider == ProviderId.LOCAL) "That playlist has no songs Valen can read" else "That ${link.provider.label} playlist is empty",
            )
        }

        // The reader's own address wins: it is the list's identity, not the share link's.
        val resolved = source.link?.playlistId?.takeIf { it.isNotBlank() } ?: link.playlistId
        val address = source.link?.url ?: link.url
        val playlist = library.add(
            name = imported.name,
            tracks = imported.tracks,
            source = PlaylistSource(provider = link.provider, playlistId = resolved, url = address),
            artworkUrl = imported.artworkUrl,
            unmatched = imported.unmatched,
            note = imported.notes.firstOrNull(),
        )
        return ImportResult.Imported(playlist)
    }
}
