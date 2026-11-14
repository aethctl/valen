/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import app.valen.ui.artwork.ArtworkTones
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The artwork's colour as light in the room: three soft pools over a dark floor, one leaning the
 * way the left of the cover does, one the right, one its accent. It is the only decoration in the
 * app, and there is at most one on a screen.
 *
 * [fade] is how far the wash dissolves into the black page by its bottom edge: 1 for a band that
 * content continues under, less for a stage the wash fills. [strength] thins the pools, for a
 * wash with no artwork behind it, where grey at full strength would read as fog.
 *
 * With [drift] the pools wander slowly while a song plays. The clock is read in the draw phase
 * only, so drifting never recomposes anything, and it stops under reduced motion.
 *
 * The cover's colours as drifting pools is Mochy Music's idea (its ambient backdrop); this
 * implementation is Valen's own. See NOTICE.
 */
@Composable
fun AmbientWash(
    tones: ArtworkTones,
    modifier: Modifier = Modifier,
    drift: Boolean = false,
    fade: Float = 1f,
    strength: Float = 1f,
) {
    val reduced = LocalReducedMotion.current
    val spec = if (reduced) Motion.fade<Color>() else Motion.ambient()
    val floor by animateColorAsState(tones.deep, spec, label = "washFloor")
    val left by animateColorAsState(tones.poolLeft, spec, label = "washLeft")
    val right by animateColorAsState(tones.poolRight, spec, label = "washRight")
    val accent by animateColorAsState(tones.poolAccent, spec, label = "washAccent")
    val seconds = rememberDrift(running = drift && !reduced)
    // Dithered, because a dark gradient across a whole window bands visibly without it.
    val paints = remember { List(3) { Paint().also { it.dither() } } }

    Spacer(
        modifier = modifier.drawWithCache {
            val reach = maxOf(size.width, size.height)
            val radii = floatArrayOf(reach * 0.74f, reach * 0.62f, reach * 0.66f)
            val colours = arrayOf(left, right, accent)
            for (i in 0 until 3) {
                paints[i].shader = RadialGradientShader(
                    center = Offset.Zero,
                    radius = radii[i],
                    colors = PoolAlphas.map { colours[i].copy(alpha = it * strength) },
                    colorStops = PoolStops,
                )
            }
            val veil = Brush.verticalGradient(
                0f to Color.Transparent,
                0.45f to ValenColors.Background.copy(alpha = 0.22f * fade),
                0.8f to ValenColors.Background.copy(alpha = 0.78f * fade),
                1f to ValenColors.Background.copy(alpha = fade),
            )
            onDrawBehind {
                drawRect(floor)
                val turn = seconds.floatValue * (2f * PI.toFloat() / Motion.AmbientLoopSeconds)
                drawIntoCanvas { canvas ->
                    for (i in 0 until 3) {
                        val cx = size.width * (PoolX[i] + 0.05f * sin(turn * (i + 1) + i * 1.7f))
                        val cy = size.height * (PoolY[i] + 0.05f * cos(turn * (i + 2) + i))
                        canvas.save()
                        canvas.translate(cx, cy)
                        canvas.drawRect(Rect(-cx, -cy, size.width - cx, size.height - cy), paints[i])
                        canvas.restore()
                    }
                }
                drawRect(veil)
            }
        },
    )
}

/** Where each pool rests, as a fraction of the area: top left, right of centre, low centre. */
private val PoolX = floatArrayOf(0.14f, 0.90f, 0.46f)
private val PoolY = floatArrayOf(0.08f, 0.34f, 0.98f)

/** A smooth shoulder rather than a straight ramp, so a pool has no visible rim. */
private val PoolStops = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
private val PoolAlphas = listOf(0.95f, 0.80f, 0.47f, 0.15f, 0f)

/** The stage wash moves in steps of this length: slow colour looks the same at 30 a second as at 120. */
private const val DriftStepNanos = 33_000_000L

@Composable
private fun rememberDrift(running: Boolean): FloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last < 0L) {
                    last = now
                } else if (now - last >= DriftStepNanos) {
                    seconds.floatValue = (seconds.floatValue + (now - last) / 1e9f) % Motion.AmbientLoopSeconds
                    last = now
                }
            }
        }
    }
    return seconds
}

/** Turns dithering on for this paint; each platform reaches its own native paint to do it. */
internal expect fun Paint.dither()
