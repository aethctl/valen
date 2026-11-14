/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * Volume is a preference, so it is written once, when the finger lifts. The rail previews the
 * level while dragging. With no [railWidth] the rail takes the room it is given.
 */
@Composable
fun VolumeSlider(
    volume: Float,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    railWidth: Dp? = 96.dp,
) {
    var preview by remember { mutableStateOf<Float?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.step2),
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeDown,
            contentDescription = null,
            tint = ValenColors.Text2,
            modifier = Modifier.size(20.dp),
        )
        ProgressRail(
            value = preview ?: volume,
            enabled = enabled,
            onScrub = { preview = it },
            onCommit = { fraction ->
                preview = null
                onVolume(fraction)
            },
            modifier = if (railWidth != null) Modifier.width(railWidth) else Modifier.weight(1f),
            height = 28.dp,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = null,
            tint = ValenColors.Text2,
            modifier = Modifier.size(20.dp),
        )
    }
}
