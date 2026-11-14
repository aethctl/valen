/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The player on wide layouts: a capsule floating over the bottom of the content, warmed by the
 * cover of what is playing. One line: the song, the transport, the position, then queue and
 * volume. As the window narrows it gives up volume first, then the times, then the rail.
 *
 * Cover and title open the stage.
 */
@Composable
fun PlayerBar(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    positionMs: Long,
    durationMs: Long,
    volume: Float,
    tones: ArtworkTones,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(tones.chrome, Motion.tint(), label = "dockTint")
    BoxWithConstraints(
        modifier = modifier
            .widthIn(max = Layout.dockMaxWidth)
            .fillMaxWidth()
            .height(Layout.dockHeight)
            .glass(Radii.pillShape, tint = tint),
    ) {
        val showVolume = maxWidth >= 860.dp
        val showTimes = maxWidth >= 640.dp
        val showRail = maxWidth >= 520.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = Space.step3, end = Space.step4),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .tap(onClick = onOpen, shape = Radii.pillShape)
                    .padding(end = Space.step3),
            ) {
                ArtworkSquare(url = track.artworkUrl, size = 48.dp, measure = true)
                Column(modifier = Modifier.padding(start = Space.step3)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ValenColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${track.artist}  ·  ${track.provider.short}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ValenColors.Text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TransportBar(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                onPrevious = onPrevious,
                onToggle = onToggle,
                onNext = onNext,
                playSize = 34.dp,
                skipSize = 26.dp,
                gap = 0.dp,
            )
            if (showRail) {
                Spacer(Modifier.width(Space.step3))
                InlineSeek(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = onSeek,
                    showTimes = showTimes,
                    modifier = Modifier.weight(1.25f),
                )
            }
            Spacer(Modifier.width(Space.step2))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.step1),
            ) {
                IconTap(icon = Icons.AutoMirrored.Rounded.QueueMusic, label = "Queue", onClick = onQueue)
                if (showVolume) {
                    VolumeSlider(volume = volume, onVolume = onVolume, railWidth = 88.dp)
                }
            }
        }
    }
}
