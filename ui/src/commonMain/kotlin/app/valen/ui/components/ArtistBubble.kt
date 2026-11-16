/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import app.valen.ui.theme.Squircle
import app.valen.ui.theme.Radii

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/** An artist as a round picture with their name under it; without a picture, the tag's V. */
@Composable
fun ArtistBubble(name: String, artwork: String?, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = rememberInteraction()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(size)
            .hoverLift(source)
            .pressScale(source)
            // The whole bubble presses, name included: a circle here clipped the name's ends.
            .tap(onClick = onClick, shape = Squircle(Radii.row), source = source),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).clip(CircleShape).background(ValenColors.Container),
        ) {
            if (artwork != null) {
                Artwork(url = artwork, shape = CircleShape, modifier = Modifier.fillMaxSize())
            } else {
                Tag(ink = ValenColors.OnContainer, mist = ValenColors.OnContainer, initial = true, modifier = Modifier.width(size * 0.42f))
            }
        }
        Spacer(Modifier.height(Space.step2))
        Text(name, style = MaterialTheme.typography.titleSmall, color = ValenColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
