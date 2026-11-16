/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.artist

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.valen.core.MergedRelease
import app.valen.core.ServiceArtist
import app.valen.core.ServicePlaylist
import app.valen.core.artistsIn

/** A page of the music catalogue: an artist, one of their releases, a playlist that features them. */
sealed interface BrowsePage {
    /** An artist by name; [known] are their ids already in hand (a search, a related artist). */
    data class Artist(val name: String, val known: List<ServiceArtist> = emptyList()) : BrowsePage
    data class Album(val release: MergedRelease) : BrowsePage
    data class Listing(val playlist: ServicePlaylist) : BrowsePage

    /** Each page once on the stack, whatever path led to it again. */
    val key: String
        get() = when (this) {
            is Artist -> "artist:" + app.valen.core.artistKey(name)
            is Album -> "album:" + release.key + ":" + release.copies.firstOrNull()?.id
            is Listing -> "list:" + playlist.provider.name + ":" + playlist.id
        }
}

/**
 * Where the catalogue pages stand: a stack, as a browser's history is. Any screen opens a page
 * ([open]); back goes one page down ([back]), and past the first one the pages close.
 *
 * The app sets [onShow] and [onHide] to put the pages on screen and take them away.
 */
@Stable
class Browser {
    var stack by mutableStateOf<List<BrowsePage>>(emptyList())
        private set

    val top: BrowsePage? get() = stack.lastOrNull()

    /** How the stack last moved: true for a page opened, false for one closed (the animation's way). */
    var forward by mutableStateOf(true)
        private set

    var onShow: () -> Unit = {}
    var onHide: () -> Unit = {}

    fun open(page: BrowsePage) {
        forward = true
        // Opening a page already in the stack goes back to it rather than piling up a loop.
        val at = stack.indexOfFirst { it.key == page.key }
        stack = if (at >= 0) stack.take(at) + page else stack + page
        onShow()
    }

    /** The artist of a song line ("A, B feat. C"): the first one named. */
    fun openArtistOf(line: String) {
        val first = artistsIn(line).firstOrNull() ?: return
        open(BrowsePage.Artist(first))
    }

    /** One page back; true while pages are left to show. */
    fun back(): Boolean {
        forward = false
        stack = stack.dropLast(1)
        if (stack.isEmpty()) onHide()
        return stack.isNotEmpty()
    }

    /** The pages are gone from the screen already (another place was opened): the stack follows. */
    fun forget() {
        stack = emptyList()
    }

    /** Every page closed (the pages' sheet was swept away). */
    fun clear() {
        if (stack.isEmpty()) return
        stack = emptyList()
        onHide()
    }
}

/** The app's one catalogue browser. */
val LocalBrowser = staticCompositionLocalOf { Browser() }
