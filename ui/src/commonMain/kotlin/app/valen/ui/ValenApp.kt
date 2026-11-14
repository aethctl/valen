/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.NOTICE_QUEUED
import app.valen.core.PlaybackCoordinator
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.components.BottomNav
import app.valen.ui.components.FullScreenSheet
import app.valen.ui.components.MiniPlayer
import app.valen.ui.components.NoticeToast
import app.valen.ui.components.PlayerBar
import app.valen.ui.components.Sidebar
import app.valen.ui.screens.HomeScreen
import app.valen.ui.screens.NowPlayingScreen
import app.valen.ui.screens.QueueScreen
import app.valen.ui.screens.SearchScreen
import app.valen.ui.screens.SearchState
import app.valen.ui.screens.SettingsScreen
import app.valen.ui.theme.Chrome
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.ValenTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The scope every screen sends its playback commands on. It is remembered once, at the root, which
 * never leaves the composition, so a command started from a screen that is then navigated away
 * from still completes. A scope remembered inside a screen would be cancelled with it, and one
 * used after it left throws inside the composition and freezes the window.
 */
val LocalAppScope = staticCompositionLocalOf<CoroutineScope> { error("ValenApp provides the app scope") }

/**
 * Where the app is. A place ([base]) is one of the screens the navigation lists; the stage and the
 * queue open over a place and close back to it. The same state serves every width: on a phone the
 * things in [over] are sheets, on a wide window the top one is simply the screen shown.
 *
 * It lives above the width switch, so resizing a window across a breakpoint keeps you where you were.
 */
@Stable
private class Nav(start: Route) {
    var base by mutableStateOf(if (start in Places) start else Route.Home)
        private set
    var over by mutableStateOf(if (start in Places) emptyList() else listOf(start))
        private set

    val top: Route get() = over.lastOrNull() ?: base

    fun open(route: Route) {
        if (route in Places) {
            base = route
            over = emptyList()
        } else if (top != route) {
            over = (over - route) + route
        }
    }

    fun close(route: Route) {
        over = over - route
    }

    companion object {
        val Places = setOf(Route.Home, Route.Search, Route.Settings)
    }
}

/**
 * The app. Responsive by width, not by platform: a floating side panel and player dock on wide
 * windows, an icon rail in the middle, and a floating tab bar with two sheets on a phone.
 */
@Composable
fun ValenApp(
    coordinator: PlaybackCoordinator,
    startRoute: String? = null,
    /** Text the search field starts with; the development `--query` flag. */
    startQuery: String? = null,
) {
    ValenTheme {
        val scope = rememberCoroutineScope()
        val nav = remember(startRoute) { Nav(Route.from(startRoute)) }
        val search = remember(startQuery) { SearchState(startQuery.orEmpty()) }
        CompositionLocalProvider(LocalAppScope provides scope) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ValenColors.Background),
            ) {
                when {
                    maxWidth >= Layout.wideFrom -> WideApp(coordinator, nav, search, rail = false)
                    maxWidth >= Layout.railFrom -> WideApp(coordinator, nav, search, rail = true)
                    else -> PhoneApp(coordinator, nav, search)
                }
            }
        }
    }
}

