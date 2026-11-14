/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * The column a screen's content sits in: clear of the side panel, inside the gutters, and as wide
 * as the window gives it. A screen itself draws edge to edge, so a wash can run under the chrome
 * while everything readable stays in here.
 *
 * [maxWidth] is for screens that are read rather than browsed — settings, a lone paragraph. The
 * rest spread out, because a wide window is room for more music, not for margins.
 */
@Composable
fun ContentColumn(
    modifier: Modifier = Modifier,
    maxWidth: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val chrome = LocalChrome.current
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = chrome.start),
    ) {
        Column(
            modifier = Modifier
                .then(if (maxWidth == null) Modifier else Modifier.widthIn(max = maxWidth))
                .fillMaxWidth()
                .padding(horizontal = chrome.gutter),
            content = content,
        )
    }
}

/** A screen's name, in the app's voice: large on a wide window, a step down on a phone. */
@Composable
fun PageTitle(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val compact = LocalChrome.current.compact
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Layout.hitTarget),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.padding(end = Space.step1))
        }
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
            color = ValenColors.Text,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** The space above a screen's first line. */
@Composable
fun PageTop() {
    Spacer(Modifier.height(if (LocalChrome.current.compact) Space.step4 else Space.step10))
}

/** The space under a screen's last line, so it can scroll clear of the floating player. */
@Composable
fun PageBottom() {
    Spacer(Modifier.height(LocalChrome.current.bottom + Space.step12))
}

/**
 * Lets a row's hover and press fill reach [by] past the text column on both sides, so covers and
 * titles stay on the page's left edge while the fill has room around them.
 */
fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val wide = Constraints(
        minWidth = constraints.minWidth + 2 * extra,
        maxWidth = constraints.maxWidth + 2 * extra,
        minHeight = constraints.minHeight,
        maxHeight = constraints.maxHeight,
    )
    val placeable = measurable.measure(wide)
    layout((placeable.width - 2 * extra).coerceAtLeast(0), placeable.height) {
        placeable.place(-extra, 0)
    }
}
