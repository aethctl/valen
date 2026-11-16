/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.produceState
import app.valen.core.ArtistResult
import app.valen.ui.artist.BrowsePage
import app.valen.ui.artist.LocalBrowser
import app.valen.ui.components.ArtistBubble
import app.valen.ui.components.ItemArrival
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.arrive

import app.valen.ui.components.rise
import app.valen.ui.components.ScrollPage
import app.valen.ui.menu.MenuOrigin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.ProviderId
import app.valen.core.UnifiedSearch
import app.valen.ui.LocalAppScope
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.EmptyState
import app.valen.ui.components.FilterChips
import app.valen.ui.components.InlineError
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PageTitle
import app.valen.ui.components.PageTop
import app.valen.ui.components.QuietButton
import app.valen.ui.components.ResultRow
import app.valen.ui.components.SearchField
import app.valen.ui.components.SkeletonRows
import app.valen.ui.components.bleed
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DebounceMs = 320L

/**
 * What the search screen is holding. It lives in the app, not in the screen, so going to the
 * stage and coming back finds the same words and the same results instead of an empty field.
 */
@Stable
class SearchState(initialQuery: String = "") {
    var query by mutableStateOf(initialQuery)
    var enabled by mutableStateOf(ProviderId.entries.toSet())
    internal var answer by mutableStateOf<UnifiedSearch?>(null)

    /** The question [answer] is the answer to; a different one means it is stale. */
    internal var answered by mutableStateOf<Any?>(null)
}

/**
 * One query, every selected service, one list. A song that two services have appears once: the
 * copy from the service highest in Settings → Sources is the one that plays, and the row names the
 * others. A service that fails is named above the list; it never hides the rest.
 */
