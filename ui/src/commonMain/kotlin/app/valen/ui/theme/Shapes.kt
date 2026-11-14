/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A rectangle whose corners are superellipse quarters instead of circle quarters, so the curve
 * eases into each straight edge rather than starting abruptly. Covers and the surfaces that hold
 * them share it, which is what makes a cover inside a tile read as one object.
 *
 * [reach] is how far along each edge the corner extends. A superellipse corner looks tighter than
 * a circular one of the same reach, so the values in [Radii] are larger than a circular radius
 * would be for the same visual roundness.
 *
 * The idea of using one superellipse for covers and their containers is Mochy Music's
 * (`SmoothCornerShape`); this implementation is Valen's own. See NOTICE.
 */
@Immutable
data class Squircle(val reach: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { reach.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        if (r <= 0.5f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val w = size.width
        val h = size.height
        val path = Path()
        path.moveTo(w - r, 0f)
        // Each corner runs clockwise from the edge it leaves to the edge it joins. (cx, cy) is the
        // corner's centre; (ax, ay) points along the edge being left, (bx, by) along the one joined.
        path.corner(w - r, r, 0f, -1f, 1f, 0f, r)
        path.lineTo(w, h - r)
        path.corner(w - r, h - r, 1f, 0f, 0f, 1f, r)
        path.lineTo(r, h)
        path.corner(r, h - r, 0f, 1f, -1f, 0f, r)
        path.lineTo(0f, r)
        path.corner(r, r, -1f, 0f, 0f, -1f, r)
        path.close()
        return Outline.Generic(path)
    }
}

/** Exponent 5 sits between a circle (2) and the squared-up look of 6+: soft, but still a rectangle. */
private const val Exponent = 5.0
private const val Steps = 14
private val Power = 2.0 / Exponent
private val Along = FloatArray(Steps + 1) { cos(PI / 2 * it / Steps).coerceAtLeast(0.0).pow(Power).toFloat() }
private val Across = FloatArray(Steps + 1) { sin(PI / 2 * it / Steps).coerceAtLeast(0.0).pow(Power).toFloat() }

private fun Path.corner(cx: Float, cy: Float, ax: Float, ay: Float, bx: Float, by: Float, r: Float) {
    for (i in 1..Steps) {
        val a = Along[i] * r
        val b = Across[i] * r
        lineTo(cx + ax * a + bx * b, cy + ay * a + by * b)
    }
}
