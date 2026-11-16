/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.launch

private val TrackWidth = 52.dp
private val TrackHeight = 32.dp
private val Knob = 24.dp

/**
 * On or off, as a drop of ink in a pill. The drop does not slide: its two ends move on different
 * springs, the one reaching for the new side quick and the other slow, so it stretches across and
 * gathers itself there — the same liquid as the droplet that marks the current place. A press
 * makes it reach a little toward the other side before it goes.
 */
@Composable
fun Toggle(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val source = rememberInteraction()
    val pressed by source.collectIsPressedAsState()
    // The ends, as fractions of the run from the left rest to the right rest.
    val lead = remember { Animatable(if (checked) 1f else 0f) }
    val trail = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) {
        val to = if (checked) 1f else 0f
        if (reduced) {
            lead.snapTo(to)
            trail.snapTo(to)
            return@LaunchedEffect
        }
        launch { lead.animateTo(to, Motion.liquidLead()) }
        launch { trail.animateTo(to, Motion.liquidTrail()) }
    }
    val track by animateColorAsState(if (checked) ValenColors.Primary else ValenColors.Fill, Motion.standard(), label = "toggleTrack")
    val ink by animateColorAsState(if (checked) ValenColors.OnPrimary else ValenColors.Text2, Motion.standard(), label = "toggleInk")
    val reach by animateFloatAsState(if (pressed && !reduced) 1f else 0f, Motion.squish(), label = "toggleReach")

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(width = TrackWidth + 8.dp, height = Layout.hitTarget)
            .semantics { contentDescription = label }
            .toggleable(
                value = checked,
                interactionSource = source,
                indication = null,
                role = Role.Switch,
                onValueChange = onChange,
            ),
    ) {
        Canvas(Modifier.size(TrackWidth, TrackHeight)) {
            val h = size.height
            drawRoundRect(track, cornerRadius = CornerRadius(h / 2f))
            val knob = Knob.toPx()
            val inset = (h - knob) / 2f
            val run = size.width - inset * 2f - knob
            // Leading end: whichever of the two is further along the way the drop is heading.
            val a = inset + run * minOf(lead.value, trail.value)
            val b = inset + run * maxOf(lead.value, trail.value) + knob
            // Pressed, the drop reaches a quarter knob toward the side it will go to.
            val stretch = knob * 0.25f * reach
            val l = (a - if (checked) stretch else 0f).coerceAtLeast(inset)
            val r = (b + if (checked) 0f else stretch).coerceAtMost(size.width - inset)
            drawRoundRect(
                color = ink,
                topLeft = Offset(l, inset),
                size = Size(r - l, knob),
                cornerRadius = CornerRadius(knob / 2f),
            )
        }
    }
}
