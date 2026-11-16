/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import org.jetbrains.compose.resources.imageResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** The picture's own proportions on the tube, in the units it is drawn in before the warp. */
private const val PicW = 130f
private const val PicH = 100f

/**
 * A 3D colour television showing [picture] — the profile picture — on its tube.
 *
 * The set is a rendered model (see [TvFrames]) shown at the angle the pointer asks for: it turns
 * toward wherever the pointer is over it (or follows a finger dragged across it), and when left
 * alone it sways slowly on its own. Between rendered angles it crossfades, so it turns smoothly.
 * Its plastic takes the scheme's colour. The picture is laid on the tube in true perspective, under
 * scanlines, a rolling bar and the rendered glass; the tube lights up when the set appears, and a
 * new picture comes in through a burst of static. The whole set is the way to change the picture.
 */
@Composable
fun CrtTv(
    picture: String?,
    onTune: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val measurer = rememberTextMeasurer()
    val bodies = TvFrames.body.map { imageResource(it) }
    val details = TvFrames.detail.map { imageResource(it) }
    val painter = rememberPicturePainter(picture)

    val power = remember { Animatable(if (reduced) 1f else 0f) }
    val static = remember { Animatable(0f) }
    val yaw = remember { Animatable(0f) }
    var seen by remember { mutableStateOf<String?>(null) }
    var first by remember { mutableStateOf(true) }
    var aim by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(Unit) {
        if (!reduced) power.animateTo(1f, tween(Motion.TubeOnMs, easing = LinearEasing))
    }
    LaunchedEffect(picture) {
        val change = !first && picture != seen
        first = false
        seen = picture
        if (!change || reduced) return@LaunchedEffect
        static.snapTo(1f)
        static.animateTo(0f, tween(Motion.StaticMs, easing = Motion.Exit))
    }

    val loop = if (reduced) null else rememberInfiniteTransition(label = "tvLoop")
    val clock = loop?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(Motion.TvLoopMs, easing = LinearEasing), RepeatMode.Restart),
        label = "tvClock",
    )
    val frame = loop?.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(Motion.TvLoopMs, easing = LinearEasing), RepeatMode.Restart),
        label = "tvFrame",
    )
    // The angle: where the pointer asks, or a slow sway of its own.
    val idle = sin((clock?.value ?: 0f) * 2f * PI.toFloat()) * Motion.TvSwayDegrees
    val wanted = if (reduced) 0f else (aim ?: idle)
    LaunchedEffect(wanted) { yaw.animateTo(wanted, Motion.lean()) }

    val tint = ValenColors.Primary
    val source = rememberInteraction()
    Canvas(
        modifier = modifier
            .aspectRatio(TvFrames.Width.toFloat() / TvFrames.Height)
            .graphicsLayer { translationY = sin((clock?.value ?: 0f) * 4f * PI.toFloat()) * 5.dp.toPx() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val over = event.type != PointerEventType.Exit &&
                            (change.type == PointerType.Mouse || change.pressed)
                        aim = if (over && size.width > 0) {
                            ((change.position.x / size.width) * 2f - 1f).coerceIn(-1f, 1f) * TvFrames.yaws.last() * 0.9f
                        } else {
                            null
                        }
                    }
                }
            }
            .pressScale(source)
            .clickable(interactionSource = source, indication = null, onClickLabel = "Change picture", onClick = onTune),
    ) {
        val k = size.width / TvFrames.Width
        // Which two rendered angles the set is between, and how far.
        val step = TvFrames.yaws[1] - TvFrames.yaws[0]
        val at = ((yaw.value - TvFrames.yaws[0]) / step).coerceIn(0f, (TvFrames.yaws.size - 1).toFloat())
        val i = floor(at).toInt().coerceAtMost(TvFrames.yaws.size - 2)
        val f = at - i

        // A soft shadow where it stands.
        drawOval(
            Brush.radialGradient(
                0f to Color.Black.copy(alpha = 0.55f),
                1f to Color.Transparent,
                center = Offset(size.width * 0.5f, size.height * 0.94f),
                radius = size.width * 0.42f,
            ),
            topLeft = Offset(size.width * 0.08f, size.height * 0.88f),
            size = Size(size.width * 0.84f, size.height * 0.12f),
        )
        scale(k, k, pivot = Offset.Zero) {
            val tinted = ColorFilter.tint(tint, BlendMode.Modulate)
            drawFrame(bodies[i], 1f - f, tinted)
            drawFrame(bodies[i + 1], f, tinted)
            val quad = FloatArray(8) { n -> TvFrames.quads[i][n] * (1f - f) + TvFrames.quads[i + 1][n] * f }
            drawTube(
                quad = quad,
                painter = painter,
                power = power.value,
                static = static.value,
                clock = clock?.value ?: 0f,
                frame = frame?.value ?: 0f,
                measurer = measurer,
            )
            drawFrame(details[i], 1f - f, null)
            drawFrame(details[i + 1], f, null)
        }
    }
}

private fun DrawScope.drawFrame(image: ImageBitmap, alpha: Float, filter: ColorFilter?) {
    if (alpha <= 0.002f) return
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(TvFrames.Width, TvFrames.Height),
        alpha = alpha,
        colorFilter = filter,
    )
}

/**
 * The tube, drawn flat in [PicW] x [PicH] and warped onto [quad] (four corners, clockwise from top
 * left) by a perspective matrix: the picture (or snow), scanlines, the rolling bar, and while it
 * switches on, the line it opens from.
 */
