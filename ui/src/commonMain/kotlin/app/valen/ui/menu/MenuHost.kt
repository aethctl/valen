/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.menu

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistManager
import app.valen.ui.SystemBack
import app.valen.ui.components.GlassSurface
import app.valen.ui.components.LocalPageEntrance
import app.valen.ui.components.drawGlassLight
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.rememberPageEntrance
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.Squircle
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The glass menu's width on a wide window; a component's own measurement. */
private val PanelWidth = 344.dp

/** The drop a glass menu starts as: about the ⋯ button's own size. */
private val DropRadius = 22.dp

/** Below this much room on the anchor's side, the menu stops hanging off it and centres on it. */
private val PanelMinRoom = 240.dp

/** What a sheet (or a panel that opened by itself) dims behind it: black at 56%. */
private val SheetScrim = Color.Black.copy(alpha = 0.56f)

/** The phone sheet's handle, at rest. */
private val HandleWidth = 36.dp

/**
 * Draws whatever song menu [LocalSongMenu] has open: on a wide window a glass panel that grows out
 * of what opened it, on a phone a sheet that pours up from the bottom. It is chrome, so it is
 * placed beside the side panel and the dock, outside the wall the pages are painted on.
 *
 * [start] and [bottom] are what the side panel and the dock take on a wide window, so the panel
 * never lands on other glass.
 */
@Composable
fun SongMenuHost(
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    compact: Boolean,
    modifier: Modifier = Modifier,
    start: Dp = 0.dp,
    bottom: Dp = 0.dp,
    /** Opens the sound notebook. */
    onSound: () -> Unit = {},
) {
    val state = LocalSongMenu.current
    val request = state.request
    var shown by remember { mutableStateOf<MenuRequest?>(null) }
    if (request != null && request.id != shown?.id) shown = request
    val current = shown ?: return
    val open = request?.id == current.id
    var page by remember(current.id) { mutableStateOf(current.page) }
    // Back steps out of a page first, then closes.
    SystemBack(enabled = open) { if (page != MenuPage.Main) page = MenuPage.Main else state.close() }
    val gone = { if (state.request == null) shown = null }
    val body: @Composable (Boolean) -> Unit = { glass ->
        MenuBody(
            request = current,
            page = page,
            onPage = { page = it },
            coordinator = coordinator,
            playlists = playlists,
            glass = glass,
            onClose = state::close,
            onSound = { state.close(); onSound() },
        )
    }
    Box(modifier = modifier) {
        if (compact) {
            PouredSheet(key = current.id, open = open, onDismiss = state::close, onGone = gone) { body(false) }
        } else {
            PouredPanel(
                key = current.id,
                open = open,
                anchor = current.anchor,
                alignEnd = current.alignEnd,
                sweepKey = page,
                start = start,
                bottom = bottom,
                onDismiss = state::close,
                onGone = gone,
            ) { body(true) }
        }
    }
}

/**
 * A page of the menu, sliding in from the side it leads to while the panel springs to the new
 * page's height — the glass gulps it, with a band of light crossing it — and its rows rise in.
 */
@Composable
private fun MenuBody(
    request: MenuRequest,
    page: MenuPage,
    onPage: (MenuPage) -> Unit,
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    glass: Boolean,
    onClose: () -> Unit,
    onSound: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != MenuPage.Main
            if (reduced) {
                fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            } else {
                (slideInHorizontally(Motion.enter()) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(Motion.enter())) togetherWith
                    (slideOutHorizontally(Motion.exit()) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(Motion.exit()))
            } using SizeTransform(clip = true) { _, _ -> Motion.liquid() }
        },
        label = "menuPage",
    ) { shownPage ->
        val entrance = rememberPageEntrance(request.id to shownPage)
        CompositionLocalProvider(LocalPageEntrance provides entrance) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(Space.step4),
            ) {
                when (shownPage) {
                    MenuPage.Main -> MainPage(request, coordinator, playlists, glass, onPage, onClose, onSound)
                    MenuPage.AddToPlaylist -> AddToPlaylistPage(request.track, coordinator, playlists, glass) { onPage(MenuPage.Main) }
                    MenuPage.Sleep -> SleepPage(coordinator, glass) { onPage(MenuPage.Main) }
                }
            }
        }
    }
}

// ── Wide: a glass panel that grows out of what opened it ──

/**
 * The panel is born as a drop the size of the ⋯ button, where the button (or the right click) is,
 * and spreads into its shape on two springs — across quick, down slower — so it pours out rather
 * than scaling up; it pulls back into the drop as it closes. Only its reach is animated, never its
 * size, so the glass keeps seeing exactly what is behind it while it opens.
 */