@Composable
fun SearchScreen(
    coordinator: PlaybackCoordinator,
    state: SearchState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = LocalAppScope.current
    val chrome = LocalChrome.current
    val settings by coordinator.settings.collectAsState()
    val snapshot by coordinator.nowPlaying.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val focus = remember { FocusRequester() }

    val trimmed = state.query.trim()
    val question = Triple(trimmed, state.enabled, settings.priority)
    LaunchedEffect(question) {
        when {
            trimmed.isEmpty() -> {
                state.answer = null
                state.answered = null
            }
            state.enabled.isEmpty() -> {
                state.answer = UnifiedSearch()
                state.answered = question
            }
            state.answered != question -> {
                state.answer = null
                delay(DebounceMs)
                state.answer = coordinator.unifiedSearch(trimmed, state.enabled, settings.priority)
                state.answered = question
            }
        }
    }

    // A keyboard is already under the hands on a wide window; on a phone it would cover the page.
    if (!chrome.compact) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }

    // Artists by that name on every service, asked beside the songs (a page of theirs is a tap away).
    val browser = LocalBrowser.current
    val artistsFound by produceState(emptyList<ArtistResult>(), trimmed) {
        value = emptyList()
        if (trimmed.isEmpty()) return@produceState
        delay(DebounceMs)
        value = runCatching { coordinator.artists.search(trimmed, 8) }.getOrDefault(emptyList())
    }

    val playingKey = snapshot?.track?.key
    val accent = ArtworkPalette.tones(snapshot?.track?.artworkUrl).bright
    val queued = remember(queue) { queue.mapTo(HashSet()) { it.key } }

    ScrollPage(key = "search:" + trimmed, modifier = modifier
            .fillMaxSize()) {
        ContentColumn {
            PageTop()
            PageTitle(title = "Search")
            Spacer(Modifier.height(Space.step4))
            SearchField(
                value = state.query,
                onValueChange = { state.query = it },
                onClear = { state.query = "" },
                focusRequester = focus,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.step2))
            // This device is a chip only where Valen reads its files (the desktop).
            val sources = remember(coordinator) { ProviderId.entries.filter { coordinator.provider(it) != null }.ifEmpty { ProviderId.services } }
            FilterChips(
                options = sources,
                selected = state.enabled,
                onToggle = { provider ->
                    // From "All", picking a service means "only this one"; after that, chips add and
                    // remove, and removing the last one is the same as asking for all of them again.
                    state.enabled = when {
                        state.enabled.containsAll(sources) -> setOf(provider)
                        provider in state.enabled -> (state.enabled - provider).ifEmpty { ProviderId.entries.toSet() }
                        else -> state.enabled + provider
                    }
                },
                label = { it.short },
                allLabel = "All",
                allSelected = state.enabled.containsAll(sources),
                onAll = { state.enabled = ProviderId.entries.toSet() },
            )
            Spacer(Modifier.height(Space.step6))

            val answer = state.answer
            when {
                trimmed.isEmpty() -> SearchIntro(
                    order = coordinator.priorityOrder(settings.priority),
                    onOpenSettings = onOpenSettings,
                )

                answer == null || state.answered != question -> SkeletonRows(count = 8)

                else -> {
                    val hits = answer.hits
                    if (artistsFound.isNotEmpty()) {
                        SectionHeader(title = "Artists", modifier = Modifier.arrive(0, ItemArrival.Swing))
                        Spacer(Modifier.height(Space.step2))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.step5),
                            contentPadding = PaddingValues(horizontal = chrome.gutter),
                            modifier = Modifier.fillMaxWidth().bleed(chrome.gutter),
                        ) {
                            itemsIndexed(artistsFound, key = { _, it -> it.key }) { i, artist ->
                                ArtistBubble(
                                    name = artist.name,
                                    artwork = artist.imageUrl,
                                    size = if (chrome.compact) 96.dp else 116.dp,
                                    onClick = { browser.open(BrowsePage.Artist(artist.name, artist.copies)) },
                                    modifier = Modifier.arrive(1 + i, ItemArrival.Slap),
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.step6))
                        SectionHeader(title = "Songs")
                    }
                    Text(
                        text = when (hits.size) {
                            0 -> "Nothing found"
                            1 -> "1 song"
                            else -> "${hits.size} songs"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = ValenColors.Text2,
                    )
                    answer.errors.forEach { (provider, reason) ->
                        InlineError(message = "${provider.label} did not answer: $reason")
                    }
                    Spacer(Modifier.height(Space.step2))
                    if (hits.isEmpty()) {
                        Spacer(Modifier.height(Space.step4))
                        EmptyState(
                            title = "No match for “$trimmed”",
                            body = "None of the services you selected has a song by that name. Check the spelling, or search more services.",
                        )
                    } else {
                        // Tapping any row queues the whole merged list, in the order shown.
                        val context = hits.map { it.track }
                        Column(modifier = Modifier.fillMaxWidth().bleed(Space.step2)) {
                            hits.forEachIndexed { i, hit ->
                                val key = hit.track.key
                                ResultRow(
                                    modifier = Modifier.rise(i),
                                    track = hit.track,
                                    alsoOn = hit.alsoOn,
                                    current = key == playingKey,
                                    playing = key == playingKey && snapshot?.isPlaying == true,
                                    queued = key in queued,
                                    accent = accent,
                                    onPlay = { scope.launch { coordinator.play(hit.track, context) } },
                                    onEnqueue = { coordinator.enqueue(hit.track) },
                                    menu = MenuOrigin.Song,
                                )
                            }
                        }
                    }
                }
            }
        }
        PageBottom()
    }
}

/**
 * Before a query: what one search does here, and the order the services are asked to play in.
 * The order is real state, so it is shown, with the way to change it beside it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchIntro(order: List<ProviderId>, onOpenSettings: () -> Unit) {
    EmptyState(
        title = "One search, every service",
        body = "A song that more than one service has appears once, and plays from the first of these that has it.",
    )
    Spacer(Modifier.height(Space.step4))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.step2),
        verticalArrangement = Arrangement.spacedBy(Space.step2),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        order.forEachIndexed { index, provider ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.step2),
                modifier = Modifier
                    .height(36.dp)
                    .clip(Radii.pillShape)
                    .background(ValenColors.Surface)
                    .padding(start = Space.step1, end = Space.step4),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(ValenColors.Fill),
                ) {
                    Text(text = "${index + 1}", style = timeStyle(), color = ValenColors.Text)
                }
                Text(text = provider.label, style = MaterialTheme.typography.labelMedium, color = ValenColors.Text)
            }
        }
        QuietButton(label = "Change order", onClick = onOpenSettings, small = true)
    }
}
