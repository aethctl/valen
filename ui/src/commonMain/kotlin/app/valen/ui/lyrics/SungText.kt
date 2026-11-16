/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import app.valen.core.LyricWord
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlin.math.PI
import kotlin.math.sin

/** Where a line stands in the song. */
internal enum class LinePhase {
    /** Still to come. */
    Upcoming,

    /** Being sung now. */
    Sung,

    /** Over, but the pen is still finishing a mark on it. */
    Trailing,

    /** Over. */
    Past,
}

/**
 * Words being sung. The paragraph is drawn once in white and masked: what has been sung is full
 * white, what has not is at [dim], and the edge between them is soft and moves through each word
 * at the pace it is sung. A word lifts a little as its singing starts and stays up until the line
 * is over; a held note has a wave run through its letters. The marks the pen makes ([marks], one
 * per word or null) are drawn over the letters, or under them for a swipe.
 *
 * With no [words] (a line timed as a whole) the line brightens and lifts as one.
 */
@Composable
internal fun SungText(
    text: String,
    words: List<LyricWord>,
    marks: List<Mark?>,
    phase: LinePhase,
    clock: LongState,
    style: TextStyle,
    align: TextAlign,
    dim: Float,
    /** Settings → Lyrics → Moving words off: the words light up but do not rise or ripple. */
    still: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val focus = phase == LinePhase.Sung
    val bright = remember { Animatable(if (focus) 1f else 0f) }
    LaunchedEffect(focus) {
        val target = if (focus) 1f else 0f
        if (reduced) bright.snapTo(target) else bright.animateTo(target, tween(Motion.LyricLineMs, easing = Motion.Standard))
    }
    val shown = remember(text, words) {
        if (words.isEmpty()) text else words.joinToString("") { it.text + if (it.space) " " else "" }.trimEnd()
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current.density
    val primary = ValenColors.Primary
    val container = ValenColors.Container
    // Worked out once per laid-out paragraph and kept across phases: a line's phase changes
    // with every line sung, and rebuilding the geometry then (a box per letter) cost every
    // line's first frame again at each change.
    val geometries = remember { GeometryKeeper() }
    Text(
        text = shown,
        style = style,
        color = Color.White,
        textAlign = align,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                val result = layout
                val geometry = if (result != null && result.layoutInput.text.text == shown) {
                    geometries.of(result, words, marks, style.fontSize.toPx(), density)
                } else {
                    null
                }
                // Only a line being sung (or finishing a mark) reads the clock, so only it is
                // drawn again on every frame.
                val t = when (phase) {
                    LinePhase.Sung, LinePhase.Trailing -> clock.longValue
                    LinePhase.Past -> FAR_FUTURE
                    LinePhase.Upcoming -> FAR_PAST
                }
                val b = bright.value
                if (geometry == null) {
                    // Not laid out yet (its first frame): the Text's own drawing, unmasked.
                    drawContent()
                    return@drawWithContent
                }
                val em = geometry.em
                for (i in marks.indices) {
                    val ink = geometry.inks[i] ?: continue
                    if (ink.kind.behind) ink.draw(this, marks[i]!!.progress(t), container.copy(alpha = 0.9f))
                }
                // The words are drawn from the paragraph with their tone in the paint, never by
                // dimming a layer: an offscreen layer for every line on show was most of what a
                // phone's GPU spent on the lyrics.
                val layoutResult = geometry.layout
                if (words.isNotEmpty() && b <= 0.001f && phase != LinePhase.Sung) {
                    // Resting (to come, or over): all one tone.
                    drawText(layoutResult, color = Color.White.copy(alpha = dim))
                } else if (words.isEmpty()) {
                    // Timed by line: it comes up as a whole.
                    translate(0f, if (still) 0f else -LIFT * em * b) {
                        drawText(layoutResult, color = Color.White.copy(alpha = dim + (1f - dim) * b))
                    }
                } else {
                    sung(geometry, t, b, dim, reduced, still)
                }
                val markAlpha = 0.72f + 0.28f * b
                for (i in marks.indices) {
                    val ink = geometry.inks[i] ?: continue
                    if (!ink.kind.behind) ink.draw(this, marks[i]!!.progress(t), primary.copy(alpha = markAlpha))
                }
            },
    )
}

/**
 * The words being sung. Each row is painted with its own soft edge (a gradient from sung to
 * unsung in the text's paint, no mask layer), and words that have started rise: a run of words at
 * the same height is drawn in one go, so a row costs a few draws, not one per word.
 */
