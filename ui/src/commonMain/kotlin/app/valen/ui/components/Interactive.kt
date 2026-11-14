/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.ValenColors

/**
 * Floating chrome: one translucent fill with a light edge, optionally warmed by the artwork that
 * is playing. It is a single layer by rule; glass is never stacked on glass.
 */
fun Modifier.glass(shape: Shape = Radii.pillShape, tint: Color? = null): Modifier {
    val fill = if (tint == null) ValenColors.GlassFill else tint.copy(alpha = 0.62f).compositeOver(ValenColors.GlassFill)
    return clip(shape).background(fill).border(1.dp, ValenColors.GlassEdge, shape)
}

/** Press settles a control to 0.97; skipped entirely when the host asks for less motion. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val reduced = LocalReducedMotion.current
    val pressed by source.collectIsPressedAsState()
    if (reduced) return this
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressedScale else 1f,
        animationSpec = Motion.press(),
        label = "pressScale",
    )
    return scale(scale)
}

@Composable
fun rememberInteraction(): MutableInteractionSource = remember { MutableInteractionSource() }

/**
 * The app's one way to make something pressable. No ripple: a pointer resting on it lifts the fill
 * a step, a press lifts it another, and keyboard focus shows the same lift, so every row, tile and
 * icon answers the same way on a desktop and under a finger.
 */
@Composable
fun Modifier.tap(
    onClick: () -> Unit,
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    source: MutableInteractionSource = rememberInteraction(),
    /** The fill at rest; hover and press are laid over it. */
    rest: Color = Color.Transparent,
    role: Role = Role.Button,
): Modifier {
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val lift = when {
        !enabled -> Color.Transparent
        pressed -> ValenColors.Fill
        hovered || focused -> ValenColors.Hover
        else -> Color.Transparent
    }
    val fill by animateColorAsState(lift, Motion.press(), label = "tapFill")
    return clip(shape)
        .background(rest)
        .background(fill)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** An icon with a full-size hit target. The glyph is small; what a finger presses never is. */
@Composable
fun IconTap(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = ValenColors.Text2,
    iconSize: Dp = 22.dp,
    size: Dp = Layout.hitTarget,
) {
    val source = rememberInteraction()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .pressScale(source)
            .tap(onClick = onClick, shape = CircleShape, enabled = enabled, source = source),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else ValenColors.Text3,
            modifier = Modifier.size(iconSize),
        )
    }
}
