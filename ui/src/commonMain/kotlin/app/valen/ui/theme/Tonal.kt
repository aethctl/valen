/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One colour, in tones. Like Material 3's dynamic colour, the whole app is drawn from a single
 * seed — the hue of the cover that is playing — taken at fixed lightness and gentle chroma, so it
 * is always the same family: soft pastel marks, a calm container for what is selected, and dark
 * surfaces carrying just a breath of the hue. Never two hues on screen, never a saturated edge.
 *
 * Tones are set in OKLCH, which keeps lightness even across hues: a yellow and a blue seed give
 * marks of the same weight. A tone the screen cannot show is brought into gamut by giving up
 * chroma, never lightness or hue.
 */
@Immutable
data class ValenScheme(
    val seed: Color,
    /** The page. */
    val background: Color,
    /** One step up: grouped rows, tiles, the frame a cover loads into. */
    val surface: Color,
    /** Two steps up: inputs, a pressed row. */
    val surface2: Color,
    val skeleton: Color,
    /** The one colour: marks, the filled button, the active part of a rail, the tag. */
    val primary: Color,
    val onPrimary: Color,
    /** What is selected: the droplet, a chosen chip, the swipe behind the playing song. */
    val container: Color,
    val onContainer: Color,
    /** Glass: the veil over the blurred page, and the fill where nothing can be blurred. */
    val glassVeil: Color,
    val glassFill: Color,
    /** The wash: a floor and three pools, all of the one hue. */
    val washFloor: Color,
    val washHigh: Color,
    val washMid: Color,
    val washLow: Color,
    /** The player's tint, mixed into its glass. */
    val chrome: Color,
)

object Tonal {
    /** The default colour: pink, on black. */
    val Pink = Color(0xFFE84C8B)
    val DefaultSeed = Pink

    /**
     * The scheme [seed] gives. [black] keeps the page and its surfaces neutral black — the default
     * black and pink — so only the marks, the selection and the glass tint carry the colour.
     */
    fun scheme(seed: Color, black: Boolean = false): ValenScheme {
        val (_, c, h) = oklch(seed)
        // A grey cover stays nearly grey; anything with colour gets the full, gentle chroma.
        val k = (c / 0.08f).coerceIn(0.15f, 1f)
        fun tone(l: Float, chroma: Float, alpha: Float = 1f) = fromOklch(l, chroma * k, h).copy(alpha = alpha)
        // On black the surfaces lose the hue and the page is true black.
        val s = if (black) 0f else 1f
        return ValenScheme(
            seed = seed,
            background = if (black) Color.Black else tone(0.155f, 0.012f),
            surface = tone(0.19f, 0.018f * s),
            surface2 = tone(0.245f, 0.022f * s),
            skeleton = tone(0.18f, 0.014f * s),
            primary = tone(0.82f, 0.11f),
            onPrimary = tone(0.27f, 0.06f),
            container = tone(0.37f, 0.075f),
            onContainer = tone(0.93f, 0.04f),
            glassVeil = tone(if (black) 0.12f else 0.2f, 0.016f * s, 0.62f),
            glassFill = tone(if (black) 0.16f else 0.22f, 0.018f * s, 0.93f),
            washFloor = if (black) tone(0.14f, 0.03f) else tone(0.19f, 0.03f),
            washHigh = tone(0.4f, 0.075f),
            washMid = tone(0.34f, 0.065f),
            washLow = tone(0.29f, 0.055f),
            chrome = tone(0.3f, 0.05f),
        )
    }

    /** OKLCH of an sRGB colour: lightness 0–1, chroma, hue in radians. */
    internal fun oklch(color: Color): Triple<Float, Float, Float> {
        val r = toLinear(color.red)
        val g = toLinear(color.green)
        val b = toLinear(color.blue)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val A = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val B = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return Triple(L.toFloat(), sqrt(A * A + B * B).toFloat(), atan2(B, A).toFloat())
    }

    /** The colour at OKLCH [l], [c], [h], with chroma reduced until it can be shown. */
    internal fun fromOklch(l: Float, c: Float, h: Float): Color {
        var lo = 0f
        var hi = c
        if (inGamut(l, c, h)) return rgb(l, c, h)
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (inGamut(l, mid, h)) lo = mid else hi = mid
        }
        return rgb(l, lo, h)
    }

    private fun linear(l: Float, c: Float, h: Float): DoubleArray {
        val a = c * cos(h)
        val b = c * sin(h)
        val l1 = (l + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m1 = (l - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s1 = (l - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        return doubleArrayOf(
            4.0767416621 * l1 - 3.3077115913 * m1 + 0.2309699292 * s1,
            -1.2684380046 * l1 + 2.6097574011 * m1 - 0.3413193965 * s1,
            -0.0041960863 * l1 - 0.7034186147 * m1 + 1.7076147010 * s1,
        )
    }

    private fun inGamut(l: Float, c: Float, h: Float): Boolean =
        linear(l, c, h).all { it >= -0.0001 && it <= 1.0001 }

    private fun rgb(l: Float, c: Float, h: Float): Color {
        val v = linear(l, c, h)
        return Color(fromLinear(v[0]), fromLinear(v[1]), fromLinear(v[2]), 1f)
    }

    private fun toLinear(v: Float): Double {
        val x = v.toDouble()
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    private fun fromLinear(v: Double): Float {
        val x = v.coerceIn(0.0, 1.0)
        val s = if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - 0.055
        return s.toFloat().coerceIn(0f, 1f)
    }
}