private fun DrawScope.drawTube(
    quad: FloatArray,
    painter: Painter?,
    power: Float,
    static: Float,
    clock: Float,
    frame: Float,
    measurer: TextMeasurer,
) {
    if (power <= 0f) return
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.concat(homography(quad))
        canvas.drawRect(Rect(0f, 0f, PicW, PicH), Paint().apply { color = Color.Black })
        val open = if (power < 0.22f) 0f else ((power - 0.22f) / 0.78f).coerceIn(0f, 1f)
        val across = (power / 0.22f).coerceIn(0f, 1f)
        val h = if (open > 0f) PicH * Motion.Enter.transform(open) else 0.8f
        val w = PicW * if (open > 0f) 1f else Motion.Enter.transform(across)
        clipRect(PicW / 2f - w / 2f, PicH / 2f - h / 2f, PicW / 2f + w / 2f, PicH / 2f + h / 2f) {
            val src = painter?.intrinsicSize
            if (painter != null && src != null && src.width.isFinite() && src.height.isFinite() && src.width > 0f && src.height > 0f) {
                // Cover: fill the tube, cropping the picture's longer side.
                val s = maxOf(PicW / src.width, PicH / src.height)
                val dw = src.width * s
                val dh = src.height * s
                translate((PicW - dw) / 2f, (PicH - dh) / 2f) {
                    with(painter) { draw(Size(dw, dh)) }
                }
            } else {
                drawSnow(frame, 0.9f)
                val text = measurer.measure("No signal", TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold))
                drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(PicW / 2f - 34f, PicH / 2f - 11f), size = Size(68f, 22f))
                drawText(text, topLeft = Offset(PicW / 2f - text.size.width / 2f, PicH / 2f - text.size.height / 2f))
            }
            if (static > 0f) drawSnow(frame, static)
            var y = 0f
            while (y < PicH) {
                drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(PicW, y), strokeWidth = 0.45f)
                y += 1.3f
            }
            val barH = PicH * 0.24f
            val barY = -barH + (PicH + barH * 2f) * ((clock * 3f) % 1f)
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.07f), 1f to Color.Transparent, startY = barY, endY = barY + barH),
                topLeft = Offset(0f, barY),
                size = Size(PicW, barH),
            )
            if (power < 1f) drawRect(Color.White.copy(alpha = (1f - open) * 0.9f))
        }
        canvas.restore()
    }
}

/**
 * The perspective matrix taking the rectangle 0..[PicW] x 0..[PicH] onto [q] (x0 y0 … x3 y3,
 * clockwise from top left): the square-to-quad projective map, scaled to the rectangle.
 */
private fun homography(q: FloatArray): Matrix {
    val x0 = q[0]; val y0 = q[1]; val x1 = q[2]; val y1 = q[3]
    val x2 = q[4]; val y2 = q[5]; val x3 = q[6]; val y3 = q[7]
    val sx = x0 - x1 + x2 - x3
    val sy = y0 - y1 + y2 - y3
    var g = 0f
    var h = 0f
    if (abs(sx) > 1e-4f || abs(sy) > 1e-4f) {
        val dx1 = x1 - x2; val dx2 = x3 - x2; val dy1 = y1 - y2; val dy2 = y3 - y2
        val det = dx1 * dy2 - dx2 * dy1
        g = (sx * dy2 - dx2 * sy) / det
        h = (dx1 * sy - sx * dy1) / det
    }
    val a = x1 - x0 + g * x1
    val b = x3 - x0 + h * x3
    val d = y1 - y0 + g * y1
    val e = y3 - y0 + h * y3
    val m = Matrix()
    val v = m.values
    v[0] = a / PicW; v[1] = d / PicW; v[3] = g / PicW
    v[4] = b / PicH; v[5] = e / PicH; v[7] = h / PicH
    v[12] = x0; v[13] = y0; v[15] = 1f; v[10] = 1f
    return m
}

/** Snow: black-and-white noise, a fresh patch of it every frame, at [strength]. */
private fun DrawScope.drawSnow(frame: Float, strength: Float) {
    val random = Random(frame.toInt())
    val brush = ShaderBrush(ImageShader(Snow.bitmap, TileMode.Repeated, TileMode.Repeated))
    // The tube is drawn in small units; the noise is drawn finer than that so it reads as snow.
    scale(0.35f, 0.35f, pivot = Offset.Zero) {
        translate(-random.nextInt(Snow.Size).toFloat(), -random.nextInt(Snow.Size).toFloat()) {
            drawRect(brush, size = Size(PicW / 0.35f + Snow.Size, PicH / 0.35f + Snow.Size), alpha = strength)
        }
    }
    repeat(3) {
        val y = random.nextFloat() * PicH
        drawRect(Color.White.copy(alpha = 0.18f * strength), topLeft = Offset(0f, y), size = Size(PicW, 0.8f))
    }
}

private object Snow {
    const val Size = 128

    val bitmap: ImageBitmap by lazy {
        val image = ImageBitmap(Size, Size)
        val canvas = Canvas(image)
        val random = Random(9861)
        canvas.drawRect(0f, 0f, Size.toFloat(), Size.toFloat(), Paint().apply { color = Color(0xFF101012) })
        for (shade in listOf(0.25f, 0.5f, 0.8f, 1f)) {
            val points = List(Size * Size / 6) { Offset(random.nextInt(Size) + 0.5f, random.nextInt(Size) + 0.5f) }
            canvas.drawPoints(PointMode.Points, points, Paint().apply { color = Color(shade, shade, shade, 1f); strokeWidth = 1f })
        }
        image
    }
}
