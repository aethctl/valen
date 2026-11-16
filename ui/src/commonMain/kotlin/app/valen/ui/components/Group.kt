/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * Rows that belong to one thing, on one surface step: the three services in Settings, the list a
 * service offers for import. The card clips what is inside it, so a row here does not round its
 * own corners ([PlaylistRow], [ServiceTile] take `contained` for that), and a hairline separates
 * rows of this group and nothing else.
 */
@Composable
fun Group(
    rows: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
    /** The surface step it stands on: the page's next one, or a white veil on glass. */
    fill: Color = ValenColors.Surface,
    shape: Shape = Radii.cardShape,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .padding(vertical = Space.step1),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.step4)
                        .height(1.dp)
                        .background(ValenColors.Hairline),
                )
            }
            row()
        }
    }
}
