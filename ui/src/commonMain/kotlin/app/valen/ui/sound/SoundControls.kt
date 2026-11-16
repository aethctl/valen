/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.sound

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.valen.core.EQ_BANDS
import app.valen.core.SoundEffects
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.tap
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

// ── The pencil slider ──

/**
 * A setting on a pencil line: its name and value above, the line below with the marker's ink
 * running from where it rests ([rest]) to where it is, and a dot of the marker to hold. A tap puts
 * it there, a drag moves it, a double tap puts it back to rest; near rest it catches.
 *
 * [log]: frequencies travel by octaves. [off]: the far start means "off" (shown by [display]).
 * [reversed]: the line runs from the end of the range to its start (more muffled to the right).
 */
@Composable
internal fun PencilSlider(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    modifier: Modifier = Modifier,
    rest: Float = range.start,
    step: Float = 0f,
    log: Boolean = false,
    off: Float? = null,
    reversed: Boolean = false,
    enabled: Boolean = true,
) {
    val reduced = LocalReducedMotion.current
    val latest by rememberUpdatedState(onValue)
    var holding by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(if (holding && !reduced) 1f else 0f, Motion.squish(), label = "pencilHold")

    fun toT(v: Float): Float {
        // Off is always the start of the line, whichever way the values run along it.
        if (off != null && v == off) return 0f
        val raw = if (log) (ln(v / range.start) / ln(range.endInclusive / range.start)) else (v - range.start) / (range.endInclusive - range.start)
        val t = raw.coerceIn(0f, 1f)
        return if (reversed) 1f - t else t
    }
    fun fromT(tIn: Float): Float {
        if (off != null && tIn < 0.03f) return off
        val t = (if (reversed) 1f - tIn else tIn).coerceIn(0f, 1f)
        var v = if (log) range.start * (range.endInclusive / range.start).pow(t) else range.start + t * (range.endInclusive - range.start)
        if (step > 0f) v = (v / step).roundToInt() * step
        // Rest catches the dot when it comes near.
        if (abs(toT(v) - toT(rest)) < 0.025f) v = rest
        return v.coerceIn(range.start, range.endInclusive)
    }

    val tValue = toT(value)
    val tRest = toT(rest)
    val primary = ValenColors.Primary
    val onPrimary = ValenColors.OnPrimary
    val pencil = ValenColors.Text3
    val notch = ValenColors.Text2
    Column(modifier.graphicsLayer { alpha = if (enabled) 1f else 0.45f }) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = ValenColors.Text, modifier = Modifier.weight(1f))
            Text(
                text = display(value),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = if (value != rest) primary else ValenColors.Text2,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = display(value)
                    progressBarRangeInfo = ProgressBarRangeInfo(tValue, 0f..1f)
                    setProgress { t -> latest(fromT(t)); true }
                }
                .then(
                    if (!enabled) {
                        Modifier
                    } else {
                        Modifier
                            .pointerInput(range, log, reversed) {
                                var lastUp = 0L
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    holding = true
                                    val pad = 10.dp.toPx()
                                    fun at(x: Float) = latest(fromT((x - pad) / (size.width - pad * 2)))
                                    // A second tap soon after the last: back to rest.
                                    val again = down.uptimeMillis - lastUp < DoubleTapMs
                                    if (again) latest(rest) else at(down.position.x)
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            lastUp = if (again) 0L else change.uptimeMillis
                                            break
                                        }
                                        if (!again) at(change.position.x)
                                        change.consume()
                                    }
                                    holding = false
                                }
                            }
                    },
                ),
        ) {
            val pad = 10.dp.toPx()
            val w = size.width - pad * 2
            val y = size.height / 2f
            drawLine(pencil.copy(alpha = 0.55f), Offset(pad, y), Offset(pad + w, y), 1.5.dp.toPx(), StrokeCap.Round)
            for (q in 0..4) {
                val x = pad + w * q / 4f
                drawLine(pencil.copy(alpha = 0.45f), Offset(x, y + 6.dp.toPx()), Offset(x, y + 10.dp.toPx()), 1.dp.toPx())
            }
            if (tRest > 0.001f && tRest < 0.999f) {
                val x = pad + w * tRest
                drawLine(notch, Offset(x, y - 7.dp.toPx()), Offset(x, y + 7.dp.toPx()), 1.5.dp.toPx(), StrokeCap.Round)
            }
            val from = pad + w * tRest
            val to = pad + w * tValue
            if (abs(to - from) > 0.5f) drawLine(primary, Offset(from, y), Offset(to, y), 4.dp.toPx(), StrokeCap.Round)
            val r = (7f + 4f * grow).dp.toPx()
            drawCircle(primary, r, Offset(to, y))
            drawCircle(onPrimary, 2.2.dp.toPx(), Offset(to, y))
        }
    }
}

