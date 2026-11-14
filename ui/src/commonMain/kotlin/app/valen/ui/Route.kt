/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** The five places the app can be. Now Playing and Queue are sheets on phones. */
enum class Route(val label: String) {
    Home("Home"),
    Search("Search"),
    NowPlaying("Now Playing"),
    Queue("Queue"),
    Settings("Settings"),
    ;

    val icon: ImageVector
        get() = when (this) {
            Home -> Icons.Rounded.Home
            Search -> Icons.Rounded.Search
            NowPlaying -> Icons.Rounded.Album
            Queue -> Icons.AutoMirrored.Rounded.QueueMusic
            Settings -> Icons.Rounded.Settings
        }

    companion object {
        fun from(value: String?): Route = when (value?.trim()?.lowercase()) {
            null, "", "home" -> Home
            "search" -> Search
            "nowplaying", "now_playing", "now-playing", "player" -> NowPlaying
            "queue" -> Queue
            "settings" -> Settings
            else -> Home
        }
    }
}
