/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.Track
import app.valen.ui.LocalAppScope
import app.valen.ui.Route
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.artwork.ArtworkTones
import app.valen.ui.components.AmbientWash
import app.valen.ui.components.Artwork
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.IconTap
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PageTop
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.QuickTile
import app.valen.ui.components.QuietButton
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.ServiceTile
import app.valen.ui.components.ShelfCard
import app.valen.ui.components.bleed
import app.valen.ui.components.tap
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import app.valen.ui.util.currentHour
import app.valen.ui.util.greetingFor
import kotlinx.coroutines.launch

/**
 * Home answers, in order: what is playing, what comes next, what was played, and which services
 * are reachable. A question with no answer yet is simply not asked: an empty queue shows no
 * "Up next", a first start shows no "Recently played".
 */
@Composable
fun HomeScreen(
    coordinator: PlaybackCoordinator,
    onRoute: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = LocalAppScope.current
    val chrome = LocalChrome.current
    val snapshot by coordinator.snapshot.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val index by coordinator.index.collectAsState()
    val sessions by coordinator.sessions.collectAsState()

    val current = snapshot?.track ?: queue.getOrNull(index)
    val upNext = remember(queue, index) {
        if (index < 0) emptyList() else queue.drop(index + 1).take(6)
    }
    val history = remember(queue, index) {
        if (index <= 0) emptyList() else queue.take(index).reversed().distinctBy { it.key }.take(12)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (current != null) {
            NowHero(
                track = current,
                isPlaying = snapshot?.isPlaying == true,
                onToggle = { scope.launch { coordinator.toggle() } },
                onOpen = { onRoute(Route.NowPlaying) },
                onSettings = { onRoute(Route.Settings) },
            )
        } else {
            Welcome(
                connected = ProviderId.entries.count { sessions[it] is SessionState.Connected },
                onSearch = { onRoute(Route.Search) },
                onSettings = { onRoute(Route.Settings) },
            )
        }

        ContentColumn {
            if (upNext.isNotEmpty()) {
                SectionHeader(title = "Up next", onOpen = { onRoute(Route.Queue) })
                Spacer(Modifier.height(Space.step2))
                QuickGrid(
                    tracks = upNext,
                    onPlay = { track -> scope.launch { coordinator.play(track, queue) } },
                )
                Spacer(Modifier.height(Space.step8))
            }

            if (history.isNotEmpty()) {
                SectionHeader(title = "Recently played", onOpen = { onRoute(Route.Queue) })
                Spacer(Modifier.height(Space.step2))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.step4),
                    contentPadding = PaddingValues(horizontal = chrome.gutter),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bleed(chrome.gutter),
                ) {
                    items(history, key = { it.key }) { track ->
                        ShelfCard(
                            track = track,
                            size = if (chrome.compact) 140.dp else 168.dp,
                            onClick = { scope.launch { coordinator.play(track, queue) } },
                        )
                    }
                }
                Spacer(Modifier.height(Space.step8))
            }

            SectionHeader(title = "Services")
            Spacer(Modifier.height(Space.step2))
            ServiceTiles(
                sessions = sessions,
                onConnect = { id -> scope.launch { coordinator.connect(id) } },
            )
        }
        PageBottom()
    }
}

/**
 * What is playing, as the top of the page. On a wide window the cover's colours wash the band and
 * the cover itself sits at its right end; on a phone the cover is the band, edge to edge, fading
 * into the page. Either way the words sit on the page's left edge, above everything that follows.
 */
@Composable
private fun NowHero(
    track: Track,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
) {
    val chrome = LocalChrome.current
    val tones = ArtworkPalette.tones(track.artworkUrl)
    if (chrome.compact) {
        Box(
            contentAlignment = Alignment.BottomStart,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .aspectRatio(1f, matchHeightConstraintsFirst = false),
        ) {
            Artwork(
                url = track.artworkUrl,
                shape = RectangleShape,
                measure = true,
                iconSize = 48.dp,
                modifier = Modifier.fillMaxSize(),
            )
            Spacer(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to ValenColors.Background.copy(alpha = 0.30f),
                            0.30f to Color.Transparent,
                            0.55f to ValenColors.Background.copy(alpha = 0.45f),
                            0.85f to ValenColors.Background.copy(alpha = 0.92f),
                            1f to ValenColors.Background,
                        ),
                    ),
            )
            IconTap(
                icon = Icons.Rounded.Settings,
                label = "Settings",
                onClick = onSettings,
                tint = ValenColors.Text,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Space.step2),
            )
            Column(modifier = Modifier.padding(horizontal = chrome.gutter)) {
                HeroWords(track = track, isPlaying = isPlaying, tones = tones, wide = false)
                Spacer(Modifier.height(Space.step4))
                HeroActions(isPlaying = isPlaying, onToggle = onToggle, onOpen = onOpen)
                Spacer(Modifier.height(Space.step2))
            }
        }
        Spacer(Modifier.height(Space.step6))
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // The cover grows a step with the window: at 248dp it reads as a stamp in the corner of
            // a very wide band. It stays the anchor, the words stay the weight beside it.
            val cover = when {
                maxWidth >= 2000.dp -> HeroCoverWidest
                maxWidth >= 1600.dp -> HeroCoverWide
                else -> HeroCover
            }
            AmbientWash(tones = tones, modifier = Modifier.matchParentSize())
            ContentColumn {
                Spacer(Modifier.height(Space.step16))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = Space.step8),
                    ) {
                        HeroWords(track = track, isPlaying = isPlaying, tones = tones, wide = true)
                        Spacer(Modifier.height(Space.step6))
                        HeroActions(isPlaying = isPlaying, onToggle = onToggle, onOpen = onOpen)
                    }
                    Artwork(
                        url = track.artworkUrl,
                        shape = Radii.artworkShape,
                        contentDescription = null,
                        measure = true,
                        iconSize = 48.dp,
                        modifier = Modifier
                            .size(cover)
                            .tap(onClick = onOpen, shape = Radii.artworkShape),
                    )
                }
                Spacer(Modifier.height(Space.step12))
            }
        }
        Spacer(Modifier.height(Space.step2))
    }
}