// ── The ruler ──

/**
 * A measuring tape for a value with a feel to it — speed, pitch. The tape slides under a fixed
 * needle: drag it, or fling it and let it run out and settle on a tick (and catch at [rest]).
 * Ticks every [step], taller every [mid] of them, labelled every [major] by [tick].
 */
@Composable
internal fun Ruler(
    value: Float,
    onValue: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    mid: Int,
    major: Int,
    tick: (Float) -> String,
    label: String,
    display: (Float) -> String,
    modifier: Modifier = Modifier,
    rest: Float = 1f,
    spacing: Dp = 7.dp,
    enabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onValue)
    val current by rememberUpdatedState(value)
    val fling = remember { Animatable(value) }
    val measurer = rememberTextMeasurer()
    val labelStyle = timeStyle().copy(color = ValenColors.Text2)
    val primary = ValenColors.Primary
    val ink = ValenColors.Text2
    val faint = ValenColors.Text3

    fun snap(v: Float): Float {
        val near = (v / step).roundToInt() * step
        return (if (abs(near - rest) <= step * 1.5f) rest else near).coerceIn(range.start, range.endInclusive)
    }

    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val drag = rememberDraggableState { dx ->
        latest((current - dx / spacingPx * step).coerceIn(range.start, range.endInclusive))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = if (enabled) 1f else 0.45f }
            .semantics {
                contentDescription = label
                stateDescription = display(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                setProgress { v -> latest(snap(v)); true }
            }
            .draggable(
                state = drag,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = { velocity ->
                    scope.launch {
                        val px = spacingPx
                        fling.snapTo(current)
                        fling.updateBounds(range.start, range.endInclusive)
                        fling.animateDecay(-velocity / px * step, exponentialDecay(frictionMultiplier = 2.2f)) { latest(this.value) }
                        val settled: Float = snap(fling.value)
                        fling.animateTo(settled, Motion.liquid()) { latest(this.value) }
                    }
                },
            )
            .drawWithContent {
                drawContent()
                // The tape runs on past both edges: it fades out rather than stopping.
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val px = spacing.toPx()
            val cx = size.width / 2f
            val base = size.height - 16.dp.toPx()
            val first = ((range.start) / step).roundToInt()
            val last = ((range.endInclusive) / step).roundToInt()
            for (k in first..last) {
                val v = k * step
                val x = cx + (v - value) / step * px
                if (x < -px || x > size.width + px) continue
                val isMajor = k % major == 0
                val isMid = k % mid == 0
                val h = when {
                    isMajor -> 22.dp.toPx()
                    isMid -> 14.dp.toPx()
                    else -> 8.dp.toPx()
                }
                drawLine(if (isMajor) ink else faint, Offset(x, base - h), Offset(x, base), if (isMajor) 1.6.dp.toPx() else 1.dp.toPx(), StrokeCap.Round)
                if (isMajor) {
                    val text = measurer.measure(tick(v), labelStyle)
                    drawText(text, topLeft = Offset(x - text.size.width / 2f, base + 3.dp.toPx()))
                }
            }
            // The needle: a line of the marker with its dot.
            drawLine(primary, Offset(cx, 4.dp.toPx()), Offset(cx, base), 2.5.dp.toPx(), StrokeCap.Round)
            drawCircle(primary, 4.5.dp.toPx(), Offset(cx, 4.dp.toPx()))
        }
    }
}

// ── The equalizer, drawn ──

/**
 * The equalizer as a sketch on the dot grid: the response the player really applies — all ten
 * bands, both shelves, both cuts, the preamp, from the same filter design — drawn as one line of
 * the marker, shaded to the 0 dB line, with a dot on each band to pull. Anywhere in a band's column
 * sets it: press and drag up or down; a double tap flattens it. When the settings jump (a preset),
 * the line pours into its new shape on the liquid spring; the first time, it is drawn on by pen.
 */
