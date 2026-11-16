/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Space

/**
 * Where each page was left: its scroll, and small choices such as which playlist is open. A page
 * leaves the composition whenever another one is shown, which used to throw its scroll away, so a
 * trip to the player sent a long playlist back to its top. The states live here instead, above
 * the width switch, for as long as the app runs.
 *
 * Keys are a page's name, plus whatever tells its contents apart (a playlist's id, a query).
 */
class PageMemory {
    private val scrolls = HashMap<String, ScrollState>()
    private val lists = HashMap<String, LazyListState>()
    private val values = HashMap<String, MutableState<Any?>>()

    fun scroll(key: String): ScrollState = scrolls.getOrPut(key) { ScrollState(0) }

    /** Starts [key]'s page scrolled to [px] (the screenshot run's `VALEN_CAPTURE_SCROLL`). */
    fun startScrolled(key: String, px: Int) {
        scrolls[key] = ScrollState(px)
    }

    fun list(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }

    @Suppress("UNCHECKED_CAST")
    fun <T> value(key: String, initial: T): MutableState<T> =
        values.getOrPut(key) { mutableStateOf(initial) } as MutableState<T>
}

val LocalPageMemory = staticCompositionLocalOf { PageMemory() }

/** A page's scroll that is still where it was when the page comes back. */
@Composable
fun rememberPageScroll(key: String): ScrollState = LocalPageMemory.current.scroll(key)

/** The same for a page that is a lazy list. */
@Composable
fun rememberPageList(key: String): LazyListState = LocalPageMemory.current.list(key)

/** A page's own small state (an open list, a chosen tab) that outlives the page leaving. */
@Composable
fun <T> rememberPageValue(key: String, initial: T): MutableState<T> = LocalPageMemory.current.value(key, initial)

/**
 * A scrollbar to see and drag on the right edge of a page, where a mouse is the pointer (the
 * desktop). A touch screen scrolls by hand and shows nothing.
 */
@Composable
expect fun PageScrollbar(state: ScrollState, modifier: Modifier = Modifier)

@Composable
expect fun PageScrollbar(state: LazyListState, modifier: Modifier = Modifier)

/**
 * A page that scrolls as one column, remembers where it was left under [key], and shows the
 * scrollbar on its right edge, stopping above the floating player.
 */
@Composable
fun ScrollPage(key: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberPageScroll(key)
    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(state), content = content)
        PageScrollbar(state, Modifier.align(Alignment.CenterEnd).pageScrollbar())
    }
}

/** Where a page's scrollbar sits: the right edge, clear of the top and of the floating player. */
@Composable
fun Modifier.pageScrollbar(): Modifier {
    val chrome = LocalChrome.current
    return this
        .then(Modifier.fillMaxHeight())
        .padding(top = Space.step4, bottom = chrome.bottom + Space.step2, end = Space.step1)
}
