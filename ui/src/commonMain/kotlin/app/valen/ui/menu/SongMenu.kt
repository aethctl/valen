/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.menu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.Track
import app.valen.ui.components.IconTap
import app.valen.ui.components.onSecondaryClick
import app.valen.ui.theme.ValenColors

/** Where a song's menu was opened from; what it offers follows from it. */
sealed interface MenuOrigin {
    /** The player itself (the stage, the dock, the mini player): the sleep timer lives here. */
    data object Player : MenuOrigin

    /** A song in a list anywhere else: search, Home. */
    data object Song : MenuOrigin

    /** A row of the queue, at [index]. */
    data class Queue(val index: Int) : MenuOrigin

    /** A song inside the playlist [playlistId]. */
    data class InPlaylist(val playlistId: String) : MenuOrigin
}

/** The menu's pages: its actions, the lists to add to, the sleep timer. */
enum class MenuPage { Main, AddToPlaylist, Sleep }

/**
 * One opening of the menu. [anchor] is what it grows out of, in root coordinates — a button, or
 * the point a right click landed on — and null for the phone's sheet, which comes from the bottom.
 * [alignEnd]: line the menu's end up with the anchor's (a button), or start at it (a pointer).
 */
@Immutable
data class MenuRequest(
    val track: Track,
    val origin: MenuOrigin,
    val anchor: Rect?,
    val alignEnd: Boolean,
    val page: MenuPage,
    val id: Long,
)

/**
 * The one song menu in the app. Anything that shows a song asks it to open; the shell draws it
 * as floating chrome above everything, so a menu never lives inside the page it was opened from.
 */
@Stable
class SongMenuState {
    var request by mutableStateOf<MenuRequest?>(null)
        private set
    private var opened = 0L

    fun open(
        track: Track,
        origin: MenuOrigin,
        anchor: Rect? = null,
        alignEnd: Boolean = true,
        page: MenuPage = MenuPage.Main,
    ) {
        request = MenuRequest(track, origin, anchor, alignEnd, page, ++opened)
    }

    fun close() {
        request = null
    }
}

val LocalSongMenu = staticCompositionLocalOf { SongMenuState() }

/**
 * ⋯ that opens a song's menu, which grows out of the button itself. With no song (nothing
 * playing) it is not drawn.
 */
@Composable
fun MoreButton(
    track: Track?,
    origin: MenuOrigin,
    modifier: Modifier = Modifier,
    tint: Color = ValenColors.Text2,
    iconSize: Dp = 22.dp,
    page: MenuPage = MenuPage.Main,
    label: String = "More",
) {
    if (track == null) return
    val menu = LocalSongMenu.current
    val bounds = remember { arrayOf(Rect.Zero) }
    IconTap(
        icon = Icons.Rounded.MoreHoriz,
        label = label,
        onClick = { menu.open(track, origin, anchor = bounds[0], alignEnd = true, page = page) },
        tint = tint,
        iconSize = iconSize,
        modifier = modifier.onGloballyPositioned { bounds[0] = it.boundsInRoot() },
    )
}

/** A right click on a song opens its menu at the pointer. */
@Composable
fun Modifier.songMenuOnRightClick(track: Track, origin: MenuOrigin): Modifier {
    val menu = LocalSongMenu.current
    return onSecondaryClick { at -> menu.open(track, origin, anchor = Rect(at, Size(1f, 1f)), alignEnd = false) }
}

/** What a long press on a song does: open its menu (the sheet, on a phone). */
@Composable
fun rememberSongMenuOpener(track: Track, origin: MenuOrigin): () -> Unit {
    val menu = LocalSongMenu.current
    return remember(menu, track, origin) { { menu.open(track, origin) } }
}
