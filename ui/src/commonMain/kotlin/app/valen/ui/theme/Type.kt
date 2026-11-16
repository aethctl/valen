/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import valen.ui.generated.resources.Res
import valen.ui.generated.resources.bricolage_semibold
import valen.ui.generated.resources.inter_medium
import valen.ui.generated.resources.inter_regular
import valen.ui.generated.resources.inter_semibold

/**
 * Two voices. Bricolage Grotesque is the app speaking: the greeting, screen titles, section
 * titles, the wordmark. Inter is the music: every song title, artist and control, set semi-bold
 * and tight so a list reads as dense, confident type rather than default UI text.
 *
 * The split is practical as well: song titles arrive in any script, and Inter covers far more of
 * them than a display face does, so a title never changes typeface halfway through.
 *
 * Tracking tightens as size grows. Nothing in the app is uppercase.
 */
@Composable
fun valenTypography(): Typography {
    val display = FontFamily(Font(Res.font.bricolage_semibold, FontWeight.SemiBold))
    val ui = FontFamily(
        Font(Res.font.inter_regular, FontWeight.Normal),
        Font(Res.font.inter_medium, FontWeight.Medium),
        Font(Res.font.inter_semibold, FontWeight.SemiBold),
    )
    return remember(display, ui) {
        fun voice(size: Int, line: Int, tracking: Float) = TextStyle(
            fontFamily = display,
            fontWeight = FontWeight.SemiBold,
            fontSize = size.sp,
            lineHeight = line.sp,
            letterSpacing = tracking.em,
        )
        fun text(weight: FontWeight, size: Int, line: Int, tracking: Float) = TextStyle(
            fontFamily = ui,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = line.sp,
            letterSpacing = tracking.em,
        )
        Typography(
            displayLarge = voice(60, 60, -0.035f),
            displayMedium = voice(44, 46, -0.03f),
            displaySmall = voice(34, 36, -0.03f),
            headlineLarge = voice(28, 32, -0.025f),
            headlineMedium = voice(22, 26, -0.02f),
            headlineSmall = voice(19, 24, -0.02f),
            titleLarge = text(FontWeight.SemiBold, 18, 24, -0.03f),
            titleMedium = text(FontWeight.SemiBold, 15, 20, -0.025f),
            titleSmall = text(FontWeight.SemiBold, 13, 18, -0.02f),
            bodyLarge = text(FontWeight.Medium, 16, 24, -0.02f),
            bodyMedium = text(FontWeight.Medium, 14, 20, -0.015f),
            bodySmall = text(FontWeight.Medium, 13, 18, -0.012f),
            labelLarge = text(FontWeight.SemiBold, 14, 20, -0.02f),
            labelMedium = text(FontWeight.SemiBold, 13, 16, -0.015f),
            labelSmall = text(FontWeight.Medium, 11, 14, 0f),
        )
    }
}

/** Styles for the music itself, at the sizes where a song title is the headline of a screen. */
object TrackType {
    /** The hero on a wide Home. */
    val display: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(fontSize = 52.sp, lineHeight = 54.sp, letterSpacing = (-0.045).em)

    /** The stage on a wide window; the hero on a phone. */
    val title: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.04).em)

    /** The stage on a phone. */
    val headline: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.035).em)
}

/**
 * The song's words on the stage, in the music's voice (Inter, never Bricolage: lyrics arrive in
 * any script). Large and tight so a line reads from across a room; backing vocals sit under their
 * line, smaller and in the medium weight.
 */
object LyricType {
    /** A sung line on a wide stage. */
    val line: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.035).em)

    /** A sung line on a phone or a narrow stage. */
    val lineCompact: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.03).em)

    /** Backing vocals under their line. */
    val backing: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em)

    val backingCompact: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.015).em)

    /** Words that are not timed to the song, read like a page. */
    val plain: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.025).em)
}

/**
 * "Your week": a poster's own sizes, in the app's voice (Bricolage). They are set for the poster's
 * design width ([Layout.storyDesignWidth]); the poster scales as a whole, so they read the same on
 * a phone and on a wide window.
 */
object RecapType {
    /** The one big number of a page ("11 h 6 min", "23"). */
    val figure: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp, lineHeight = 70.sp, letterSpacing = (-0.045).em)

    /** The story's title on its first page. */
    val title: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.displayLarge.copy(fontSize = 66.sp, lineHeight = 62.sp, letterSpacing = (-0.04).em)
}

/**
 * Times and counts: tabular figures, so a ticking position never shifts the text beside it.
 * [large] for the stage, where the times sit under a thick rail and are read from further away.
 */
@Composable
@ReadOnlyComposable
fun timeStyle(large: Boolean = false): TextStyle {
    val base = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    return if (large) base.copy(fontSize = 12.sp, lineHeight = 16.sp) else base
}
