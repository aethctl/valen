/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.sound

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.tap
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/** The gap between two ruled lines: one row of handwriting. */
internal val RuleGap = 28.dp

/** Where the margin line runs, from the page's left edge. */
internal val MarginAt = 44.dp

/**
 * A page of the notebook: the surface step, ruled — hairlines a row apart from [top], and the
 * margin in the one colour — the paper notes are written on.
 */
internal fun Modifier.ruledPage(top: Dp): Modifier = this
    .clip(Radii.cardShape)
    .background(ValenColors.Surface)
    .drawBehind {
        val gap = RuleGap.toPx()
        var y = top.toPx()
        while (y < size.height - gap / 2) {
            drawLine(ValenColors.Hairline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            y += gap
        }
        val x = MarginAt.toPx()
        drawLine(ValenColors.Primary.copy(alpha = 0.32f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
    }

/** A dot-grid page: where the settings are drawn up rather than written. */
internal fun Modifier.dotPage(): Modifier = this
    .clip(Radii.cardShape)
    .background(ValenColors.Surface)
    .drawBehind {
        val gap = 16.dp.toPx()
        val r = 0.9.dp.toPx()
        val dot = Color.White.copy(alpha = 0.11f)
        var y = gap
        while (y < size.height) {
            var x = gap
            while (x < size.width) {
                drawCircle(dot, r, Offset(x, y))
                x += gap
            }
            y += gap
        }
    }

/**
 * The spiral binding. Between two pages side by side it runs down the gap ([vertical]), ring after
 * ring through a punched hole on each page; across the top of a page on a phone it runs along it.
 */
@Composable
internal fun Spiral(vertical: Boolean, modifier: Modifier = Modifier) {
    val ring = ValenColors.Text2
    val shine = ValenColors.Text
    val hole = ValenColors.Background
    Canvas(modifier) {
        val step = 26.dp.toPx()
        val holeR = 3.2.dp.toPx()
        val stroke = 2.4.dp.toPx()
        if (vertical) {
            // The pages meet at this spine's two edges; the holes sit just inside each.
            var y = step * 0.8f
            while (y < size.height - step * 0.4f) {
                val left = Offset(-7.dp.toPx(), y)
                val right = Offset(size.width + 7.dp.toPx(), y)
                drawCircle(hole, holeR, left)
                drawCircle(hole, holeR, right)
                val top = Offset(left.x, y - 4.dp.toPx())
                drawRoundRect(
                    ring,
                    topLeft = top,
                    size = Size(right.x - left.x, 8.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawLine(shine.copy(alpha = 0.5f), Offset(left.x + 6.dp.toPx(), y - 4.dp.toPx()), Offset(right.x - 6.dp.toPx(), y - 4.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
                y += step
            }
        } else {
            var x = step * 0.8f
            while (x < size.width - step * 0.4f) {
                val bottom = Offset(x, size.height - 2.dp.toPx())
                drawCircle(hole, holeR, bottom)
                drawRoundRect(
                    ring,
                    topLeft = Offset(x - 4.dp.toPx(), 0f),
                    size = Size(8.dp.toPx(), bottom.y),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawLine(shine.copy(alpha = 0.5f), Offset(x - 4.dp.toPx(), 3.dp.toPx()), Offset(x - 4.dp.toPx(), bottom.y - 3.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
                x += step
            }
        }
    }
}

/**
 * The index tabs along the top of the settings page, like a divider's: the open one is the page's
 * own paper and stands up from it, the rest sit a step back and lower.
 */
@Composable
internal fun NotebookTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, even: Boolean = false) {
    // On a narrow page the tabs share its width instead of running off its edge.
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.step1),
        verticalAlignment = Alignment.Bottom,
        modifier = modifier
            .then(if (even) Modifier.fillMaxWidth() else Modifier)
            .padding(start = if (even) Space.step2 else Space.step5, end = if (even) Space.step2 else 0.dp),
    ) {
        tabs.forEachIndexed { i, label ->
            val on = i == selected
            val source = rememberInteraction()
            val lift by animateFloatAsState(if (on) 0f else 5f, Motion.squish(), label = "tabLift")
            val fill by animateColorAsState(if (on) ValenColors.Surface else ValenColors.Skeleton, Motion.standard(), label = "tabFill")
            val ink by animateColorAsState(if (on) ValenColors.Text else ValenColors.Text2, Motion.standard(), label = "tabInk")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .then(if (even) Modifier.weight(1f) else Modifier)
                    .graphicsLayer { translationY = lift.dp.toPx() }
                    .height(40.dp)
                    .semantics { role = Role.Tab; this.selected = on }
                    .pressScale(source)
                    .tap(
                        onClick = { onSelect(i) },
                        shape = TabShape,
                        source = source,
                        rest = fill,
                    )
                    .padding(horizontal = if (even) Space.step1 else Space.step4),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
            }
        }
    }
}

private val TabShape = RoundedCornerShape(topStart = Radii.row, topEnd = Radii.row)

/**
 * A page turned over the spine. Forward, the open page lifts at its free edge and swings over the
 * binding, shading as it rises, uncovering the next one beneath; back, the earlier page swings in
 * over the open one and settles. [order] says which way is forward. Under reduced motion the two
 * simply cross-fade.
 */
@Composable
internal fun <T> PageTurn(
    target: T,
    order: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(target) }
    var leaving by remember { mutableStateOf<T?>(null) }
    val turn = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == shown) return@LaunchedEffect
        leaving = shown
        shown = target
        turn.snapTo(0f)
        turn.animateTo(1f, tween(if (reduced) Motion.ReducedTweenMs else Motion.PageTurnMs, easing = Motion.Enter))
        leaving = null
    }
    val gone = leaving
    val forward = gone == null || order(shown) >= order(gone)
    Box(modifier) {
        // Beneath: the next page (forward) or the one being covered (back).
        val under = if (forward) shown else gone
        val over = if (forward) gone else shown
        if (under != null) {
            Box(
                Modifier.drawWithContent {
                    drawContent()
                    // The page above throws no shadow (there are none here); it only keeps the light off.
                    val p = turn.value
                    val shade = if (gone == null) 0f else if (forward) (1f - p) * 0.35f else p * 0.25f
                    if (shade > 0.001f && !reduced) drawRect(Color.Black.copy(alpha = shade))
                },
            ) { content(under) }
        }
        if (over != null && gone != null) {
            Box(
                Modifier
                    .zIndex(1f)
                    .graphicsLayer {
                        val p = turn.value
                        if (reduced) {
                            alpha = if (forward) 1f - p else p
                        } else {
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            cameraDistance = 14f * density
                            // Past edge-on it is gone; the last part of the turn is the page settling.
                            rotationY = if (forward) -105f * p else -105f * (1f - p)
                            alpha = if (forward) (1f - p * 1.15f).coerceIn(0f, 1f) else (p * 1.4f).coerceIn(0f, 1f)
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        val p = turn.value
                        val lifted = if (forward) p else 1f - p
                        if (!reduced) drawRect(Color.Black.copy(alpha = 0.4f * lifted))
                    },
            ) { content(over) }
        }
    }
}

/** A thin rule across a page, for a gap between sections. */
@Composable
internal fun PageRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ValenColors.Hairline),
    )
}

/** Keeps a page's own width while it lays out beside the binding. */
internal fun Modifier.pageColumn(width: Dp?): Modifier = if (width == null) fillMaxWidth() else width(width).fillMaxHeight()
