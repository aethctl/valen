/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.ValenColors

/** A thin pill in the page's quiet ink that brightens under the pointer; drag it or click the track. */
private val barStyle: ScrollbarStyle
    get() = ScrollbarStyle(
        minimalHeight = 48.dp,
        thickness = 8.dp,
        shape = Radii.pillShape,
        hoverDurationMillis = Motion.PressMs,
        unhoverColor = ValenColors.Text3,
        hoverColor = ValenColors.Text2,
    )

@Composable
actual fun PageScrollbar(state: ScrollState, modifier: Modifier) {
    VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier, style = barStyle)
}

@Composable
actual fun PageScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(adapter = rememberScrollbarAdapter(state), modifier = modifier, style = barStyle)
}
