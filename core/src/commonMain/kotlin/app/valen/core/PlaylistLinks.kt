/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

/**
 * A playlist address as a person pastes it: a service link, or the service's own uri form.
 * [playlistId] is filled when the address says which playlist it is; a short link (a
 * `spotify.link` or `on.soundcloud.com` code) leaves it null and the reader resolves [url].
 */
data class PlaylistLink(
    val provider: ProviderId,
    val playlistId: String? = null,
    val url: String,
)

/**
 * Reads a pasted playlist address. Only the three services Valen can read are recognised: any
 * other host is not a playlist this app can open, and the caller says so instead of searching
 * the text.
 *
 * The same list is recognised however its link was shared: a locale segment in a Spotify path
 * (`/intl-de/playlist/…`), a `si=` share token, a `music.youtube.com` or plain `youtube.com`
 * host, and the `spotify:playlist:` uri are all the same address.
 */
fun parsePlaylistLink(input: String): PlaylistLink? {
    val text = input.trim()
    if (text.isEmpty()) return null

    // A playlist file or a folder on this device.
    if (looksLikeLocalPath(text)) return localPlaylistLink(text)

    // The service's own uri form, before any URL parsing.
    if (text.startsWith("spotify:playlist:", ignoreCase = true)) {
        val id = text.substringAfter("spotify:playlist:").substringBefore('?').trim()
        return id.takeIf { it.isNotEmpty() }?.let { PlaylistLink(ProviderId.SPOTIFY, it, "spotify:playlist:$it") }
    }

    val url = if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://$text"
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return null
    val rest = url.substring(schemeEnd + 3)
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        .lowercase()
        .removePrefix("www.")
        .removePrefix("m.")
    val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
    val query = rest.substringAfter('?', "").substringBefore('#')

    return when {
        host == "open.spotify.com" || host == "play.spotify.com" -> spotifyLink(path, url)
        host == "spotify.link" || host == "spoti.fi" -> PlaylistLink(ProviderId.SPOTIFY, null, url)
        host == "music.youtube.com" || host == "youtube.com" || host == "youtu.be" ->
            youtubeLink(query, url)
        host == "soundcloud.com" -> soundcloudLink(path, url)
        host == "on.soundcloud.com" -> PlaylistLink(ProviderId.SOUNDCLOUD, null, url)
        else -> null
    }
}

/**
 * `open.spotify.com/playlist/{id}`, with any locale segment in front of `playlist`. An album or
 * artist link is not a playlist and is refused here rather than at the reader.
 */
private fun spotifyLink(path: String, url: String): PlaylistLink? {
    val segments = path.split('/').filter { it.isNotEmpty() }
    val index = segments.indexOfFirst { it == "playlist" }
    if (index < 0) return null
    val id = segments.getOrNull(index + 1)?.takeIf { it.isNotBlank() } ?: return null
    return PlaylistLink(ProviderId.SPOTIFY, id, url)
}

/**
 * A YouTube Music playlist is its `list` parameter; `/playlist?list=` and `watch?v=…&list=…` both
 * name one. Generated mixes (`RD…`) are playlists too — YouTube Music lists them as such.
 */
private fun youtubeLink(query: String, url: String): PlaylistLink? {
    val list = query.split('&')
        .firstOrNull { it.startsWith("list=") }
        ?.substringAfter('=')
        ?.let(::decodeQueryPart)
        ?.removePrefix("VL")
        ?.takeIf { it.isNotBlank() }
    return list?.let { PlaylistLink(ProviderId.YOUTUBE_MUSIC, it, url) }
}

/** `soundcloud.com/{user}/sets/{slug}`; a bare track link is not a playlist. */
private fun soundcloudLink(path: String, url: String): PlaylistLink? {
    val segments = path.split('/').filter { it.isNotEmpty() }
    val sets = segments.indexOfFirst { it == "sets" }
    if (sets <= 0 || segments.size <= sets + 1) return null
    return PlaylistLink(ProviderId.SOUNDCLOUD, segments.drop(sets).joinToString("/"), url)
}

/** Percent-decoding for the few characters a share link escapes in a query value. */
private fun decodeQueryPart(value: String): String =
    value.replace("%3D", "=", ignoreCase = true)
        .replace("%26", "&", ignoreCase = true)
