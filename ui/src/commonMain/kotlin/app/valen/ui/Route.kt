/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Where the app can be. A place is one of the screens the navigation lists (the profile is reached
 * from its picture at the foot of the panel, or beside the greeting on a phone); the stage, the queue
 * and importing a playlist open over a place. On a phone those three are sheets.
 */
enum class Route(val label: String) {
    Home("Home"),
    Search("Search"),
    Playlists("Playlists"),
    NowPlaying("Now Playing"),
    Queue("Queue"),
    Import("Import"),
    Settings("Settings"),
    Profile("Profile"),
    Mix("New mix"),
    About("About"),
    Sound("Sound"),
    /** The catalogue's pages: an artist, a release, a playlist of a service (see [app.valen.ui.artist.Browser]). */
    Artist("Artist"),
    ;

    val icon: ImageVector
        get() = when (this) {
            Home -> Icons.Rounded.Home
            Search -> Icons.Rounded.Search
            Playlists -> Icons.AutoMirrored.Rounded.PlaylistPlay
            NowPlaying -> Icons.Rounded.Album
            Queue -> Icons.AutoMirrored.Rounded.QueueMusic
            Import -> Icons.Rounded.AddLink
            Settings -> Icons.Rounded.Settings
            Profile -> Icons.Rounded.AccountCircle
            Mix -> Icons.Rounded.AutoAwesome
            About -> Icons.Rounded.Info
            Sound -> Icons.Rounded.GraphicEq
            Artist -> Icons.Rounded.Person
        }

    companion object {
        fun from(value: String?): Route = when (value?.trim()?.lowercase()) {
            null, "", "home" -> Home
            "search" -> Search
            "playlists", "playlist" -> Playlists
            "nowplaying", "now_playing", "now-playing", "player" -> NowPlaying
            "queue" -> Queue
            "import" -> Import
            "settings" -> Settings
            "profile", "me" -> Profile
            "mix", "new-mix" -> Mix
            "about", "credits" -> About
            "sound", "eq", "equalizer", "effects" -> Sound
            "artist" -> Artist
            else -> Home
        }
    }
}
