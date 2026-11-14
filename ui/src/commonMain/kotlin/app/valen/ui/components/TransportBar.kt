/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import app.valen.ui.theme.Layout
import app.valen.ui.theme.ValenColors

/**
 * Previous, play/pause, next, as bare glyphs: no disc behind the play button. Size is the
 * hierarchy; on the stage the play glyph is the largest thing on screen after the cover.
 */
@Composable
fun TransportBar(
    isPlaying: Boolean,
    isBuffering: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    playSize: Dp = 36.dp,
    skipSize: Dp = 28.dp,
    gap: Dp = 4.dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        IconTap(
            icon = Icons.Rounded.SkipPrevious,
            label = "Previous",
            onClick = onPrevious,
            enabled = canSkipPrevious,
            tint = ValenColors.Text,
            iconSize = skipSize,
            size = max(Layout.hitTarget, skipSize + 16.dp),
        )
        val target = max(Layout.hitTarget, playSize + 16.dp)
        if (isBuffering) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(target)) {
                CircularProgressIndicator(
                    color = ValenColors.Text,
                    trackColor = ValenColors.Fill,
                    strokeWidth = if (playSize >= 56.dp) 3.dp else 2.dp,
                    modifier = Modifier.size(playSize * 0.62f),
                )
            }
        } else {
            val source = rememberInteraction()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(target)
                    .pressScale(source)
                    .tap(onClick = onToggle, shape = CircleShape, source = source),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = ValenColors.Text,
                    modifier = Modifier.size(playSize),
                )
            }
        }
        IconTap(
            icon = Icons.Rounded.SkipNext,
            label = "Next",
            onClick = onNext,
            enabled = canSkipNext,
            tint = ValenColors.Text,
            iconSize = skipSize,
            size = max(Layout.hitTarget, skipSize + 16.dp),
        )
    }
}
