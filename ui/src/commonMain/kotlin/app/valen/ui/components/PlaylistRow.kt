/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * A playlist as a row: its cover, its name and what it holds, with the action the list that shows
 * it needs on the right. Used where the lists are chosen rather than browsed (importing from an
 * account), so it stays one line tall and lets the action name itself. [contained] is for a row
 * inside a [Group]: the card does the clipping, so the row's own hover fill stays square.
 */
@Composable
fun PlaylistRow(
    name: String,
    detail: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contained: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Layout.rowHeight)
            .tap(
                onClick = onClick,
                shape = if (contained) RectangleShape else Radii.rowShape,
            )
            .padding(start = Space.step3, top = Space.step2, bottom = Space.step2, end = Space.step2),
    ) {
        ArtworkSquare(url = artworkUrl, size = 48.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}
