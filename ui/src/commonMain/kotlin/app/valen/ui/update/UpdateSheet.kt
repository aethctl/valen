/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.InstallDesktop
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.AppRelease
import app.valen.core.NotePiece
import app.valen.core.PlaybackCoordinator
import app.valen.core.UpdateRoute
import app.valen.core.UpdateState
import app.valen.core.Updates
import app.valen.core.releaseNotes
import app.valen.ui.SystemBack
import app.valen.ui.components.KineticText
import app.valen.ui.components.LocalPageEntrance
import app.valen.ui.components.QuietButton
import app.valen.ui.components.Tag
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.rememberPageEntrance
import app.valen.ui.components.rise
import app.valen.ui.menu.PouredPanel
import app.valen.ui.menu.PouredSheet
import app.valen.ui.menu.stepFill
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.delay

/**
 * Whether the update sheet is open. It opens by itself once a release is ready to act on (see
 * [UpdateSheetHost]); Settings → About opens it with [show].
 */
@Stable
class UpdateSheetState {
    var open by mutableStateOf(false)
        private set

    /** Each opening is its own: the pour, the tag and the title start again. */
    var key by mutableIntStateOf(0)
        private set

    /** The release it last opened by itself for in this run: once is enough until "Later" runs out. */
    internal var offeredCode = 0

    fun show() {
        key++
        open = true
    }

    fun close() {
        open = false
    }
}

val LocalUpdateSheet = staticCompositionLocalOf { UpdateSheetState() }

// A component's own measurements.

/** The glass panel's width on a wide window: room for a release's notes to read as lines. */
private val PanelWidth: Dp = 440.dp

/** The tag at the head of the sheet, written as it opens. */
private val TagWidth: Dp = 128.dp

/** A notes item's dash: the About page's pink dash. */
private val DashWidth: Dp = 12.dp

/** The file's tile: a row's cover. */
private val FileTile: Dp = 48.dp

/** The one action: a little taller than a button, since it is what the sheet is for. */
private val ActionHeight: Dp = 52.dp

/** How far the notes thin out at an edge they scroll past. */
private val EdgeFade: Dp = 24.dp

/** How long after a release becomes actionable the sheet waits, so it never lands on a page mid-pour. */
private const val OfferDelayMs = 1_200L

/**
 * The update sheet: a new Valen, what changed in it, the file it brings, and the one press that
 * moves it on. On a wide window it is glass poured out of a drop in the middle of the window on
 * the song menu's springs, over a scrim (it came by itself); on a phone it is the song menu's
 * opaque sheet, sprung up from the bottom and pulled down to close. Closing it any way is "Later":
 * it stays away a day for that release (a newer one shows at once), and Settings → About still has
 * it.
 *
 * It is chrome, so the shell places it beside the song menu, outside the wall; [start] and
 * [bottom] are what the side panel and the dock take.
 */
@Composable
fun UpdateSheetHost(
    coordinator: PlaybackCoordinator,
    compact: Boolean,
    modifier: Modifier = Modifier,
    start: Dp = 0.dp,
    bottom: Dp = 0.dp,
) {
    val updates = coordinator.updates ?: return
    val sheet = LocalUpdateSheet.current
    val state by updates.state.collectAsState()
    val settings by coordinator.settings.collectAsState()
    val release = releaseOf(state)

    // By itself: once per release in a run, when there is something to press, unless "Later" holds.
    val offerable = state is UpdateState.Available || state is UpdateState.Ready || state is UpdateState.Installed
    LaunchedEffect(release?.code, offerable) {
        val r = release ?: return@LaunchedEffect
        if (!offerable || sheet.offeredCode == r.code) return@LaunchedEffect
        if (!settings.updates.mayShow(r, System.currentTimeMillis())) return@LaunchedEffect
        delay(OfferDelayMs)
        sheet.offeredCode = r.code
        sheet.show()
    }

    // The release stays on show while the sheet closes, even if the state moves on underneath.
    var shown by remember { mutableStateOf<AppRelease?>(null) }
    if (release != null && sheet.open) shown = release
    val showing = shown ?: return
    val later = {
        coordinator.setUpdates { it.later(showing, System.currentTimeMillis()) }
        sheet.close()
    }
    val gone = { if (!sheet.open) shown = null }
    SystemBack(enabled = sheet.open) { later() }

    Box(modifier = modifier) {
        if (compact) {
            PouredSheet(key = sheet.key, open = sheet.open, onDismiss = later, onGone = gone, closeLabel = "Later") {
                UpdateBody(updates, state, showing, settings.profileName, glass = false, opening = sheet.key, onLater = later, onClose = sheet::close)
            }
        } else {
            PouredPanel(
                key = sheet.key,
                open = sheet.open,
                anchor = null,
                alignEnd = false,
                // A new state sends a band of light across the glass; a download's ticks do not.
                sweepKey = state::class,
                start = start,
                bottom = bottom,
                onDismiss = later,
                onGone = gone,
                width = PanelWidth,
                closeLabel = "Later",
                scrim = true,
            ) {
                UpdateBody(updates, state, showing, settings.profileName, glass = true, opening = sheet.key, onLater = later, onClose = sheet::close)
            }
        }
    }
}

