/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import app.valen.ui.artist.BrowsePage
import app.valen.ui.artist.LocalBrowser
import app.valen.ui.components.ArtistBubble

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.SmartSource
import app.valen.core.SmartSpec
import app.valen.core.Track
import app.valen.ui.LocalAppScope
import app.valen.ui.components.Artwork
import app.valen.ui.components.ItemArrival
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.ShelfCard
import app.valen.ui.components.Tag
import app.valen.ui.components.arrive
import app.valen.ui.components.bleed
import app.valen.ui.components.hoverLift
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.tap
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.launch

/**
 * What Home shows from the taste, kept above the screens so leaving Home and coming back does not
 * search again. Made again when what it was made from changes, after [RemakeAfterPlays] more songs
 * were played, or after [StaleMs].
 */
@Stable
class HomeFeed {
    var forYou by mutableStateOf<List<Track>>(emptyList())
    internal var madeFrom: Any? = null
    internal var madeAt = 0L
    internal var madeAtPlays = 0
    var loading by mutableStateOf(false)
}

val LocalHomeFeed = staticCompositionLocalOf { HomeFeed() }

private const val StaleMs = 30 * 60_000L

/** Songs played after which "For you" is made again, to follow what is being played now. */
private const val RemakeAfterPlays = 5

/**
 * Home's part that learns: songs picked for this person, the artists they love most (each a
 * tap away from a mix of that artist), and what they keep coming back to. Each section is left
 * out until there is something in it.
 */
@Composable
fun TasteSections(coordinator: PlaybackCoordinator, startIndex: Int) {
    val scope = LocalAppScope.current
    val chrome = LocalChrome.current
    val feed = LocalHomeFeed.current
    val browser = LocalBrowser.current
    val settings by coordinator.settings.collectAsState()
    val log by coordinator.stats.log.collectAsState()
    val taste = remember(log.plays, log.songs.size, log.skips, settings.favouriteArtists, settings.favouriteSongs) { coordinator.taste() }
    val seed = settings.favouriteArtists to settings.favouriteSongs.map { it.key }

    // Made again every few songs (and when the favourites change), so it follows what is played now.
    LaunchedEffect(seed, log.plays / RemakeAfterPlays) {
        val now = System.currentTimeMillis()
        val fresh = now - feed.madeAt < StaleMs && log.plays - feed.madeAtPlays < RemakeAfterPlays
        if (feed.madeFrom == seed && fresh && feed.forYou.isNotEmpty()) return@LaunchedEffect
        if (taste.topArtists(1).isEmpty()) return@LaunchedEffect
        feed.loading = true
        val made = runCatching {
            coordinator.mixer().build(
                SmartSpec(sources = setOf(SmartSource.Favourites, SmartSource.MostPlayed), size = 20, discovery = 0.65f),
                taste,
            )
        }.getOrDefault(emptyList())
        feed.loading = false
        if (made.isNotEmpty()) {
            feed.forYou = made
            feed.madeFrom = seed
            feed.madeAt = now
            feed.madeAtPlays = log.plays
        }
    }

    val card = if (chrome.compact) 140.dp else 168.dp
    val forYou = feed.forYou
    if (forYou.isNotEmpty()) {
        SectionHeader(title = "For you", modifier = Modifier.arrive(startIndex, ItemArrival.Swing))
        Spacer(Modifier.height(Space.step2))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Space.step4),
            contentPadding = PaddingValues(horizontal = chrome.gutter),
            modifier = Modifier.fillMaxWidth().bleed(chrome.gutter),
        ) {
            itemsIndexed(forYou, key = { _, it -> "fy-" + it.key }) { i, track ->
                ShelfCard(
                    track = track,
                    size = card,
                    onClick = { scope.launch { coordinator.play(track, forYou, from = null) } },
                    modifier = Modifier.arrive(startIndex + 1 + i, ItemArrival.Slap),
                )
            }
        }
        Spacer(Modifier.height(Space.step8))
    }

    val artists = taste.topArtists(12)
    if (artists.isNotEmpty()) {
        SectionHeader(title = "Your artists", modifier = Modifier.arrive(startIndex + 2, ItemArrival.Swing))
        Spacer(Modifier.height(Space.step2))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Space.step5),
            contentPadding = PaddingValues(horizontal = chrome.gutter),
            modifier = Modifier.fillMaxWidth().bleed(chrome.gutter),
        ) {
            itemsIndexed(artists, key = { _, it -> "ar-$it" }) { i, artist ->
                val art = log.artworkFor(artist)
                    ?: settings.favouriteSongs.firstOrNull { it.artist.equals(artist, true) }?.artworkUrl
                    ?: forYou.firstOrNull { it.artist.contains(artist, true) }?.artworkUrl
                ArtistBubble(
                    name = artist,
                    artwork = art,
                    size = if (chrome.compact) 104.dp else 128.dp,
                    // Their page, from every service (the mix of them is a button there).
                    onClick = { browser.open(BrowsePage.Artist(artist)) },
                    modifier = Modifier.arrive(startIndex + 3 + i, ItemArrival.Slap),
                )
            }
        }
        Spacer(Modifier.height(Space.step8))
    }

    val repeat = taste.onRepeat(12)
    if (repeat.size >= 3) {
        SectionHeader(title = "On repeat", modifier = Modifier.arrive(startIndex + 4, ItemArrival.Swing))
        Spacer(Modifier.height(Space.step2))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Space.step4),
            contentPadding = PaddingValues(horizontal = chrome.gutter),
            modifier = Modifier.fillMaxWidth().bleed(chrome.gutter),
        ) {
            itemsIndexed(repeat, key = { _, it -> "or-" + it.key }) { i, track ->
                ShelfCard(
                    track = track,
                    size = card,
                    onClick = { scope.launch { coordinator.play(track, repeat, from = null) } },
                    modifier = Modifier.arrive(startIndex + 5 + i, ItemArrival.Slap),
                )
            }
        }
        Spacer(Modifier.height(Space.step8))
    }
}

