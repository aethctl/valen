/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.Track
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/** Every quick tile is this tall, and its cover is this square. */
val QuickTileHeight = 60.dp

/**
 * A song as a compact tile for a grid: the cover fills the tile's left end, edge to edge, and the
 * tile's own shape rounds it. Used for what comes next on Home.
 *
 * The cover-flush quick tile is Orpheon's home grid; see NOTICE.
 */
@Composable
fun QuickTile(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(QuickTileHeight)
            .tap(onClick = onClick, shape = Radii.rowShape, rest = ValenColors.Surface),
    ) {
        // Square corners here: the tile clips its own left end, so cover and tile share one curve.
        ArtworkSquare(url = track.artworkUrl, size = QuickTileHeight, shape = RectangleShape)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A song as a large cover on a shelf, its title and artist under it. Under a pointer a play mark
 * comes up in the cover's corner; on a touch screen the whole card is simply the button.
 */
@Composable
fun ShelfCard(
    track: Track,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteraction()
    val hovered by source.collectIsHoveredAsState()
    val mark by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = Motion.press(),
        label = "shelfPlayMark",
    )
    Column(
        modifier = modifier
            .width(size)
            .pressScale(source)
            .tap(onClick = onClick, shape = Radii.cardShape, source = source, rest = ValenColors.Background),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Artwork(
                url = track.artworkUrl,
                shape = Radii.cardShape,
                contentDescription = null,
                iconSize = 32.dp,
                modifier = Modifier.size(size),
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(Space.step3)
                    .size(44.dp)
                    .graphicsLayer {
                        alpha = mark
                        translationY = (1f - mark) * 6.dp.toPx()
                    }
                    .clip(CircleShape)
                    .background(ValenColors.Text),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = ValenColors.OnLight,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(Space.step3))
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleMedium,
            color = ValenColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Space.step1),
        )
        Text(
            text = "${track.artist}  ·  ${track.provider.short}",
            style = MaterialTheme.typography.bodySmall,
            color = ValenColors.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.step1, end = Space.step1, bottom = Space.step2),
        )
    }
}