/**
 * The sheet's contents: the head, what changed and the file scroll (thinning out at an edge they
 * pass), and the actions stay put under them. Everything rises in one after another as it opens.
 */
@Composable
private fun UpdateBody(
    updates: Updates,
    state: UpdateState,
    release: AppRelease,
    name: String,
    glass: Boolean,
    opening: Int,
    onLater: () -> Unit,
    onClose: () -> Unit,
) {
    val entrance = rememberPageEntrance(opening)
    val fill = stepFill(glass)
    val scroll = rememberScrollState()
    CompositionLocalProvider(LocalPageEntrance provides entrance) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .edgeFade(scroll)
                    .verticalScroll(scroll)
                    .padding(start = Space.step5, end = Space.step5, top = if (glass) Space.step6 else Space.step3, bottom = Space.step5),
            ) {
                Head(updates, release, name, opening)

                Spacer(Modifier.height(Space.step8))
                SectionTitle("What changed", Modifier.rise(3))
                Spacer(Modifier.height(Space.step3))
                Notes(release, fill, Modifier.rise(4))

                if (updates.route != UpdateRoute.Tell) {
                    Spacer(Modifier.height(Space.step8))
                    SectionTitle("To be installed", Modifier.rise(5))
                    Spacer(Modifier.height(Space.step3))
                    FileRow(updates, release, state, fill, Modifier.rise(6))
                } else {
                    updates.elsewhere?.let { line ->
                        Spacer(Modifier.height(Space.step6))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2, modifier = Modifier.rise(5))
                    }
                }
            }
            Actions(
                updates = updates,
                state = state,
                release = release,
                onLater = onLater,
                onClose = onClose,
                modifier = Modifier
                    .padding(start = Space.step5, end = Space.step5, bottom = if (glass) Space.step5 else Space.step2)
                    .rise(7),
            )
        }
    }
}

/** The tag, written as the sheet opens; the title rising letter by letter; the version in two tones. */
@Composable
private fun Head(updates: Updates, release: AppRelease, name: String, opening: Int) {
    Tag(
        ink = ValenColors.Primary,
        mist = ValenColors.Primary,
        replay = opening,
        modifier = Modifier.width(TagWidth).rise(0),
    )
    Spacer(Modifier.height(Space.step5))
    KineticText(
        text = if (name.isBlank()) "A new Valen" else "A new Valen, ${name.trim()}",
        style = MaterialTheme.typography.headlineLarge,
        color = ValenColors.Text,
        maxLines = 2,
        modifier = Modifier.rise(1),
    )
    Spacer(Modifier.height(Space.step2))
    // A lead-in in two tones: the new version in full white, what it is and what you have in 62%.
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = ValenColors.Text, fontWeight = FontWeight.SemiBold)) { append(release.version) }
            withStyle(SpanStyle(color = ValenColors.Text2)) {
                if (release.test) append(" · test build")
                append(" · you have ${updates.current}")
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.rise(2),
    )
}

/** A section's name, in the app's voice. */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = ValenColors.Text, modifier = modifier)
}

/** What changed, from the release's own notes: its headings, and its items behind a pink dash. */
@Composable
private fun Notes(release: AppRelease, fill: Color, modifier: Modifier = Modifier) {
    val pieces = remember(release.notes) { releaseNotes(release.notes) }
        // GitHub's own "What's Changed" heading says what the section's title already says.
        .let { all -> if ((all.firstOrNull() as? NotePiece.Heading)?.text.equals("What's Changed", ignoreCase = true)) all.drop(1) else all }
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.step3),
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.rowShape)
            .background(fill)
            .padding(horizontal = Space.step4, vertical = Space.step4),
    ) {
        if (pieces.isEmpty()) {
            Text("This release came with no notes.", style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2)
        }
        pieces.forEach { piece ->
            when (piece) {
                is NotePiece.Heading -> Text(
                    piece.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = ValenColors.Text,
                    modifier = Modifier.padding(top = Space.step1),
                )
                is NotePiece.Item -> Row(verticalAlignment = Alignment.Top) {
                    // The dash sits on the middle of the item's first line (20sp lines, a 2dp dash).
                    Box(
                        Modifier
                            .padding(top = 9.dp, end = Space.step3)
                            .width(DashWidth)
                            .height(2.dp)
                            .clip(Radii.pillShape)
                            .background(ValenColors.Primary),
                    )
                    Text(piece.text, style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text)
                }
                is NotePiece.Line -> Text(piece.text, style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2)
            }
        }
    }
}