@Composable
internal fun PouredPanel(
    key: Any,
    open: Boolean,
    /** What opened it, in the root's coordinates; null pours it from the middle of the window. */
    anchor: Rect?,
    alignEnd: Boolean,
    /** A change of it sends the band of light across the glass (a menu's page). */
    sweepKey: Any?,
    start: Dp,
    bottom: Dp,
    onDismiss: () -> Unit,
    onGone: () -> Unit,
    width: Dp = PanelWidth,
    closeLabel: String = "Close menu",
    /** Dims what is behind it as it pours, for a panel that opened by itself (the update sheet). */
    scrim: Boolean = false,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val across = remember(key) { Animatable(0f) }
    val down = remember(key) { Animatable(0f) }
    LaunchedEffect(key, open) {
        if (open) {
            if (reduced) {
                across.snapTo(1f)
                down.snapTo(1f)
            } else {
                launch { across.animateTo(1f, Motion.liquidLead()) }
                down.animateTo(1f, Motion.bloom())
            }
        } else {
            coroutineScope {
                launch { across.animateTo(0f, if (reduced) Motion.fade() else Motion.exit()) }
                launch { down.animateTo(0f, if (reduced) Motion.fade() else Motion.exit()) }
            }
            onGone()
        }
    }
    var host by remember { mutableStateOf(Offset.Zero) }
    val below = remember(key) { arrayOfNulls<Boolean>(1) }
    var origin by remember(key) { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(key) { runCatching { focus.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { host = it.positionInRoot() }) {
        if (scrim) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = min(across.value, down.value).coerceIn(0f, 1f) }
                    .background(SheetScrim),
            )
        }
        if (open) {
            // Anywhere else closes it; nothing under it is pressed by that click.
            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = closeLabel }
                    .clickable(interactionSource = rememberInteraction(), indication = null, onClick = onDismiss),
            )
        }
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Box(
                    modifier = Modifier
                        .focusRequester(focus)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                                onDismiss()
                                true
                            } else {
                                false
                            }
                        }
                        // The rim light of the drop is drawn on top, outside the clip that reveals it.
                        .drawWithContent {
                            drawContent()
                            val a = across.value
                            val d = down.value
                            if (a < 0.999f || d < 0.999f) {
                                // The light's sheen fills its whole scope: kept to the drop, like the glass.
                                val rr = revealRect(size, origin, a, d, DropRadius.toPx(), Radii.card.toPx())
                                clipPath(Path().apply { addRoundRect(rr) }) { drawGlassLight(Outline.Rounded(rr)) }
                            }
                        }
                        // A layer's own clip: it holds for the glass's blurred layers too, where a
                        // path clipped around drawContent let the full panel show through faintly.
                        .graphicsLayer {
                            val a = across.value
                            val d = down.value
                            if (a < 0.999f || d < 0.999f) {
                                clip = true
                                shape = RevealShape(revealRect(size, origin, a, d, DropRadius.toPx(), Radii.card.toPx()))
                            } else {
                                clip = false
                            }
                        },
                ) {
                    GlassSurface(shape = Radii.cardShape, corner = Radii.card, sweepKey = sweepKey) {
                        Box(
                            Modifier.graphicsLayer {
                                alpha = ((min(across.value, down.value) - 0.35f) / 0.65f).coerceIn(0f, 1f)
                            },
                        ) { content() }
                    }
                }
            },
        ) { measurables, constraints ->
            val margin = Space.step3.roundToPx()
            val gap = Space.step2.roundToPx()
            val minX = max(margin, start.roundToPx() + Space.step2.roundToPx())
            val maxX = constraints.maxWidth - margin
            val minY = margin
            val maxY = constraints.maxHeight - bottom.roundToPx() - margin
            val width = min(width.roundToPx(), maxX - minX).coerceAtLeast(0)
            val spot = anchor?.translate(-host)
                ?: Rect(Offset(constraints.maxWidth / 2f, constraints.maxHeight / 2f), Size(1f, 1f))
            // Which side it hangs on is decided once, so a page change never flips it over.
            val hang = below[0] ?: (spot.center.y < (minY + maxY) / 2f).also { below[0] = it }
            val room = if (hang) maxY - (spot.bottom.toInt() + gap) else (spot.top.toInt() - gap) - minY
            // Nothing opened it (the update sheet): it stands in the middle, as tall as it needs.
            val hanging = anchor != null && room >= PanelMinRoom.roundToPx()
            val tallest = (if (hanging) room else maxY - minY).coerceAtLeast(0)
            val panel = measurables.first().measure(Constraints(minWidth = width, maxWidth = width, maxHeight = tallest))
            val x = (if (anchor == null) (minX + maxX - width) / 2 else if (alignEnd) spot.right.toInt() - width else spot.left.toInt())
                .coerceIn(minX, max(minX, maxX - width))
            val y = when {
                hanging && hang -> spot.bottom.toInt() + gap
                hanging -> spot.top.toInt() - gap - panel.height
                anchor == null -> ((minY + maxY) / 2 - panel.height / 2).coerceIn(minY, max(minY, maxY - panel.height))
                else -> (spot.center.y.toInt() - panel.height / 2).coerceIn(minY, max(minY, maxY - panel.height))
            }
            origin = Offset(
                (spot.center.x - x).coerceIn(0f, width.toFloat()),
                (spot.center.y - y).coerceIn(0f, panel.height.toFloat()),
            )
            layout(constraints.maxWidth, constraints.maxHeight) { panel.place(x, y) }
        }
    }
}

