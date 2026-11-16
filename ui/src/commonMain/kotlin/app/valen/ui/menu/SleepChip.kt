/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.SleepTimer
import app.valen.core.Track
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.tap
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.util.formatTime
import kotlinx.coroutines.delay

/**
 * While a sleep timer runs, the stage says so in one small pill — "Stops in 23 min", or "Stops
 * after this song" — and a press on it opens the timer, grown out of the pill itself.
 */
@Composable
fun SleepChip(coordinator: PlaybackCoordinator, track: Track?, modifier: Modifier = Modifier) {
    val sleep by coordinator.sleep.collectAsState()
    AnimatedVisibility(
        visible = sleep != null && track != null,
        enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
        exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
        modifier = modifier,
    ) {
        val menu = LocalSongMenu.current
        val bounds = remember { arrayOf(Rect.Zero) }
        val source = rememberInteraction()
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                delay(1_000)
                value = System.currentTimeMillis()
            }
        }
        val label = when (val timer = sleep) {
            is SleepTimer.At -> {
                val left = (timer.endsAt - now).coerceAtLeast(0L)
                if (left < 60_000L) "Stops in ${formatTime(left)}" else "Stops in ${(left + 59_999) / 60_000} min"
            }
            else -> "Stops after this song"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(bottom = Space.step3)
                .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
                .pressScale(source)
                .tap(
                    onClick = { track?.let { menu.open(it, MenuOrigin.Player, anchor = bounds[0], alignEnd = false, page = MenuPage.Sleep) } },
                    shape = Radii.pillShape,
                    source = source,
                    rest = ValenColors.Fill,
                )
                .height(36.dp)
                .padding(horizontal = Space.step3),
        ) {
            Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = ValenColors.Primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.step2))
            Text(label, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = ValenColors.Text)
        }
    }
}
