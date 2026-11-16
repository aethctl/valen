/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The 4pt grid, named by step rather than size so layouts read as rhythm. */
object Space {
    val step1 = 4.dp
    val step2 = 8.dp
    val step3 = 12.dp
    val step4 = 16.dp
    val step5 = 20.dp
    val step6 = 24.dp
    val step8 = 32.dp
    val step10 = 40.dp
    val step12 = 48.dp
    val step16 = 64.dp
    val step24 = 96.dp
}

/**
 * One shape per component; never mix two on the same element. Everything that is or holds a cover
 * is a [Squircle]; everything a finger presses as a control is a pill.
 *
 * The values are superellipse reach, not circular radius (see [Squircle]).
 */
object Radii {
    /** Covers in rows, the dock and the mini player (40–56dp). */
    val cover = 12.dp

    /** Rows, quick tiles, inline messages. */
    val row = 18.dp

    /** Grouped cards, shelf covers. */
    val card = 28.dp

    /** The stage cover, the hero cover, the side panel. */
    val artwork = 40.dp
    val pill = 999.dp

    val coverShape: Shape = Squircle(cover)
    val rowShape: Shape = Squircle(row)
    val cardShape: Shape = Squircle(card)
    val artworkShape: Shape = Squircle(artwork)
    val pillShape: Shape = RoundedCornerShape(pill)
}

object Layout {
    val phoneGutter = 20.dp
    val railGutter = 28.dp
    val desktopGutter = 40.dp

    /** Text columns (settings, an empty state) stop here however wide the window is. */
    val readingMaxWidth = 720.dp

    /** Settings on a wide window: its index and the open part beside it stop here. */
    val settingsMaxWidth = 1040.dp
    val settingsIndexWidth = 264.dp
    val settingsIndexRow = 60.dp

    val rowHeight = 64.dp

    /** The gap between floating chrome and the window edge, and between two pieces of chrome. */
    val chromeInset = 12.dp
    val sidebarWidth = 236.dp
    val railWidth = 68.dp
    val dockHeight = 72.dp
    val dockMaxWidth = 1000.dp
    val miniPlayerHeight = 64.dp
    val bottomNavHeight = 60.dp

    /**
     * "Your week" is a poster: designed at this width and scaled as a whole to fill the phone, or
     * at [storyAspect] to the height of a wide window.
     */
    val storyDesignWidth = 400.dp
    const val storyAspect = 9f / 16f

    /** Nothing interactive is smaller than this. */
    val hitTarget = 44.dp

    /** The width breakpoints. Layout follows width, never platform. */
    val wideFrom = 1000.dp
    val railFrom = 700.dp
}

/** Liquid glass, as measured on the chrome that carries it. */
object Glass {
    /** How far the page behind is blurred. */
    val blur = 30.dp

    /** How deep the rim's refraction reaches into the shape, and how far it bends what is behind. */
    val rimDepth = 22.dp
    val refraction = 14.dp

    /** The pointer-follow highlight on the rim reaches this far around the pointer. */
    val glintReach = 220.dp

    /** Colour separation at the rim, as a share of the bend: a hint of prism, no more. */
    const val dispersion = 0.14f

    /** Colours behind glass are this much more vivid, so a dull blur still reads as the cover. */
    const val vibrancy = 1.55f
}

/**
 * What the floating chrome takes from a screen. Screens draw edge to edge and keep their content
 * clear of these, so a wash can run under the side panel while text never does.
 */
@Immutable
data class Chrome(
    /** Width taken on the left by the side panel or rail, including its inset. */
    val start: Dp = 0.dp,
    /** Height taken at the bottom by the dock, or the phone's mini player and tab bar. */
    val bottom: Dp = 0.dp,
    val gutter: Dp = Layout.phoneGutter,
    /** True below [Layout.railFrom]: one column, sheets, no hover. */
    val compact: Boolean = true,
)

val LocalChrome = compositionLocalOf { Chrome() }