private fun DrawScope.sung(g: Geometry, t: Long, bright: Float, dim: Float, reduced: Boolean, still: Boolean) {
    val em = g.em
    val pad = em
    val sungAlpha = dim + (1f - dim) * bright
    val sung = Color.White.copy(alpha = sungAlpha)
    val unsung = Color.White.copy(alpha = dim)
    val feather = FEATHER * em
    val lift = if (reduced || still) 0f else LIFT * em * bright
    for (row in 0 until g.rows) {
        val top = g.bandTop(row, pad)
        val bottom = g.bandBottom(row, pad, size.height)
        val edge = g.edge(row, t, reduced, feather)
        val brush = if (g.rtl) {
            Brush.horizontalGradient(listOf(unsung, sung), startX = edge - feather / 2f, endX = edge + feather / 2f)
        } else {
            Brush.horizontalGradient(listOf(sung, unsung), startX = edge - feather / 2f, endX = edge + feather / 2f)
        }
        val pieces = g.piecesOn(row)
        if (lift <= 0f || pieces.isEmpty()) {
            clipRect(-pad, top, size.width + pad, bottom) { drawText(g.layout, brush) }
            continue
        }
        // Pieces in the order they stand on the row, left to right.
        val ordered = g.leftToRight(row)
        var k = 0
        while (k < ordered.size) {
            val piece = ordered[k]
            val word = g.words[piece.word]
            val left = ordered.getOrNull(k - 1)?.let { (it.right + piece.left) / 2f } ?: -pad
            val held = word.endMs - word.startMs >= WAVE_MS && piece.count >= 3 && t in word.startMs..(word.endMs + WAVE_TAIL_MS)
            val up = lift * Motion.Enter.transform(((t - word.startMs).toFloat() / Motion.WordLiftMs).coerceIn(0f, 1f))
            if (held) {
                // A held note: a crest runs through its letters as it is held.
                val right = ordered.getOrNull(k + 1)?.let { (piece.right + it.left) / 2f } ?: (size.width + pad)
                val per = (word.endMs - word.startMs).toFloat() / g.lengths[piece.word]
                for (c in 0 until piece.count) {
                    val box = g.layout.getBoundingBox(piece.global + c)
                    val at = word.startMs + per * (piece.from + c)
                    val local = ((t - at) / (per * 2.6f).coerceIn(160f, 520f)).coerceIn(0f, 1f)
                    val crest = lift * 1.25f * sin(local * PI.toFloat())
                    val l = if (c == 0) left else box.left
                    val r = if (c == piece.count - 1) right else box.right
                    clipRect(l, top, r, bottom) { translate(0f, -(up + crest)) { drawText(g.layout, brush) } }
                }
                k++
                continue
            }
            // The run of following words at the same height (all still down, or all fully up).
            var end = k
            while (end + 1 < ordered.size) {
                val next = ordered[end + 1]
                val nextWord = g.words[next.word]
                val nextHeld = nextWord.endMs - nextWord.startMs >= WAVE_MS && next.count >= 3 && t in nextWord.startMs..(nextWord.endMs + WAVE_TAIL_MS)
                val nextUp = lift * Motion.Enter.transform(((t - nextWord.startMs).toFloat() / Motion.WordLiftMs).coerceIn(0f, 1f))
                if (nextHeld || nextUp != up) break
                end++
            }
            val last = ordered[end]
            val right = ordered.getOrNull(end + 1)?.let { (last.right + it.left) / 2f } ?: (size.width + pad)
            clipRect(left, top, right, bottom) { translate(0f, -up) { drawText(g.layout, brush) } }
            k = end + 1
        }
    }
}

/** The last geometry made, kept while its paragraph and marks are the same. */
private class GeometryKeeper {
    private var layout: TextLayoutResult? = null
    private var marks: List<Mark?>? = null
    private var words: List<LyricWord>? = null
    private var em = 0f
    private var kept: Geometry? = null

    fun of(result: TextLayoutResult, words: List<LyricWord>, marks: List<Mark?>, em: Float, density: Float): Geometry {
        val k = kept
        if (k != null && layout === result && this.marks === marks && this.words === words && this.em == em) return k
        return Geometry.of(result, words, marks, em, density).also {
            kept = it
            layout = result
            this.marks = marks
            this.words = words
            this.em = em
        }
    }
}

/** A word's run of letters on one row (a word too long for a row is in two pieces). */
private class Piece(val word: Int, val row: Int, val left: Float, val right: Float, val from: Int, val count: Int, val global: Int)

/**
 * What a laid-out line needs to be drawn sung: its rows, the marks' ink and, only once the line is
 * sung, each word's run of letters. Most lines on show are never sung while the pane is open, and
 * reading a box for every letter of every line was the pane's first-frame cost on a phone.
 */