@Composable
private fun WideApp(
    coordinator: PlaybackCoordinator,
    nav: Nav,
    search: SearchState,
    rail: Boolean,
) {
    val scope = LocalAppScope.current
    val snapshot by coordinator.snapshot.collectAsState()
    val settings by coordinator.settings.collectAsState()
    val sessions by coordinator.sessions.collectAsState()
    val notice by coordinator.notice.collectAsState()
    val track = snapshot?.track
    val route = nav.top

    // The stage carries its own transport; a dock under it would only repeat it.
    val docked = track != null && route != Route.NowPlaying
    val gutter = if (rail) Layout.railGutter else Layout.desktopGutter
    val chrome = Chrome(
        start = Layout.chromeInset + (if (rail) Layout.railWidth else Layout.sidebarWidth),
        bottom = if (docked) Layout.dockHeight + Space.step4 else 0.dp,
        gutter = gutter,
        compact = false,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalChrome provides chrome) {
            Pages(route) { shown ->
                Screen(
                    route = shown,
                    coordinator = coordinator,
                    search = search,
                    onRoute = nav::open,
                    // The stage closes back to where you were. The queue has no close here: the
                    // side panel is right beside it.
                    onClose = if (shown == Route.NowPlaying) ({ nav.close(shown) }) else null,
                    onBack = null,
                )
            }
        }

        Lowlight(visible = docked, height = Layout.dockHeight + Space.step24)

        Sidebar(
            current = route,
            onNavigate = nav::open,
            sessions = sessions,
            condensed = rail,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = Layout.chromeInset, top = Layout.chromeInset, bottom = Layout.chromeInset),
        )

        Rising(
            visible = docked,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = chrome.start + gutter, end = gutter, bottom = Space.step4),
        ) {
            val shown = rememberLast(track) ?: return@Rising
            PlayerBar(
                track = shown,
                isPlaying = snapshot?.isPlaying == true,
                isBuffering = snapshot?.isBuffering == true,
                canSkipPrevious = snapshot?.canSkip != false,
                canSkipNext = snapshot?.canSkip != false,
                positionMs = snapshot?.positionMs ?: 0L,
                durationMs = snapshot?.durationMs ?: shown.durationMs,
                volume = settings.volume,
                tones = ArtworkPalette.tones(shown.artworkUrl),
                onOpen = { nav.open(Route.NowPlaying) },
                onPrevious = { scope.launch { coordinator.previous() } },
                onToggle = { scope.launch { coordinator.toggle() } },
                onNext = { scope.launch { coordinator.next() } },
                onSeek = { position -> scope.launch { coordinator.seek(position) } },
                onVolume = { value -> scope.launch { coordinator.setVolume(value) } },
                onQueue = { nav.open(Route.Queue) },
            )
        }

        Notice(
            message = notice,
            onDismiss = { coordinator.clearNotice() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = chrome.start + gutter, end = gutter, top = Space.step4),
        )
    }
}

@Composable
private fun PhoneApp(
    coordinator: PlaybackCoordinator,
    nav: Nav,
    search: SearchState,
) {
    val scope = LocalAppScope.current
    val snapshot by coordinator.snapshot.collectAsState()
    val notice by coordinator.notice.collectAsState()
    val track = snapshot?.track

    val bars = Layout.bottomNavHeight + Layout.chromeInset +
        (if (track != null) Layout.miniPlayerHeight + Space.step2 else 0.dp)
    val page = Chrome(start = 0.dp, bottom = bars, gutter = Layout.phoneGutter, compact = true)
    val sheet = Chrome(start = 0.dp, bottom = 0.dp, gutter = Layout.phoneGutter, compact = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        CompositionLocalProvider(LocalChrome provides page) {
            Pages(nav.base) { shown ->
                Screen(
                    route = shown,
                    coordinator = coordinator,
                    search = search,
                    onRoute = nav::open,
                    onClose = null,
                    onBack = { nav.open(Route.Home) },
                )
            }
        }

        Lowlight(visible = true, height = bars + Space.step16)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.step2),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = Layout.chromeInset)
                .padding(bottom = Layout.chromeInset),
        ) {
            Rising(visible = track != null) {
                val shown = rememberLast(track) ?: return@Rising
                MiniPlayer(
                    track = shown,
                    isPlaying = snapshot?.isPlaying == true,
                    canSkipNext = snapshot?.canSkip != false,
                    positionMs = snapshot?.positionMs ?: 0L,
                    durationMs = snapshot?.durationMs ?: shown.durationMs,
                    tones = ArtworkPalette.tones(shown.artworkUrl),
                    onOpen = { nav.open(Route.NowPlaying) },
                    onToggle = { scope.launch { coordinator.toggle() } },
                    onNext = { scope.launch { coordinator.next() } },
                )
            }
            BottomNav(current = nav.top, onNavigate = nav::open)
        }

        Notice(
            message = notice,
            onDismiss = { coordinator.clearNotice() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = Layout.chromeInset, vertical = Space.step2),
        )

        CompositionLocalProvider(LocalChrome provides sheet) {
            FullScreenSheet(
                visible = Route.NowPlaying in nav.over,
                onDismiss = { nav.close(Route.NowPlaying) },
            ) {
                NowPlayingScreen(
                    coordinator = coordinator,
                    onOpenQueue = { nav.open(Route.Queue) },
                    onBrowse = { nav.open(Route.Search) },
                    onClose = { nav.close(Route.NowPlaying) },
                )
            }
            FullScreenSheet(
                visible = Route.Queue in nav.over,
                onDismiss = { nav.close(Route.Queue) },
            ) {
                QueueScreen(
                    coordinator = coordinator,
                    onBrowse = { nav.open(Route.Search) },
                    onClose = { nav.close(Route.Queue) },
                )
            }
        }
    }
}

