/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.spotify

import app.myspoti.api.Track as SpotifyTrack
import app.valen.core.ProviderId
import app.valen.core.Track

/**
 * One Spotify catalogue row as Valen's song. A playlist may also hold podcast episodes, which are
 * not songs and cannot fill a song's place in the queue: those answer null and are reported by the
 * reader instead of being relabelled as music.
 */
internal fun SpotifyTrack.toValenTrack(): Track? {
    if (!uri.startsWith("spotify:track:")) return null
    return Track(
        provider = ProviderId.SPOTIFY,
        id = uri.substringAfterLast(':'),
        title = name,
        artist = artists.joinToString(", ") { it.name },
        album = album?.name?.takeIf { it.isNotBlank() },
        // The largest cover Spotify offers, as Orpheon's album images carry it.
        artworkUrl = album?.images?.maxByOrNull { it.width ?: 0 }?.url,
        durationMs = durationMs,
        explicit = explicit,
        uri = uri,
    )
}