private val HeroCover = 248.dp
private val HeroCoverWide = 312.dp
private val HeroCoverWidest = 360.dp

/** "Playing from Spotify": the lead-in dimmed, the service in full white, then the song. */
@Composable
private fun HeroWords(track: Track, isPlaying: Boolean, tones: ArtworkTones, wide: Boolean) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = ValenColors.Text2)) {
                append(if (isPlaying) "Playing from " else "Paused on ")
            }
            append(track.provider.label)
        },
        style = MaterialTheme.typography.titleMedium,
        color = ValenColors.Text,
        maxLines = 1,
    )
    Spacer(Modifier.height(if (wide) Space.step2 else Space.step1))
    Text(
        text = track.title,
        style = if (wide) TrackType.display else TrackType.title,
        color = ValenColors.Text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(if (wide) Space.step2 else Space.step1))
    Text(
        text = track.artist,
        style = MaterialTheme.typography.titleLarge,
        color = ValenColors.Text2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HeroActions(isPlaying: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
        PrimaryButton(
            label = if (isPlaying) "Pause" else "Play",
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            onClick = onToggle,
        )
        QuietButton(label = "Open player", onClick = onOpen)
    }
}

/**
 * Nothing has been played: the app says hello and offers the one thing to do next. The search
 * prompt is a door to the search screen, not a second search field.
 */
@Composable
private fun Welcome(connected: Int, onSearch: () -> Unit, onSettings: () -> Unit) {
    val chrome = LocalChrome.current
    Box(modifier = Modifier.fillMaxWidth()) {
        AmbientWash(tones = ArtworkPalette.Neutral, strength = 0.55f, modifier = Modifier.matchParentSize())
        ContentColumn {
            if (chrome.compact) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = Space.step2)) {
                    Spacer(Modifier.weight(1f))
                    IconTap(icon = Icons.Rounded.Settings, label = "Settings", onClick = onSettings, tint = ValenColors.Text)
                }
                Spacer(Modifier.height(Space.step12))
            } else {
                Spacer(Modifier.height(Space.step24))
            }
            Text(
                text = greetingFor(remember { currentHour() }),
                style = if (chrome.compact) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                color = ValenColors.Text,
            )
            Spacer(Modifier.height(Space.step3))
            Text(
                text = when (connected) {
                    0 -> "No service is signed in yet. YouTube Music and SoundCloud play without an account, so you can start with a search."
                    1 -> "One of your three services is signed in. One search covers all three."
                    2 -> "Two of your three services are signed in. One search covers all three."
                    else -> "All three services are signed in. One search covers them all."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = ValenColors.Text2,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            Spacer(Modifier.height(Space.step6))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .tap(onClick = onSearch, shape = Radii.pillShape, rest = ValenColors.Fill)
                    .padding(horizontal = Space.step5),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = ValenColors.Text,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Space.step3))
                Text(
                    text = "Search every service",
                    style = MaterialTheme.typography.bodyLarge,
                    color = ValenColors.Text2,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(if (chrome.compact) Space.step10 else Space.step16))
        }
    }
}

/**
 * What comes next, as a grid of quick tiles: one column on a phone, up to six across a wide window.
 * A tile carries a cover, a title and an artist, so it needs room before it earns a column.
 */
@Composable
private fun QuickGrid(tracks: List<Track>, onPlay: (Track) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 2100.dp -> 6
            maxWidth >= 1500.dp -> 5
            maxWidth >= 1150.dp -> 4
            maxWidth >= 860.dp -> 3
            maxWidth >= 560.dp -> 2
            else -> 1
        }
        // Song titles are long; two tiles across a phone would show four letters of each.
        val shown = if (columns == 1) tracks.take(4) else tracks
        Column(verticalArrangement = Arrangement.spacedBy(Space.step2)) {
            shown.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
                    row.forEach { track ->
                        QuickTile(track = track, onClick = { onPlay(track) }, modifier = Modifier.weight(1f))
                    }
                    // A short last row keeps its tiles the same width as the rows above it.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The three services: side by side when there is room for their buttons, stacked when not. */
@Composable
private fun ServiceTiles(sessions: Map<ProviderId, SessionState>, onConnect: (ProviderId) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 880.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
                ProviderId.entries.forEach { provider ->
                    ServiceTile(
                        provider = provider,
                        state = sessions[provider] ?: SessionState.SignedOut,
                        onConnect = { onConnect(provider) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.step2)) {
                ProviderId.entries.forEach { provider ->
                    ServiceTile(
                        provider = provider,
                        state = sessions[provider] ?: SessionState.SignedOut,
                        onConnect = { onConnect(provider) },
                    )
                }
            }
        }
    }
}
