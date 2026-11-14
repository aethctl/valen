/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors

/**
 * A phone sheet that owns the whole screen: Now Playing and the Queue. It rises from the bottom
 * (320ms in, 240ms out) over a fading scrim. The sheet is opaque: whatever wash its screen draws
 * is the only translucent thing on it.
 */
@Composable
fun FullScreenSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val enter = if (reduced) {
        fadeIn(tween(Motion.ReducedTweenMs, easing = Motion.Standard))
    } else {
        slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(Motion.SheetEnterMs, easing = Motion.Enter),
        ) + fadeIn(tween(Motion.SheetEnterMs))
    }
    val exit = if (reduced) {
        fadeOut(tween(Motion.ReducedMs, easing = Motion.Standard))
    } else {
        slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(Motion.SheetExitMs, easing = Motion.Exit),
        ) + fadeOut(tween(Motion.SheetExitMs))
    }
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(if (reduced) Motion.ReducedMs else Motion.EnterMs, easing = Motion.Enter)),
            exit = fadeOut(tween(if (reduced) Motion.ReducedMs else Motion.ExitMs, easing = Motion.Exit)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.56f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = enter,
            exit = exit,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ValenColors.Background)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                content()
            }
        }
    }
}
