/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.menu

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.tap
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * The like, as a heart that fills like a glass: the primary tone pours in from the bottom, its
 * surface sloshing on the jelly spring until it is still, and the heart gives a small gulp as it
 * fills. Taking the like back drains it. It shows the state it is given; [onToggle] asks for the
 * other one.
 */
@Composable
fun LiquidHeart(
    liked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    tint: Color = ValenColors.Text2,
) {
    val reduced = LocalReducedMotion.current
    val source = rememberInteraction()
    val level = remember { Animatable(if (liked) 1f else 0f) }
    val slosh = remember { Animatable(0f) }
    val gulp = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(liked) }
    LaunchedEffect(liked) {
        if (liked == seen) return@LaunchedEffect
        seen = liked
        if (reduced) {
            level.snapTo(if (liked) 1f else 0f)
            return@LaunchedEffect
        }
        if (liked) {
            launch { level.animateTo(1f, Motion.liquid()) }
            launch {
                slosh.snapTo(1f)
                slosh.animateTo(0f, Motion.jelly())
            }
            launch {
                gulp.snapTo(0.78f)
                gulp.animateTo(1f, Motion.jelly())
            }
        } else {
            launch { level.animateTo(0f, tween(Motion.StandardMs, easing = Motion.Exit)) }
            launch {
                slosh.snapTo(-0.6f)
                slosh.animateTo(0f, Motion.jelly())
            }
        }
    }
    val outline = rememberVectorPainter(Icons.Rounded.FavoriteBorder)
    val filled = rememberVectorPainter(Icons.Rounded.Favorite)
    val primary = ValenColors.Primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(Layout.hitTarget)
            .semantics { contentDescription = if (liked) "Liked" else "Like" }
            .pressScale(source)
            .tap(onClick = onToggle, shape = CircleShape, source = source),
    ) {
        Canvas(
            Modifier
                .size(iconSize)
                .graphicsLayer {
                    val g = gulp.value
                    scaleX = g + (1f - g) * 0.4f
                    scaleY = g
                },
        ) {
            val l = level.value.coerceIn(0f, 1.08f)
            val ink = if (liked || l > 0.02f) primary else tint
            with(outline) { draw(size, colorFilter = ColorFilter.tint(ink)) }
            if (l <= 0.001f) return@Canvas
            // The liquid: everything below a surface that rises with [level] and sways with [slosh].
            val w = size.width
            val h = size.height
            val surface = h * (1f - l)
            val amp = h * 0.09f * slosh.value
            val path = Path().apply {
                moveTo(0f, h)
                lineTo(0f, surface)
                val steps = 16
                for (i in 0..steps) {
                    val x = w * i / steps
                    val y = surface + amp * sin((i.toFloat() / steps) * 2f * PI.toFloat() * 1.25f)
                    lineTo(x, y)
                }
                lineTo(w, h)
                close()
            }
            clipPath(path) {
                with(filled) { draw(size, colorFilter = ColorFilter.tint(primary)) }
            }
        }
    }
}
