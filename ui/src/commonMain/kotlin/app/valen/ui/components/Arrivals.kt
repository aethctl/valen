/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.valen.ui.Route
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * How a page arrives. Every place has its own, so moving around the app reads as moving between
 * rooms rather than flicking one card: paint poured down a wall, a can sprayed out from where you
 * reached for it, a roller drawn across, a shop shutter rolled up, a poster slapped on, a tube
 * switched on. They share one length and one curve ([Motion.pour]).
 */
enum class PageArrival { Pour, Spray, Roller, RollerBack, Shutter, Slap, Tube }

/** Each place's arrival. */
fun arrivalFor(route: Route): PageArrival = when (route) {
    Route.Home -> PageArrival.Pour
    Route.Search, Route.Import, Route.Mix -> PageArrival.Spray
    Route.Playlists -> PageArrival.Roller
    Route.Queue -> PageArrival.RollerBack
    Route.Settings -> PageArrival.Shutter
    Route.NowPlaying, Route.About, Route.Sound, Route.Artist -> PageArrival.Slap
    Route.Profile -> PageArrival.Tube
}

/**
 * Draws a page arriving by [arrival] at [progress] (0 nothing yet, 1 settled). The new page is
 * painted solid inside what has been revealed, and the tool's own mark — wet paint, spatter, a
 * roller's band, the shutter's slats, the tube's flash — leads it in [paint].
 */
internal fun ContentDrawScope.drawArrival(arrival: PageArrival, progress: Float, seed: Int, paint: Color, origin: Offset) {
    if (progress >= 1f) {
        drawContent()
        return
    }
    if (progress <= 0f) return
    val w = size.width
    val h = size.height
    // Drawn on the page's own scope, inside whichever clip the arrival sets.
    val page: () -> Unit = {
        drawRect(ValenColors.Background)
        drawContent()
    }
    when (arrival) {
        PageArrival.Pour -> {
            drawPath(pourPath(w, h, (progress + Motion.PourLead).coerceAtMost(1f), seed, density), paint)
            clipPath(pourPath(w, h, progress, seed, density, drips = false)) { page() }
        }

        PageArrival.Spray -> {
            val reach = maxOf(hypot(origin.x, origin.y), hypot(w - origin.x, origin.y), hypot(origin.x, h - origin.y), hypot(w - origin.x, h - origin.y))
            val r = reach * 1.08f * progress
            // Spatter flies ahead of the can's edge: dots that land further out the faster it sprays.
            val random = Random(seed)
            repeat(140) {
                val angle = random.nextFloat() * 6.2832f
                val out = r + (random.nextFloat() * random.nextFloat()) * 90.dp.toPx()
                val dot = (1.2f + random.nextFloat() * 4.5f) * density
                drawCircle(paint, radius = dot, center = Offset(origin.x + kotlin.math.cos(angle) * out, origin.y + sin(angle) * out))
            }
            drawCircle(paint, radius = r + 10.dp.toPx(), center = origin)
            clipPath(Path().apply { addOval(Rect(origin, r)) }) { page() }
        }

        PageArrival.Roller, PageArrival.RollerBack -> {
            val back = arrival == PageArrival.RollerBack
            val band = 34.dp.toPx()
            val edge = -band + (w + band * 2f) * progress
            val x = if (back) w - edge else edge
            val wave = { y: Float -> sin(y / h * 11f + seed) * 4.dp.toPx() }
            val covered = Path().apply {
                val steps = 24
                if (!back) {
                    moveTo(0f, 0f)
                    for (i in 0..steps) lineTo(x + wave(h * i / steps), h * i / steps)
                    lineTo(0f, h)
                } else {
                    moveTo(w, 0f)
                    for (i in 0..steps) lineTo(x + wave(h * i / steps), h * i / steps)
                    lineTo(w, h)
                }
                close()
            }
            // The roller's band: paint, with the dry streaks a roller leaves.
            val left = if (back) x - band * 0.1f else x - band * 0.9f
            drawRect(paint, topLeft = Offset(left, 0f), size = Size(band, h))
            val random = Random(seed)
            repeat(26) {
                val y = random.nextFloat() * h
                drawRect(ValenColors.Background.copy(alpha = 0.18f), topLeft = Offset(left, y), size = Size(band, (1 + random.nextInt(3)) * density))
            }
            clipPath(covered) { page() }
        }

        PageArrival.Shutter -> {
            // A shop shutter rolling up: the new page shows from the bottom, the slats lead.
            val slats = 40.dp.toPx()
            val top = h - (h + slats) * progress
            clipPath(Path().apply { addRect(Rect(0f, top + slats, w, h)) }) { page() }
            val pitch = 7.dp.toPx()
            var y = top
            while (y < top + slats) {
                drawRect(ValenColors.Surface2, topLeft = Offset(0f, y), size = Size(w, pitch * 0.62f))
                drawRect(ValenColors.Surface, topLeft = Offset(0f, y + pitch * 0.62f), size = Size(w, pitch * 0.38f))
                y += pitch
            }
            drawRect(paint, topLeft = Offset(0f, top + slats - 3.dp.toPx()), size = Size(w, 3.dp.toPx()))
        }

        PageArrival.Tube -> {
            // A tube lighting: a line across the middle, then the picture opening from it.
            val line = (progress / 0.25f).coerceIn(0f, 1f)
            val open = ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f)
            val lineW = w * Motion.Enter.transform(line)
            val openH = maxOf(2.dp.toPx(), h * Motion.Enter.transform(open))
            val shown = Rect(w / 2f - lineW / 2f, h / 2f - openH / 2f, w / 2f + lineW / 2f, h / 2f + openH / 2f)
            clipPath(Path().apply { addRect(shown) }) {
                page()
                drawRect(Color.White.copy(alpha = (1f - open) * 0.85f))
            }
        }

        PageArrival.Slap -> {
            // Handled as a transform by [slapped]; here the page simply draws.
            page()
        }
    }
}

