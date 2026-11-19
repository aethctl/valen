/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import app.valen.ui.util.formatTime

/**
 * Position on the stage: a thick white rail that grows under a pointer or a finger, the time
 * elapsed on the left and the time left, counting down, on the right. There is no thumb; the end
 * of the fill is the handle.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = scrub ?: progress
    val shownMs = if (scrub != null) (shown * durationMs).toLong() else positionMs

    Column(modifier = modifier.fillMaxWidth()) {
        ProgressRail(
            value = shown,
            enabled = enabled && durationMs > 0L,
            onScrub = { scrub = it },
            onCommit = { fraction ->
                scrub = null
                if (durationMs > 0L) onSeek((fraction * durationMs).toLong())
            },
            rest = 6.dp,
            active = 10.dp,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatTime(shownMs),
                style = timeStyle(large = true),
                color = ValenColors.Text2,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (durationMs > 0L) "-" + formatTime((durationMs - shownMs).coerceAtLeast(0L)) else formatTime(0L),
                style = timeStyle(large = true),
                color = ValenColors.Text2,
            )
        }
    }
}

/**
 * Position inside the floating player: elapsed, a thin rail, total. All on one line, because the
 * dock has one line to give.
 */
@Composable
fun InlineSeek(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showTimes: Boolean = true,
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = scrub ?: progress
    val shownMs = if (scrub != null) (shown * durationMs).toLong() else positionMs
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.step3),
        modifier = modifier,
    ) {
        if (showTimes) {
            Text(
                text = formatTime(shownMs),
                style = timeStyle(),
                color = ValenColors.Text2,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 30.dp),
            )
        }
        ProgressRail(
            value = shown,
            enabled = durationMs > 0L,
            onScrub = { scrub = it },
            onCommit = { fraction ->
                scrub = null
                if (durationMs > 0L) onSeek((fraction * durationMs).toLong())
            },
            modifier = Modifier.weight(1f),
            rest = 4.dp,
            active = 6.dp,
        )
        if (showTimes) {
            Text(
                text = formatTime(durationMs),
                style = timeStyle(),
                color = ValenColors.Text2,
                modifier = Modifier.widthIn(min = 30.dp),
            )
        }
    }
}

/**
 * The one rail behind seek and volume. It reports every drag frame through [onScrub] and the
 * final value through [onCommit], so a caller can preview without writing anything until release.
 * [rest] thick normally, [active] under a pointer or while dragging.
 */
@Composable
internal fun ProgressRail(
    value: Float,
    enabled: Boolean,
    onScrub: (Float?) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    rest: Dp = 4.dp,
    active: Dp = 6.dp,
) {
    // Pointer handlers outlive recompositions. In particular, a song change replaces the
    // callback that converts the fraction to milliseconds using that song's duration.
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnCommit by rememberUpdatedState(onCommit)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(value) }
    val source = rememberInteraction()
    val hovered by source.collectIsHoveredAsState()
    val trackHeight by animateDpAsState(
        targetValue = if (enabled && (dragging || hovered)) active else rest,
        animationSpec = Motion.standard(),
        label = "railHeight",
    )

    fun fractionOf(x: Float, width: Int): Float =
        if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .hoverable(source, enabled)
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val fraction = fractionOf(offset.x, size.width)
                            currentOnScrub(fraction)
                            currentOnCommit(fraction)
                            currentOnScrub(null)
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                dragValue = fractionOf(offset.x, size.width)
                                currentOnScrub(dragValue)
                            },
                            onDragEnd = {
                                dragging = false
                                currentOnCommit(dragValue)
                                currentOnScrub(null)
                            },
                            onDragCancel = {
                                dragging = false
                                currentOnScrub(null)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragValue = fractionOf(change.position.x, size.width)
                                currentOnScrub(dragValue)
                            },
                        )
                    },
            ),
    ) {
        val cy = size.height / 2f
        val track = trackHeight.toPx()
        val radius = CornerRadius(track / 2f, track / 2f)
        drawRoundRect(
            color = ValenColors.FillStrong,
            topLeft = Offset(0f, cy - track / 2f),
            size = Size(size.width, track),
            cornerRadius = radius,
        )
        val filled = size.width * value.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRoundRect(
                color = if (enabled) ValenColors.Primary else ValenColors.Text3,
                topLeft = Offset(0f, cy - track / 2f),
                // Never narrower than it is tall, so the first seconds of a song show as a dot, not a sliver.
                size = Size(filled.coerceAtLeast(track), track),
                cornerRadius = radius,
            )
        }
    }
}
