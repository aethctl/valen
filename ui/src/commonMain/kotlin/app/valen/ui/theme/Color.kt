/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Dark only, by design. The page is black and stays black; the only colour on a screen comes from
 * the artwork that is playing, as a wash behind the content and a tint in the floating player.
 *
 * Secondary and tertiary text are white at an alpha rather than fixed greys, so the same three
 * text steps read correctly on the black page, on a raised surface and on an artwork wash.
 */
object ValenColors {
    val Background = Color(0xFF000000)

    /** One step up from the page: grouped rows, tiles, the frame an image loads into. */
    val Surface = Color(0xFF141416)

    /** Two steps up: inputs, a selected or pressed row. */
    val Surface2 = Color(0xFF1F1F23)
    val Skeleton = Color(0xFF161618)

    val Text = Color(0xFFFFFFFF)
    val Text2 = Color(0x9EFFFFFF)
    val Text3 = Color(0x61FFFFFF)

    /** Ink on a white control (the primary button, a selected chip). */
    val OnLight = Color(0xFF000000)

    /** A separator between rows of one group: 1dp at 10% white. */
    val Hairline = Color(0x1AFFFFFF)

    /**
     * Floating chrome (side panel, player dock, phone bars, toasts): one translucent layer over
     * the page with a light edge. Chrome never sits on other chrome.
     */
    val GlassFill = Color(0xE01E1E22)
    val GlassEdge = Color(0x1FFFFFFF)

    /** White fills for controls that sit on glass or on an artwork wash. */
    val Fill = Color(0x1AFFFFFF)
    val FillStrong = Color(0x29FFFFFF)
    val Hover = Color(0x0FFFFFFF)

    /** Laid over a cover so a white mark on it reads whatever the cover looks like. */
    val CoverScrim = Color(0x85000000)

    val Danger = Color(0xFFFF5A5F)
    val Success = Color(0xFF3DD68C)

    /** Used until an artwork accent has been extracted, and whenever one cannot be. */
    val AccentFallback = Color(0xFFD9D9DE)
}
