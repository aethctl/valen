/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Touch scrolls by hand: no bar. */
@Composable
actual fun PageScrollbar(state: ScrollState, modifier: Modifier) = Unit

@Composable
actual fun PageScrollbar(state: LazyListState, modifier: Modifier) = Unit
