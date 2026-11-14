/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * One answer out of a few: a track the options sit in, and a white pill that slides to the one
 * chosen. Used for audio quality.
 */
@Composable
fun <T> SegmentedFilter(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier = modifier
            .widthIn(max = 380.dp)
            .fillMaxWidth()
            .height(Layout.hitTarget)
            .clip(Radii.pillShape)
            .background(ValenColors.Fill)
            .padding(Space.step1),
    ) {
        val segment: Dp = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(
            targetValue = segment * index,
            animationSpec = if (reduced) Motion.fade() else Motion.chrome(),
            label = "segmentOffset",
        )
        Box(
            modifier = Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(Radii.pillShape)
                .background(ValenColors.Text),
        )
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { option ->
                val on = option == selected
                val ink by animateColorAsState(
                    targetValue = if (on) ValenColors.OnLight else ValenColors.Text2,
                    animationSpec = Motion.press(),
                    label = "segmentInk",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(Radii.pillShape)
                        .clickable(
                            interactionSource = rememberInteraction(),
                            indication = null,
                            role = Role.RadioButton,
                            onClick = { onSelect(option) },
                        ),
                ) {
                    Text(text = label(option), style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
                }
            }
        }
    }
}

/** Chips for narrowing a search: more than one service can be on at a time. */
@Composable
fun <T> FilterChips(
    options: List<T>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    allLabel: String? = null,
    allSelected: Boolean = false,
    onAll: (() -> Unit)? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.step2),
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) {
        if (allLabel != null && onAll != null) {
            FilterPill(label = allLabel, selected = allSelected, onClick = onAll)
        }
        options.forEach { option ->
            // With "All" lit, the single services read as part of it rather than as three more picks.
            FilterPill(
                label = label(option),
                selected = !allSelected && option in selected,
                onClick = { onToggle(option) },
            )
        }
    }
}

/** A chip: white with black ink when on, a translucent fill when off. 36dp drawn, 44dp to hit. */
@Composable
fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteraction()
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(
        targetValue = when {
            selected -> ValenColors.Text
            hovered -> ValenColors.FillStrong
            else -> ValenColors.Fill
        },
        animationSpec = Motion.press(),
        label = "chipFill",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(Layout.hitTarget)
            .pressScale(source)
            .clickable(interactionSource = source, indication = null, role = Role.Checkbox, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(36.dp)
                .clip(Radii.pillShape)
                .background(fill)
                .padding(horizontal = Space.step4),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) ValenColors.OnLight else ValenColors.Text,
                maxLines = 1,
            )
        }
    }
}