/** One screen replacing another in the same place: a short cross-fade, never a slide. */
@Composable
private fun Pages(route: Route, content: @Composable (Route) -> Unit) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            if (reduced) {
                fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            } else {
                fadeIn(Motion.page()) togetherWith fadeOut(Motion.page())
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "pages",
    ) { shown ->
        content(shown)
    }
}

@Composable
private fun Screen(
    route: Route,
    coordinator: PlaybackCoordinator,
    search: SearchState,
    onRoute: (Route) -> Unit,
    onClose: (() -> Unit)?,
    onBack: (() -> Unit)?,
) {
    when (route) {
        Route.Home -> HomeScreen(coordinator = coordinator, onRoute = onRoute)
        Route.Search -> SearchScreen(
            coordinator = coordinator,
            state = search,
            onOpenSettings = { onRoute(Route.Settings) },
        )
        Route.NowPlaying -> NowPlayingScreen(
            coordinator = coordinator,
            onOpenQueue = { onRoute(Route.Queue) },
            onBrowse = { onRoute(Route.Search) },
            onClose = onClose,
        )
        Route.Queue -> QueueScreen(
            coordinator = coordinator,
            onBrowse = { onRoute(Route.Search) },
            onClose = onClose,
        )
        Route.Settings -> SettingsScreen(coordinator = coordinator, onBack = onBack)
    }
}

/**
 * The page dimming to black under floating chrome, so a list scrolling beneath the player fades
 * out instead of colliding with it.
 */
@Composable
private fun BoxScope.Lowlight(visible: Boolean, height: Dp) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.standard()),
        exit = fadeOut(Motion.exit()),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to ValenColors.Background.copy(alpha = 0.78f),
                        1f to ValenColors.Background.copy(alpha = 0.96f),
                    ),
                ),
        )
    }
}

/** Chrome that arrives from below when there is something to play, and leaves the same way. */
@Composable
private fun Rising(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(Motion.fade()) else slideInVertically(Motion.enter()) { it / 2 } + fadeIn(Motion.enter()),
        exit = if (reduced) fadeOut(Motion.fade()) else slideOutVertically(Motion.exit()) { it / 2 } + fadeOut(Motion.exit()),
        modifier = modifier,
    ) {
        content()
    }
}

@Composable
private fun Notice(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = message != null,
        enter = if (reduced) fadeIn(Motion.fade()) else slideInVertically(Motion.enter()) { -it } + fadeIn(Motion.enter()),
        exit = if (reduced) fadeOut(Motion.fade()) else slideOutVertically(Motion.exit()) { -it } + fadeOut(Motion.exit()),
        modifier = modifier,
    ) {
        val shown = rememberLast(message) ?: return@AnimatedVisibility
        NoticeToast(
            message = shown,
            confirmation = shown == NOTICE_QUEUED,
            onDismiss = onDismiss,
        )
    }
}

/** The last non-null [value]: what something keeps showing while it animates away. */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    if (value != null && value != last) last = value
    return value ?: last
}
