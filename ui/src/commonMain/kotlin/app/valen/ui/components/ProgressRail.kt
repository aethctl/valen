/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Radii
import app.valen.ui.theme.ValenColors

/**
 * How far a long job has got, as a thin rail. A rail rather than a spinner because importing a
 * playlist names the work it is doing beside it, and a count without a shape to fill reads as a
 * stalled list.
 */
@Composable
fun ProgressRail(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(Radii.pillShape)
            .background(ValenColors.Fill),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(Radii.pillShape)
                .background(ValenColors.Primary),
        )
    }
}
