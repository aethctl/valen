/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.Track
import app.valen.ui.artwork.ArtworkTones
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * The phone's player: a capsule floating above the tab bar, warmed by the cover. Cover, one line
 * each, play and next. Position is a hairline along the bottom, inside the curve. Tapping
 * anywhere else opens the stage.
 */
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    canSkipNext: Boolean,
    positionMs: Long,
    durationMs: Long,
    tones: ArtworkTones,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val tint by animateColorAsState(tones.chrome, Motion.tint(), label = "miniTint")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Layout.miniPlayerHeight)
            .glass(Radii.pillShape, tint = tint)
            .drawWithContent {
                drawContent()
                // Inset by the capsule's own radius, so the line never touches the curve.
                val inset = size.height / 2f
                val thickness = 2.dp.toPx()
                val y = size.height - thickness - 5.dp.toPx()
                val full = size.width - inset * 2f
                val corner = CornerRadius(thickness / 2f, thickness / 2f)
                drawRoundRect(ValenColors.Fill, Offset(inset, y), Size(full, thickness), corner)
                if (progress > 0f) {
                    drawRoundRect(ValenColors.Text, Offset(inset, y), Size(full * progress, thickness), corner)
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .tap(onClick = onOpen)
                .padding(start = Space.step2, end = Space.step2),
        ) {
            ArtworkSquare(url = track.artworkUrl, size = 44.dp, measure = true)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Space.step3),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
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
            IconTap(
                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                label = if (isPlaying) "Pause" else "Play",
                onClick = onToggle,
                tint = ValenColors.Text,
                iconSize = 30.dp,
            )
            IconTap(
                icon = Icons.Rounded.SkipNext,
                label = "Next",
                onClick = onNext,
                enabled = canSkipNext,
                tint = ValenColors.Text,
                iconSize = 26.dp,
            )
        }
    }
}