// ── Phone: a sheet poured up from the bottom ──

/**
 * The sheet comes up on the bloom spring (a touch past its rest, then back) over a scrim, and can
 * be pulled down to close. Its handle is liquid too: it stretches with the sheet's speed and
 * gathers itself when the sheet is still. It is opaque — on a phone the stage is a sheet of its
 * own, outside the wall that glass sees through.
 */
@Composable
internal fun PouredSheet(
    key: Any,
    open: Boolean,
    onDismiss: () -> Unit,
    onGone: () -> Unit,
    closeLabel: String = "Close menu",
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key, open) {
        if (open) {
            if (reduced) progress.snapTo(1f) else progress.animateTo(1f, Motion.bloom())
        } else {
            progress.animateTo(0f, tween(if (reduced) Motion.ReducedMs else Motion.SheetExitMs, easing = Motion.Exit))
            onGone()
        }
    }
    var sheetHeight by remember { mutableStateOf(1f) }
    val drag = rememberDraggableState { delta ->
        scope.launch { progress.snapTo((progress.value - delta / sheetHeight).coerceIn(0f, 1f)) }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(SheetScrim)
                .then(
                    if (open) {
                        Modifier
                            .semantics { contentDescription = closeLabel }
                            .clickable(interactionSource = rememberInteraction(), indication = null, onClick = onDismiss)
                    } else {
                        Modifier
                    },
                ),
        )
        BoxWithConstraints(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val tallest = maxHeight * 0.9f
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = tallest)
                    .onGloballyPositioned { sheetHeight = it.size.height.toFloat().coerceAtLeast(1f) }
                    .graphicsLayer { translationY = (1f - progress.value) * size.height }
                    .clip(TopSquircle(Radii.artwork))
                    .background(ValenColors.Surface)
                    .draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        enabled = open,
                        onDragStopped = { velocity ->
                            // A flick down, or a pull past a quarter, closes it; anything less settles back.
                            if (velocity > 1200f || progress.value < 0.75f) onDismiss() else progress.animateTo(1f, Motion.liquid())
                        },
                    ),
            ) {
                Handle(speed = { abs(progress.velocity) })
                content()
                Spacer(Modifier.height(Space.step2))
            }
        }
    }
}

/** The sheet's grip: a pill that stretches with the sheet's speed and gathers back when it stops. */
@Composable
private fun Handle(speed: () -> Float) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(top = Space.step3, bottom = Space.step1),
    ) {
        Box(
            Modifier
                .width(HandleWidth)
                .height(5.dp)
                .graphicsLayer { scaleX = 1f + (speed() * 0.1f).coerceAtMost(0.8f) }
                .clip(Radii.pillShape)
                .background(ValenColors.Text3),
        )
    }
}

/**
 * How much of the panel shows while it pours out of the drop at [origin]: the drop's circle,
 * spread [across] and [down] (0 to 1, a spring may overshoot) toward the panel's own edges, its
 * corners easing from the drop's round to the panel's.
 */
private fun revealRect(size: Size, origin: Offset, across: Float, down: Float, drop: Float, corner: Float): RoundRect =
    RoundRect(
        left = lerp(origin.x - drop, 0f, across),
        top = lerp(origin.y - drop, 0f, down),
        right = lerp(origin.x + drop, size.width, across),
        bottom = lerp(origin.y + drop, size.height, down),
        cornerRadius = CornerRadius(lerp(drop, corner, min(across, down).coerceIn(0f, 1f))),
    )

/** One frame of the reveal, as a shape a layer can clip to. */
private class RevealShape(private val rect: RoundRect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Rounded(rect)
}

/** The side panel's superellipse on the top corners only; the bottom runs off the screen. */
private class TopSquircle(private val reach: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { reach.toPx() }
        return Squircle(reach).createOutline(Size(size.width, size.height + r * 2f), layoutDirection, density)
    }
}