@Composable
internal fun EqSketch(effects: SoundEffects, onBand: (Int, Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val reduced = LocalReducedMotion.current
    val latest by rememberUpdatedState(onBand)
    val n = effects.normalized()
    val shownBands = n.bands.mapIndexed { i, g -> animateFloatAsState(g, if (reduced) Motion.fade() else Motion.liquid(), label = "band$i").value }
    val bass by animateFloatAsState(n.bass, Motion.liquid(), label = "bass")
    val treble by animateFloatAsState(n.treble, Motion.liquid(), label = "treble")
    val pre by animateFloatAsState(n.preamp, Motion.liquid(), label = "pre")
    val low by animateFloatAsState(if (n.lowCut > 0f) n.lowCut else 12f, Motion.liquid(), label = "low")
    val high by animateFloatAsState(if (n.highCut > 0f) n.highCut else 21000f, Motion.liquid(), label = "high")
    val drawn = SoundEffects(
        bands = shownBands, bass = bass, treble = treble, preamp = pre,
        lowCut = if (low < 19f) 0f else low, highCut = if (high > 19900f) 0f else high,
    )
    val pen = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) pen.animateTo(1f, tween(Motion.MarkerMs * 2, easing = Motion.Standard)) }
    var active by remember { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val small = timeStyle().copy(color = ValenColors.Text3)
    val bubbleStyle = MaterialTheme.typography.labelMedium.copy(color = ValenColors.Text, fontFeatureSettings = "tnum")
    val primary = ValenColors.Primary
    val onPrimary = ValenColors.OnPrimary
    val guide = ValenColors.Text3
    val fill = ValenColors.Fill

    Canvas(
        modifier
            .fillMaxWidth()
            .height(230.dp)
            .semantics { contentDescription = "Equalizer" }
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        val left = 30.dp.toPx()
                        val top = 14.dp.toPx()
                        val bottom = 24.dp.toPx()
                        fun band(x: Float): Int {
                            val w = size.width - left - 8.dp.toPx()
                            return EQ_BANDS.indices.minBy { abs(left + freqX(EQ_BANDS[it]) * w - x) }
                        }
                        fun gain(y: Float): Float {
                            val h = size.height - top - bottom
                            val db = 12f - (y - top) / h * 24f
                            val snapped = (db * 2f).roundToInt() / 2f
                            return if (abs(snapped) < 0.6f) 0f else snapped.coerceIn(-12f, 12f)
                        }
                        var lastTap = 0L
                        var lastBand = -1
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val i = band(down.position.x)
                            active = i
                            val double = i == lastBand && down.uptimeMillis - lastTap < DoubleTapMs
                            if (double) latest(i, 0f) else latest(i, gain(down.position.y))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (!double) latest(i, gain(change.position.y))
                                change.consume()
                            }
                            lastTap = down.uptimeMillis
                            lastBand = i
                            active = null
                        }
                    }
                },
            ),
    ) {
        val left = 30.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = 24.dp.toPx()
        val w = size.width - left - 8.dp.toPx()
        val h = size.height - top - bottom
        fun x(f: Float) = left + freqX(f) * w
        fun y(db: Float) = top + (12f - db.coerceIn(-15f, 15f)) / 24f * h

        // The grid: 0 dB firm, ±6 and ±12 in pencil, with their numbers in the margin.
        for (db in listOf(12f, 6f, 0f, -6f, -12f)) {
            val yy = y(db)
            drawLine(
                guide.copy(alpha = if (db == 0f) 0.8f else 0.35f), Offset(left, yy), Offset(left + w, yy),
                if (db == 0f) 1.2.dp.toPx() else 1.dp.toPx(),
                pathEffect = if (db == 0f) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
            )
            val t = measurer.measure(if (db > 0) "+${db.roundToInt()}" else if (db < 0) "−${(-db).roundToInt()}" else "0", small)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), yy - t.size.height / 2f))
        }
        EQ_BANDS.forEach { f ->
            val t = measurer.measure(SoundEffects.hertz(f).replace(" Hz", "").replace(" kHz", "k"), small)
            drawText(t, topLeft = Offset(x(f) - t.size.width / 2f, top + h + 6.dp.toPx()))
        }

        // The line, and the shade between it and 0 dB.
        val steps = 140
        val curve = Path()
        val shade = Path()
        val zero = y(0f)
        for (k in 0..steps) {
            val f = 20f * 1000f.pow(k / steps.toFloat())
            val px = x(f)
            val py = y(drawn.responseDb(f))
            if (k == 0) {
                curve.moveTo(px, py)
                shade.moveTo(px, zero)
                shade.lineTo(px, py)
            } else {
                curve.lineTo(px, py)
                shade.lineTo(px, py)
            }
        }
        shade.lineTo(x(20000f), zero)
        shade.close()
        clipRect(right = left + w * pen.value + 2.dp.toPx()) {
            drawPath(shade, primary.copy(alpha = 0.13f))
            drawPath(curve, primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }

        // A dot on each band, where it is set; the one held is bigger and says its value.
        EQ_BANDS.forEachIndexed { i, f ->
            val cx = x(f)
            val cy = y(shownBands[i])
            val held = active == i
            drawCircle(primary, (if (held) 9f else 6f).dp.toPx(), Offset(cx, cy))
            drawCircle(onPrimary, 2.dp.toPx(), Offset(cx, cy))
            if (held) {
                val g = n.bands[i]
                val label = measurer.measure(if (abs(g) < 0.05f) "0 dB" else "${if (g > 0) "+" else "−"}${trim(abs(g))} dB", bubbleStyle)
                val bw = label.size.width + 16.dp.toPx()
                val bh = label.size.height + 8.dp.toPx()
                val bx = (cx - bw / 2f).coerceIn(0f, size.width - bw)
                val by = (cy - bh - 14.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(fill, Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2f))
                drawText(label, topLeft = Offset(bx + 8.dp.toPx(), by + 4.dp.toPx()))
            }
        }
    }
}

