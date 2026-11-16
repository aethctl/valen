/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.Playlist
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * A playlist as a cover on a shelf: the list's own artwork, its name, and what it holds. The
 * cover falls back to the first song's, because a service often has no image for a list.
 */
@Composable
fun PlaylistCard(
    playlist: Playlist,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteraction()
    Column(
        modifier = modifier
            .width(size)
            .pasted(seed = playlist.id.hashCode(), source = source)
            .hoverLift(source)
            .pressScale(source)
            .tap(onClick = onClick, shape = Radii.cardShape, source = source, rest = ValenColors.Background),
    ) {
        PlaylistCover(playlist = playlist, size = size, shape = Radii.cardShape)
        Spacer(Modifier.height(Space.step3))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleMedium,
            color = ValenColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Space.step1),
        )
        Text(
            text = playlistDetail(playlist),
            style = MaterialTheme.typography.bodySmall,
            color = ValenColors.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.step1, end = Space.step1, bottom = Space.step2),
        )
    }
}

/** "12 songs · Spotify": the one line under a playlist's name, wherever it is shown. */
fun playlistDetail(playlist: Playlist): String {
    val count = playlist.tracks.size
    val songs = when (count) {
        0 -> "No songs"
        1 -> "1 song"
        else -> "$count songs"
    }
    val service = playlist.source?.provider?.label
    return if (service != null) "$songs · $service" else songs
}