/** A page slapped on like a poster: big and turned, then down flat with a small bounce. */
internal fun Modifier.slapped(active: Boolean, progress: () -> Float): Modifier = if (!active) this else graphicsLayer {
    val p = Motion.Kinetic.transform(progress().coerceIn(0f, 1f))
    val s = 1.08f - 0.08f * p
    scaleX = s
    scaleY = s
    rotationZ = -2.5f * (1f - p)
    alpha = progress().coerceIn(0f, 1f) * 3f
    transformOrigin = TransformOrigin(0.5f, 0.35f)
}

/** How an item arrives on a page that has just appeared. */
enum class ItemArrival {
    /** Fades up from below: rows. */
    Rise,

    /** Comes down big and turned, and lands flat with a bounce: covers, like stickers. */
    Slap,

    /** Swings in from the left on its own corner: titles of sections. */
    Swing,
}

/**
 * Arrives with its page by [style], [index] steps after the first item. Nothing moves under
 * reduced motion or once the page has settled, so a scroll never replays an entrance.
 */
@Composable
fun Modifier.arrive(index: Int = 0, style: ItemArrival = ItemArrival.Rise): Modifier {
    val reduced = LocalReducedMotion.current
    val entrance = LocalPageEntrance.current
    val animate = remember { !entrance.settled && !reduced }
    if (!animate) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            tween(
                durationMillis = if (style == ItemArrival.Rise) Motion.EnterMs + 120 else Motion.KineticMs,
                delayMillis = index.coerceIn(0, Motion.StaggerCap) * Motion.StaggerMs,
                easing = if (style == ItemArrival.Rise) Motion.Enter else Motion.Kinetic,
            ),
        )
    }
    val turn = remember(index) { (Random(index * 7919).nextFloat() * 2f - 1f) * 6f }
    return graphicsLayer {
        val p = progress.value
        when (style) {
            ItemArrival.Rise -> {
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * Motion.RiseDistance.toPx()
            }
            ItemArrival.Slap -> {
                alpha = (p * 2.5f).coerceIn(0f, 1f)
                val s = 1.25f - 0.25f * p
                scaleX = s
                scaleY = s
                rotationZ = turn * (1f - p)
            }
            ItemArrival.Swing -> {
                alpha = p.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 1f)
                rotationZ = -8f * (1f - p)
                translationX = -(1f - p) * Motion.RiseDistance.toPx() * 1.5f
            }
        }
    }
}
