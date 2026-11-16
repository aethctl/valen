/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.sound

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.SoundNote
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import app.valen.ui.util.clockText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One line of the notebook as it is being written, struck out, or put away. */
@Stable
private class NoteLine(val id: Int, val key: String, text: String, words: String, val title: Boolean, val wait: Int) {
    var text by mutableStateOf(text)
    var words by mutableStateOf(words)
    var stamp by mutableStateOf(clockText())
    /** What it said before its last change, struck out while the new value is written. */
    var old by mutableStateOf<String?>(null)
    var leaving by mutableStateOf(false)
    /** Bumped on every rewrite, which writes the line again. */
    var version by mutableIntStateOf(0)
    val write = Animatable(0f)
    val strikeOld = Animatable(0f)
    val strike = Animatable(0f)
    val height = Animatable(0f)
}

/**
 * The notes, kept the way notes are kept: in the order they were taken. A new effect is a new
 * line, written in at a pen's pace with the nib leading it; a value that changes is struck out
 * with the marker and the new one written in its place; an effect turned off is struck through and
 * folds away. Each line carries the time it was written. The first note ([SoundNote.key] "title")
 * is the page's heading.
 *
 * [dimmed] greys the ink (effects off, or a player that cannot take them).
 */
@Composable
internal fun Notes(notes: List<SoundNote>, dimmed: Boolean, modifier: Modifier = Modifier) {
    val lines = remember { mutableStateListOf<NoteLine>() }
    var ids by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(notes) {
        val wanted = notes.associateBy { it.key }
        lines.forEach { line ->
            val note = wanted[line.key]
            when {
                line.leaving -> Unit
                note == null -> line.leaving = true
                note.text != line.text || note.words != line.words -> {
                    // Unwritten at once, in the same frame as the new words arrive: until the old
                    // value is struck out, it is the old value that shows, not the new one.
                    line.write.snapTo(0f)
                    line.strikeOld.snapTo(0f)
                    line.old = line.text.takeIf { it != note.text }
                    line.text = note.text
                    line.words = note.words
                    line.stamp = clockText()
                    line.version++
                }
            }
        }
        // Opening the notebook writes what is already set one line after another; later notes
        // are written as they come.
        var queued = 0
        notes.forEach { note ->
            if (lines.none { it.key == note.key && !it.leaving }) {
                val wait = if (opened) 0 else queued++ * Motion.NoteStaggerMs
                val line = NoteLine(++ids, note.key, note.text, note.words, title = note.key == TITLE, wait = wait)
                // The heading stays first; everything else goes at the end of the page.
                if (line.title) lines.add(0, line) else lines += line
            }
        }
        opened = true
    }
    Column(modifier.semantics { contentDescription = "Sound notes" }) {
        lines.forEach { line ->
            key(line.id) {
                NoteRow(line, dimmed, onGone = { lines.remove(line) })
            }
        }
    }
}

/** The key of the note that heads the page. */
internal const val TITLE = "title"

