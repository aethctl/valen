/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.valen.audio.SoundcloudAudio
import app.valen.audio.SoundcloudTrack
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLink
import app.valen.core.PlaylistRef
import app.valen.core.ProviderId
import app.valen.core.ReadPlaylist
import app.valen.core.Track
import kotlinx.coroutines.CancellationException

/** One of SoundCloud's rows as a song the queue can play. */
internal fun SoundcloudTrack.toValenTrack(): Track = Track(
    provider = ProviderId.SOUNDCLOUD,
    id = id,
    title = title,
    artist = artist,
    artworkUrl = artworkUrl,
    durationMs = durationMs,
    uri = "soundcloud:track:$id",
    link = permalink,
)

/**
 * SoundCloud sets: a pasted set is public data read with the runtime client id, while the account's
 * own sets need its OAuth token ([oauth], read from the browser's cookie when one is signed in).
 * Reading never opens a browser: with no token the public sets still import, and the account list
 * says what is missing.
 */
class SoundcloudPlaylistCatalog(
    private val audio: SoundcloudAudio,
    private val oauth: (suspend () -> String?)? = null,
) : PlaylistCatalog {

    override val provider: ProviderId = ProviderId.SOUNDCLOUD

    override suspend fun playlists(): List<PlaylistRef> {
        val token = tokenOrNull()
            ?: throw IllegalStateException("Sign in to SoundCloud to see your playlists")
        return audio.accountPlaylists(token).mapNotNull { set ->
            PlaylistRef(
                name = set.name,
                trackCount = set.trackCount,
                artworkUrl = set.artworkUrl,
                link = PlaylistLink(provider, set.id, set.permalink ?: return@mapNotNull null),
            )
        }
    }

    override suspend fun read(link: PlaylistLink): ReadPlaylist {
        val set = audio.playlist(link.url, tokenOrNull())
        if (set.tracks.isEmpty() && set.trackCount > 0) {
            throw IllegalStateException("SoundCloud did not send the songs of that set")
        }
        return ReadPlaylist(
            name = set.name,
            tracks = set.tracks.map { it.toValenTrack() },
            artworkUrl = set.artworkUrl,
            note = if (set.truncated) {
                "Only ${set.tracks.size} of the ${set.trackCount} songs in this set could be read"
            } else {
                null
            },
            link = PlaylistLink(provider, set.id, set.permalink ?: link.url),
        )
    }

    /** A public read works without a session; a token helper that cannot answer is no token. */
    private suspend fun tokenOrNull(): String? = try {
        oauth?.invoke()?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
