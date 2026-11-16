/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
private fun materialScheme() = darkColorScheme(
    primary = ValenColors.Primary,
    onPrimary = ValenColors.OnPrimary,
    primaryContainer = ValenColors.Container,
    onPrimaryContainer = ValenColors.OnContainer,
    secondary = ValenColors.Text2,
    onSecondary = ValenColors.Background,
    secondaryContainer = ValenColors.Surface2,
    onSecondaryContainer = ValenColors.Text,
    tertiary = ValenColors.Primary,
    onTertiary = ValenColors.Background,
    background = ValenColors.Background,
    onBackground = ValenColors.Text,
    surface = ValenColors.Surface,
    onSurface = ValenColors.Text,
    surfaceVariant = ValenColors.Surface2,
    onSurfaceVariant = ValenColors.Text2,
    surfaceTint = Color.Transparent,
    outline = ValenColors.Hairline,
    outlineVariant = ValenColors.Hairline,
    error = ValenColors.Danger,
    onError = ValenColors.Text,
    scrim = Color.Black,
)

/**
 * The app's only theme. Dark, neutral, one accent supplied by the current artwork at the screen
 * that needs it — the theme itself never guesses a colour.
 */
@Composable
fun ValenTheme(
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val typography = valenTypography()
    MaterialTheme(colorScheme = materialScheme(), typography = typography) {
        CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
            content()
        }
    }
}