@Composable
private fun NoteRow(line: NoteLine, dimmed: Boolean, onGone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val rows = if (line.words.isBlank()) 1 else 2
    // Written, or written again: the line opens, the old value is struck, the new one is written.
    LaunchedEffect(line.version) {
        if (reduced) {
            line.height.snapTo(1f)
            line.write.snapTo(1f)
            line.old = null
            return@LaunchedEffect
        }
        if (line.version == 0) {
            delay(line.wait.toLong())
            line.height.animateTo(1f, tween(Motion.EnterMs, easing = Motion.Enter))
        }
        if (line.old != null) {
            line.strikeOld.snapTo(0f)
            line.strikeOld.animateTo(1f, tween(Motion.StrikeMs, easing = Motion.Standard))
        }
        line.write.snapTo(0f)
        val ms = min(line.text.length * Motion.WriteLetterMs, Motion.WriteMaxMs)
        line.write.animateTo(1f, tween(ms, easing = LinearEasing))
        delay(Motion.StruckHoldMs)
        line.old = null
    }
    // Put away: struck through, then folded shut.
    LaunchedEffect(line.leaving) {
        if (!line.leaving) return@LaunchedEffect
        if (reduced) {
            onGone()
            return@LaunchedEffect
        }
        line.strike.animateTo(1f, tween(Motion.StrikeMs, easing = Motion.Standard))
        delay(240)
        launch { line.height.animateTo(0f, tween(Motion.ExitMs, easing = Motion.Exit)) }.join()
        onGone()
    }
    val ink = if (dimmed) 0.45f else 1f
    val primary = ValenColors.Primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val h = (placeable.height * line.height.value).roundToInt()
                layout(placeable.width, h) { placeable.place(0, 0) }
            }
            .graphicsLayer { alpha = ink * (0.35f + 0.65f * line.height.value) },
    ) {
        Box(Modifier.fillMaxWidth().height(RuleGap)) {
            // The bullet: a dab of the marker in the margin, on the line.
            Canvas(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = MarginAt - 18.dp, bottom = 7.dp)
                    .size(10.dp)
                    .graphicsLayer {
                        val s = (line.height.value * 1.15f).coerceAtMost(1f)
                        scaleX = s
                        scaleY = s
                    },
            ) { drawCircle(primary, 3.5.dp.toPx()) }
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(start = MarginAt + Space.step3, end = Space.step4, bottom = 4.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    val shown = line.old
                    if (shown != null && line.write.value < 0.02f) {
                        Written(shown, line.title, strike = line.strikeOld.value, reveal = 1f, nib = false)
                    } else {
                        Written(line.text, line.title, strike = line.strike.value, reveal = line.write.value, nib = line.write.value in 0.001f..0.999f)
                    }
                }
                Text(
                    text = line.stamp,
                    style = timeStyle(),
                    color = ValenColors.Text3,
                    modifier = Modifier.padding(start = Space.step2),
                )
            }
        }
        if (rows == 2) {
            Box(Modifier.fillMaxWidth().height(RuleGap)) {
                Text(
                    text = line.words,
                    style = MaterialTheme.typography.bodySmall,
                    color = ValenColors.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = MarginAt + Space.step3, end = Space.step4, bottom = 5.dp)
                        .graphicsLayer { alpha = ((line.write.value - 0.6f) / 0.4f).coerceIn(0f, 1f) },
                )
            }
        }
    }
}

/**
 * The words of a note as the pen leaves them: revealed up to [reveal] with the nib — a dot of the
 * marker — riding the edge, and a marker line drawn through them up to [strike].
 */
@Composable
private fun Written(text: String, title: Boolean, strike: Float, reveal: Float, nib: Boolean) {
    val primary = ValenColors.Primary
    val wobble = remember { Animatable(0f) }
    LaunchedEffect(nib) {
        if (!nib) return@LaunchedEffect
        while (true) {
            wobble.animateTo(wobble.value + 1f, tween(160, easing = LinearEasing))
        }
    }
    Text(
        text = text,
        style = if (title) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
        color = ValenColors.Text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.drawWithContent {
            val edge = size.width * reveal
            clipRect(right = edge) { this@drawWithContent.drawContent() }
            if (nib) {
                val y = size.height * 0.72f + sin(wobble.value * 6.283f) * 1.5.dp.toPx()
                drawCircle(primary, 2.6.dp.toPx(), Offset(edge, y))
            }
            if (strike > 0f) {
                // The marker goes through at about the middle of the letters, a little uphill.
                val y0 = size.height * 0.58f
                val path = Path().apply {
                    moveTo(0f, y0 + 1.dp.toPx())
                    quadraticTo(size.width * strike * 0.5f, y0 - 1.5.dp.toPx(), size.width * strike, y0 - 0.5.dp.toPx())
                }
                drawPath(path, primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        },
    )
}
