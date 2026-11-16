/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import app.valen.core.PlaybackCoordinator
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The playing song's position, as a state to hand to the one widget that shows it. Read `.value`
 * inside that widget, never above it: the position moves four times a second, and whatever reads
 * it is composed again each time. Everything else reads `coordinator.nowPlaying`.
 */
@Composable
fun rememberPosition(coordinator: PlaybackCoordinator): State<Long> {
    val flow = remember(coordinator) { coordinator.snapshot.map { it?.positionMs ?: 0L }.distinctUntilChanged() }
    return flow.collectAsState(coordinator.snapshot.value?.positionMs ?: 0L)
}
