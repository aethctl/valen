/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors

/**
 * Press squeezes a control and lets it spring back with a little overshoot, like something
 * elastic; skipped entirely when the host asks for less motion.
 */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val reduced = LocalReducedMotion.current
    val pressed by source.collectIsPressedAsState()
    if (reduced) return this
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PressedScale else 1f,
        animationSpec = Motion.squish(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
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
    /** A press held down: the way a finger opens a song's menu. */
    onLongClick: (() -> Unit)? = null,
    /**
     * The person is about to press this: a pointer has rested on it a moment, or a finger is
     * already down. For work worth starting early (a song's address); called once per approach.
     */
    onIntent: (() -> Unit)? = null,
): Modifier {
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    if (onIntent != null) {
        val intent by androidx.compose.runtime.rememberUpdatedState(onIntent)
        androidx.compose.runtime.LaunchedEffect(hovered, pressed) {
            when {
                pressed -> intent()
                hovered -> {
                    kotlinx.coroutines.delay(Motion.IntentHoverMs)
                    intent()
                }
            }
        }
    }
    val lift = when {
        !enabled -> Color.Transparent
        pressed -> ValenColors.Fill
        hovered || focused -> ValenColors.Hover
        else -> Color.Transparent
    }
    val fill by animateColorAsState(lift, Motion.press(), label = "tapFill")
    val base = clip(shape)
        .background(rest)
        .background(fill)
    return if (onLongClick == null) {
        base.clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
    } else {
        @OptIn(ExperimentalFoundationApi::class)
        base.combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
}

/**
 * A right click, where there is a mouse: [action] gets where it happened, in root coordinates. It
 * is taken before anything under it sees the press, so a row's own click never fires with it.
 */
@Composable
fun Modifier.onSecondaryClick(action: (Offset) -> Unit): Modifier {
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val latest = rememberUpdatedState(action)
    return onGloballyPositioned { placed[0] = it }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Press || !event.buttons.isSecondaryPressed) continue
                    val change = event.changes.firstOrNull() ?: continue
                    change.consume()
                    val origin = placed[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
                    latest.value(origin + change.position)
                }
            }
        }
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
