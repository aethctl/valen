/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * A page's arrival. While it is fresh, what composes on it rises into place one item after
 * another; once it has settled, items that compose later (a scroll, a new result) simply appear,
 * so a list never replays its entrance under a finger.
 */
@Stable
class PageEntrance {
    var settled by mutableStateOf(false)
        internal set
}

/** The entrance of the page being composed; a settled one outside any page. */
val LocalPageEntrance = staticCompositionLocalOf { PageEntrance().also { it.settled = true } }

/** A new entrance that settles by itself once its window has passed. */
@Composable
fun rememberPageEntrance(key: Any?): PageEntrance {
    val entrance = remember(key) { PageEntrance() }
    LaunchedEffect(entrance) {
        delay(Motion.StaggerWindowMs)
        entrance.settled = true
    }
    return entrance
}

/**
 * Rises into place as its page arrives: [index] steps later than the first item, faded in from
 * [Motion.RiseDistance] below. Nothing moves under reduced motion or once the page has settled.
 */
@Composable
fun Modifier.rise(index: Int = 0): Modifier {
    val reduced = LocalReducedMotion.current
    val entrance = LocalPageEntrance.current
    val animate = remember { !entrance.settled && !reduced }
    if (!animate) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            tween(
                durationMillis = Motion.EnterMs + 120,
                delayMillis = index.coerceIn(0, Motion.StaggerCap) * Motion.StaggerMs,
                easing = Motion.Enter,
            ),
        )
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * Motion.RiseDistance.toPx()
    }
}

/** A card under the pointer comes up toward it, and settles back with a spring when it leaves. */
@Composable
fun Modifier.hoverLift(source: MutableInteractionSource): Modifier {
    val reduced = LocalReducedMotion.current
    val hovered by source.collectIsHoveredAsState()
    if (reduced) return this
    val scale by animateFloatAsState(
        targetValue = if (hovered) Motion.HoverScale else 1f,
        animationSpec = Motion.lean(),
        label = "hoverLift",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * A cover that leans toward the pointer resting on it, a few degrees in 3D, and springs flat when
 * it leaves. Mouse only: a finger on a cover is a press, not a hover.
 */
@Composable
fun Modifier.tilt(): Modifier {
    val reduced = LocalReducedMotion.current
    if (reduced) return this
    var target by remember { mutableStateOf(Offset.Zero) }
    val x by animateFloatAsState(target.x, Motion.lean(), label = "tiltX")
    val y by animateFloatAsState(target.y, Motion.lean(), label = "tiltY")
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.type != PointerType.Mouse) continue
                    target = if (event.type == PointerEventType.Exit || size.width == 0 || size.height == 0) {
                        Offset.Zero
                    } else {
                        // -1..1 across the cover, from its centre.
                        Offset(
                            (change.position.x / size.width * 2f - 1f).coerceIn(-1f, 1f),
                            (change.position.y / size.height * 2f - 1f).coerceIn(-1f, 1f),
                        )
                    }
                }
            }
        }
        .graphicsLayer {
            rotationY = x * Motion.TiltDegrees
            rotationX = -y * Motion.TiltDegrees
            cameraDistance = 14f * density
        }
}

/**
 * The stage cover breathing with the music: full size while the song plays, sunk back a step
 * while it is paused, moving between the two on a soft spring.
 */
@Composable
fun Modifier.breathe(playing: Boolean): Modifier {
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = if (playing || reduced) 1f else Motion.PausedScale,
        animationSpec = Motion.lean(),
        label = "breathe",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
