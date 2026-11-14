/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * An empty state is a sentence about this app's real state and, when there is one, the single
 * action that changes it. It is set like the rest of the page: a title in the app's voice and a
 * line under it, on the same left edge as everything else. No icon in a circle, no centred shrug.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** Only the stage centres its text, because everything else on the stage is centred. */
    centered: Boolean = false,
) {
    Column(
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        modifier = modifier.widthIn(max = 460.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = ValenColors.Text,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        Spacer(Modifier.height(Space.step2))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = ValenColors.Text2,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.step5))
            PrimaryButton(label = actionLabel, onClick = onAction)
        }
    }
}

/**
 * The space a list of songs will take while it loads: a cover and two lines per row, flat, in the
 * skeleton grey. No shimmer, no gradient.
 */
@Composable
fun SkeletonRows(count: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(count) { index ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.step3),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Layout.rowHeight),
            ) {
                Box(Modifier.size(48.dp).clip(Radii.coverShape).background(ValenColors.Skeleton))
                Column(verticalArrangement = Arrangement.spacedBy(Space.step2)) {
                    // Uneven widths, so the block reads as a list of titles and not as a table.
                    Box(Modifier.width(SkeletonTitles[index % SkeletonTitles.size].dp).height(12.dp).clip(Radii.pillShape).background(ValenColors.Skeleton))
                    Box(Modifier.width(SkeletonArtists[index % SkeletonArtists.size].dp).height(10.dp).clip(Radii.pillShape).background(ValenColors.Skeleton))
                }
            }
        }
    }
}

private val SkeletonTitles = intArrayOf(212, 148, 264, 176, 232, 120)
private val SkeletonArtists = intArrayOf(96, 132, 84, 148, 108, 72)