/** The file the release brings: a tile, its name, and where it stands. */
@Composable
private fun FileRow(updates: Updates, release: AppRelease, state: UpdateState, fill: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val size = sizeText(release.asset.size)
    val line = when (state) {
        is UpdateState.Downloading -> "${sizeText(state.done)} of ${sizeText(state.total)}"
        is UpdateState.Ready -> "$size · downloaded and checked"
        is UpdateState.Installing -> if (updates.route == UpdateRoute.System && release.asset.platform == "android") "Android is installing it" else "Installing"
        is UpdateState.Installed -> "In place · opens next time Valen starts"
        is UpdateState.Failed -> state.reason
        else -> size
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.rowShape)
            .background(fill)
            .padding(Space.step3),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(FileTile)
                .clip(Radii.coverShape)
                .background(ValenColors.Fill),
        ) {
            Icon(
                if (release.asset.platform == "android") Icons.Rounded.InstallMobile else Icons.Rounded.InstallDesktop,
                contentDescription = null,
                tint = ValenColors.Text,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(Space.step3))
        Column(Modifier.weight(1f)) {
            Text(
                release.asset.name,
                style = MaterialTheme.typography.titleMedium,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The line changes with the state (rising in as the old one fades); a download's count is one line.
            AnimatedContent(
                targetState = FileLine(line, downloading = state is UpdateState.Downloading),
                // A download's count is one line updated in place; a new state is a new line.
                contentKey = { if (it.downloading) "downloading" else it.text },
                transitionSpec = {
                    if (reduced) {
                        fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                    } else {
                        (fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it }) togetherWith
                            (fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { -it / 2 })
                    }
                },
                label = "updateFileLine",
            ) { shown ->
                Text(
                    shown.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = if (state is UpdateState.Failed) ValenColors.Danger else ValenColors.Text2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class FileLine(val text: String, val downloading: Boolean)

/** The one press the state asks for, poured full while it downloads; then the release's page and "Later". */
@Composable
private fun Actions(
    updates: Updates,
    state: UpdateState,
    release: AppRelease,
    onLater: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val told = updates.route == UpdateRoute.Tell
    val label = when {
        told -> "Open release page"
        state is UpdateState.Downloading -> "Downloading  ${(state.fraction * 100).toInt()}%"
        state is UpdateState.Installing -> "Installing"
        state is UpdateState.Installed -> "Restart now"
        state is UpdateState.Ready -> "Install"
        state is UpdateState.Failed -> "Try again"
        updates.route == UpdateRoute.Itself -> "Update"
        else -> "Download and install"
    }
    val act: () -> Unit = when {
        told -> { { updates.openPage(release); onClose() } }
        state is UpdateState.Installed -> updates::restart
        else -> updates::update
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.step2), modifier = modifier.fillMaxWidth()) {
        PourButton(
            label = label,
            poured = (state as? UpdateState.Downloading)?.fraction,
            enabled = told || (state !is UpdateState.Downloading && state !is UpdateState.Installing),
            onClick = act,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.step2), modifier = Modifier.fillMaxWidth()) {
            if (!told) {
                QuietButton(
                    label = "Release page",
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    onClick = { updates.openPage(release) },
                    stretch = true,
                    modifier = Modifier.weight(1f),
                )
            }
            QuietButton(label = "Later", onClick = onLater, stretch = true, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The filled pill, which a download pours full: the track is the 10% fill and the primary runs in
 * from the left as the bytes arrive; the label is white over the track and on-primary over the
 * paint, cut exactly at the paint's edge. When the pour is done the pill gives a little (the
 * squish spring) and its new word rises in.
 */
@Composable
private fun PourButton(label: String, poured: Float?, enabled: Boolean, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val source = rememberInteraction()
    val hovered by source.collectIsHoveredAsState()
    val pouring = poured != null
    // Two coats. The button's own [solid] fill, and the [reach] of the paint a download pours in
    // from the left. When a download starts the solid fill fades away (never drains backwards)
    // while the pour starts from nothing; when it ends the pour runs the last of the way and the
    // solid fill is simply there again under it, so there is never a flash of bare track.
    val reach = remember { Animatable(poured ?: 1f) }
    val solid = remember { Animatable(if (pouring) 0f else 1f) }
    // The solid coat follows whether a download runs (its own effect: the pour's restarts with
    // every tick of the download and would cut the fade short).
    LaunchedEffect(pouring) {
        if (pouring) {
            reach.snapTo(0f)
            solid.animateTo(0f, if (reduced) Motion.fade() else Motion.exit())
        }
    }
    // The pour follows the download, and at its end runs the last of the way.
    LaunchedEffect(poured) {
        reach.animateTo(poured ?: 1f, if (reduced) Motion.fade() else Motion.standard())
        if (poured == null) solid.snapTo(1f)
    }
    val paint by animateColorAsState(
        when {
            !enabled && !pouring -> ValenColors.Fill
            hovered && !pouring -> ValenColors.Primary.copy(alpha = 0.86f)
            else -> ValenColors.Primary
        },
        Motion.press(),
        label = "updatePillPaint",
    )
    val give = remember { Animatable(1f) }
    var wasPouring by remember { mutableStateOf(false) }
    LaunchedEffect(pouring) {
        if (wasPouring && !pouring && !reduced) {
            give.snapTo(Motion.PressedScale)
            give.animateTo(1f, Motion.squish())
        }
        wasPouring = pouring
    }
    // The sheet's one action reads a step bigger than a button's label (Inter 15 semi-bold).
    val style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(ActionHeight)
            .graphicsLayer {
                scaleX = give.value
                scaleY = give.value
            }
            .pressScale(source)
            .clip(Radii.pillShape)
            .drawBehind {
                drawRect(ValenColors.Fill)
                if (enabled || pouring) {
                    val coat = solid.value
                    if (coat < 1f) drawRect(ValenColors.Primary, size = Size(size.width * reach.value, size.height))
                    if (coat > 0f) drawRect(paint, alpha = coat)
                }
            }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        AnimatedContent(
            targetState = PillLabel(label, pouring, enabled),
            // A download's percent is one label updated in place; a new state is a new one, rising in.
            contentKey = { if (it.pouring) "pouring" else it.text },
            transitionSpec = {
                if (reduced) {
                    fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                } else {
                    (fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it }) togetherWith
                        (fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { -it / 2 })
                }
            },
            label = "updatePillLabel",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (shown.pouring) {
                    Text(shown.text, style = style, color = ValenColors.Text, maxLines = 1)
                    // The same words in on-primary, shown only where the paint has reached.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .matchParentSize()
                            .drawWithContent { clipRect(right = size.width * reach.value) { this@drawWithContent.drawContent() } },
                    ) {
                        Text(shown.text, style = style, color = ValenColors.OnPrimary, maxLines = 1)
                    }
                } else {
                    Text(shown.text, style = style, color = if (shown.enabled) ValenColors.OnPrimary else ValenColors.Text3, maxLines = 1)
                }
            }
        }
    }
}

private data class PillLabel(val text: String, val pouring: Boolean, val enabled: Boolean)

/** The notes thin out over [EdgeFade] at an edge they scroll past, and only there. */
private fun Modifier.edgeFade(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val reach = EdgeFade.toPx()
        val top = if (scroll.canScrollBackward) reach else 0f
        val bottom = if (scroll.canScrollForward) reach else 0f
        if (top == 0f && bottom == 0f) return@drawWithContent
        val h = size.height.coerceAtLeast(1f)
        val stops = buildList {
            if (top > 0f) add(0f to Color.Transparent)
            add(top / h to Color.Black)
            add((h - bottom) / h to Color.Black)
            if (bottom > 0f) add(1f to Color.Transparent)
        }.toTypedArray()
        drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
    }

private fun releaseOf(state: UpdateState): AppRelease? = when (state) {
    is UpdateState.Available -> state.release
    is UpdateState.Downloading -> state.release
    is UpdateState.Ready -> state.release
    is UpdateState.Installing -> state.release
    is UpdateState.Installed -> state.release
    is UpdateState.Failed -> state.release
    else -> null
}

/** A file's size the way people say it: "116 MB", "860 KB". */
private fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
    bytes > 0 -> "${(bytes + 500) / 1000} KB"
    else -> "0 KB"
}