/** Where [hz] sits across 20 Hz–20 kHz, 0 to 1, by octaves. */
private fun freqX(hz: Float): Float = ((log10(hz) - log10(20f)) / (log10(20000f) - log10(20f))).coerceIn(0f, 1f)

private fun trim(v: Float): String {
    val r = (v * 2f).roundToInt() / 2f
    return if (r == r.roundToInt().toFloat()) r.roundToInt().toString() else r.toString()
}

// ── A preset, as a sticker ──

/**
 * A preset as a paper label stuck to the page, a little crooked: its glyph, its name and a few
 * words. The chosen one gets a loop of the marker drawn round it.
 */
@Composable
internal fun PresetSticker(
    name: String,
    words: String,
    icon: ImageVector,
    chosen: Boolean,
    seed: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val source = rememberInteraction()
    val loop = remember { Animatable(if (chosen) 1f else 0f) }
    LaunchedEffect(chosen) {
        if (!chosen) loop.snapTo(0f) else if (reduced) loop.snapTo(1f) else loop.animateTo(1f, tween(Motion.MarkerMs, easing = Motion.Standard))
    }
    val turn = remember(seed) { (Random(seed).nextFloat() * 2f - 1f) * 1.4f }
    val primary = ValenColors.Primary
    Box(
        modifier
            .graphicsLayer { rotationZ = turn }
            .drawWithContent {
                drawContent()
                if (loop.value > 0f) drawLoop(primary, loop.value, seed)
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .pressScale(source)
                .tap(onClick = onClick, shape = Radii.rowShape, source = source, rest = ValenColors.Fill)
                .padding(Space.step3),
        ) {
            Icon(icon, contentDescription = null, tint = if (chosen) primary else ValenColors.Text, modifier = Modifier.size(22.dp))
            Spacer(Modifier.weight(1f))
            Text(name, style = MaterialTheme.typography.titleSmall, color = ValenColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(words, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A loop of the marker round the whole box, drawn on up to [progress], overshooting where it closes. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLoop(ink: Color, progress: Float, seed: Int) {
    val random = Random(seed * 31 + 7)
    val r = Rect(-5.dp.toPx(), -5.dp.toPx(), size.width + 5.dp.toPx(), size.height + 5.dp.toPx())
    val path = Path()
    val steps = 48
    // A little more than once round, wobbling, so the ends cross like a hand-drawn circle.
    for (k in 0..steps) {
        val a = (k / steps.toFloat()) * 1.12f * 2f * kotlin.math.PI.toFloat() - 2.2f
        val wobble = 1f + (random.nextFloat() - 0.5f) * 0.025f
        val px = r.center.x + kotlin.math.cos(a) * r.width / 2f * wobble
        val py = r.center.y + kotlin.math.sin(a) * r.height / 2f * wobble
        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val part = Path()
    measure.getSegment(0f, measure.length * progress, part, true)
    drawPath(part, ink, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
}

// ── Small helpers ──

/** Two taps closer than this are a double tap. */
private const val DoubleTapMs = 280L
