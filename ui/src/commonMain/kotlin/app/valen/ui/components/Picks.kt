/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * Something to pick: a pill that fills with the one colour when chosen, its plus turning into a
 * tick, with a little jump each time it changes.
 */
@Composable
fun PickChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val fill by animateColorAsState(if (selected) ValenColors.Primary else ValenColors.Surface2, Motion.standard(), label = "pickFill")
    val ink by animateColorAsState(if (selected) ValenColors.OnPrimary else ValenColors.Text, Motion.standard(), label = "pickInk")
    val jump = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(selected) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (!reduced) {
            jump.snapTo(if (selected) 1.14f else 0.9f)
            jump.animateTo(1f, Motion.squish())
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.step2),
        modifier = modifier
            .graphicsLayer { scaleX = jump.value; scaleY = jump.value }
            .height(40.dp)
            .tap(onClick = onClick, shape = Radii.pillShape, rest = fill, role = Role.Checkbox)
            .padding(start = Space.step3, end = Space.step4),
    ) {
        Icon(
            imageVector = if (selected) Icons.Rounded.Check else Icons.Rounded.Add,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(18.dp),
        )
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

/** Chips that wrap onto as many lines as they need, each arriving a step after the last. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PickChips(
    options: List<String>,
    selected: (String) -> Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.step2),
        verticalArrangement = Arrangement.spacedBy(Space.step2),
        modifier = modifier,
    ) {
        options.forEachIndexed { i, option ->
            PickChip(
                label = option,
                selected = selected(option),
                onClick = { onToggle(option) },
                modifier = Modifier.arrive(i, ItemArrival.Slap),
            )
        }
    }
}
