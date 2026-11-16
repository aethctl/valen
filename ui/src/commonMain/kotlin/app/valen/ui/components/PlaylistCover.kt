/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.Playlist
import app.valen.ui.theme.ValenColors

/**
 * A playlist's cover, wherever a list is shown. A cover the list was given wins; "Liked songs" is
 * a heart filled to the brim, in the one colour; a list made by hand with four or more different
 * covers shows the first four as one square, the way a mixtape's sleeve is a collage; anything
 * else shows its first song's cover.
 */
@Composable
fun PlaylistCover(
    playlist: Playlist,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp,
) {
    when {
        playlist.isLiked -> LikedCover(size = size, shape = shape, modifier = modifier)
        playlist.artworkUrl == null && playlist.isOwn && mosaicOf(playlist).size == 4 -> {
            val four = mosaicOf(playlist)
            Column(modifier = modifier.size(size).clip(shape).background(ValenColors.Skeleton)) {
                for (row in 0..1) {
                    Row(Modifier.weight(1f)) {
                        for (col in 0..1) {
                            Artwork(
                                url = four[row * 2 + col],
                                shape = RectangleShape,
                                contentDescription = null,
                                iconSize = iconSize / 2,
                                modifier = Modifier.weight(1f).fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        else -> Artwork(
            url = coverOf(playlist),
            shape = shape,
            contentDescription = null,
            iconSize = iconSize,
            modifier = modifier.size(size),
        )
    }
}

/** The one picture that stands for a list: its own, or its first song's. */
fun coverOf(playlist: Playlist): String? = playlist.artworkUrl ?: playlist.tracks.firstNotNullOfOrNull { it.artworkUrl }

/** The first four different covers of a list, or fewer when it does not have four. */
private fun mosaicOf(playlist: Playlist): List<String> =
    playlist.tracks.asSequence().mapNotNull { it.artworkUrl }.distinct().take(4).toList()

/** "Liked songs": the container tone with a heart in the primary, the glass of the like filled up. */
@Composable
private fun LikedCover(size: Dp, shape: Shape, modifier: Modifier) {
    val heart = rememberVectorPainter(Icons.Rounded.Favorite)
    val primary = ValenColors.Primary
    val ink = remember(primary) { ColorFilter.tint(primary) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).clip(shape).background(ValenColors.Container),
    ) {
        Canvas(Modifier.size(size * 0.46f)) {
            with(heart) { draw(this@Canvas.size, colorFilter = ink) }
        }
    }
}