private class Geometry(
    val layout: TextLayoutResult,
    val words: List<LyricWord>,
    val lengths: IntArray,
    /** Where each word starts in the paragraph's text. */
    private val starts: IntArray,
    val rows: Int,
    val rowTop: FloatArray,
    val rowBottom: FloatArray,
    val rowLeft: FloatArray,
    val rowRight: FloatArray,
    val rtl: Boolean,
    val em: Float,
    val inks: Array<ScribbleInk?>,
) {
    /** The band of [row]: from halfway to the row above (or past the top) to halfway to the one below. */
    fun bandTop(row: Int, pad: Float): Float = if (row == 0) -pad else (rowBottom[row - 1] + rowTop[row]) / 2f
    fun bandBottom(row: Int, pad: Float, height: Float): Float = if (row == rows - 1) height + pad else (rowBottom[row] + rowTop[row + 1]) / 2f

    private val pieces: List<Piece> by lazy(LazyThreadSafetyMode.NONE) {
        val all = ArrayList<Piece>(words.size + 2)
        for (i in words.indices) piecesOf(layout, i, starts[i], words[i].text.length, all)
        all
    }

    /** In the order they are sung (right to left on an RTL row). */
    private val byRow: Array<List<Piece>> by lazy(LazyThreadSafetyMode.NONE) {
        Array(rows) { r -> pieces.filter { it.row == r }.let { if (rtl) it.sortedByDescending { p -> p.right } else it } }
    }

    /** In the order they stand, left to right. */
    private val byPlace: Array<List<Piece>> by lazy(LazyThreadSafetyMode.NONE) {
        Array(rows) { r -> pieces.filter { it.row == r }.sortedBy { it.left } }
    }

    fun piecesOn(row: Int): List<Piece> = byRow[row]
    fun leftToRight(row: Int): List<Piece> = byPlace[row]

    /** How much of a piece is sung at [t]. */
    private fun sung(piece: Piece, t: Long, reduced: Boolean): Float {
        val w = words[piece.word]
        val p = when {
            t <= w.startMs -> 0f
            t >= w.endMs -> 1f
            reduced -> 1f
            else -> (t - w.startMs).toFloat() / (w.endMs - w.startMs)
        }
        return ((p * lengths[piece.word] - piece.from) / piece.count).coerceIn(0f, 1f)
    }

    /** Where the sung part of [row] ends, as an x the soft edge is centred on. */
    fun edge(row: Int, t: Long, reduced: Boolean, feather: Float): Float {
        val list = byRow[row]
        if (list.isEmpty()) return if (rtl) rowLeft[row] - feather else rowRight[row] + feather
        var edge = if (rtl) rowRight[row] + feather / 2f else rowLeft[row] - feather / 2f
        for (piece in list) {
            val s = sung(piece, t, reduced)
            if (s <= 0f) break
            val span = piece.right - piece.left + feather
            edge = if (rtl) piece.right + feather / 2f - span * s else piece.left - feather / 2f + span * s
            if (s < 1f) break
        }
        return edge
    }

    companion object {
        fun of(result: TextLayoutResult, words: List<LyricWord>, marks: List<Mark?>, em: Float, density: Float): Geometry {
            val rows = result.lineCount
            val rowTop = FloatArray(rows) { result.getLineTop(it) }
            val rowBottom = FloatArray(rows) { result.getLineBottom(it) }
            val rowLeft = FloatArray(rows) { result.getLineLeft(it) }
            val rowRight = FloatArray(rows) { result.getLineRight(it) }
            val rtl = rows > 0 && result.getParagraphDirection(0) == ResolvedTextDirection.Rtl
            val lengths = IntArray(words.size)
            val starts = IntArray(words.size)
            var at = 0
            for ((i, w) in words.withIndex()) {
                lengths[i] = w.text.length.coerceAtLeast(1)
                starts[i] = at
                at += w.text.length + if (w.space) 1 else 0
            }
            // Only a marked word needs its box now: its ink is drawn on every line it stands on.
            val one = ArrayList<Piece>(2)
            val inks = Array(words.size) { i ->
                val mark = marks.getOrNull(i) ?: return@Array null
                one.clear()
                piecesOf(result, i, starts[i], words[i].text.length, one, firstOnly = true)
                val first = one.firstOrNull() ?: return@Array null
                val box = WordBox(first.left, first.right, result.getLineBaseline(first.row), em)
                scribbleInk(mark.kind, box, mark.seed, density, mark.drip)
            }
            return Geometry(result, words, lengths, starts, rows, rowTop, rowBottom, rowLeft, rowRight, rtl, em, inks)
        }

        /** Word [i]'s runs of letters, one per row it stands on, added to [into]. */
        private fun piecesOf(result: TextLayoutResult, i: Int, at: Int, n: Int, into: MutableList<Piece>, firstOnly: Boolean = false) {
            val length = result.layoutInput.text.length
            var c = 0
            while (c < n && at + c < length) {
                val row = result.getLineForOffset(at + c)
                val start = c
                var left = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                while (c < n && at + c < length && result.getLineForOffset(at + c) == row) {
                    val box = result.getBoundingBox(at + c)
                    if (box.width > 0f) {
                        left = minOf(left, box.left)
                        right = maxOf(right, box.right)
                    }
                    c++
                }
                if (left <= right) {
                    into += Piece(i, row, left, right, start, c - start, at + start)
                    if (firstOnly) return
                }
            }
        }
    }
}

/** How far a sung word rises, in ems of its letters. */
private const val LIFT = 0.07f

/** The soft edge between sung and unsung, in ems. */
private const val FEATHER = 0.5f

/** A note held this long gets a wave through its letters. */
private const val WAVE_MS = 1_000L
private const val WAVE_TAIL_MS = 300L

private const val FAR_FUTURE = Long.MAX_VALUE / 4
private const val FAR_PAST = Long.MIN_VALUE / 4
