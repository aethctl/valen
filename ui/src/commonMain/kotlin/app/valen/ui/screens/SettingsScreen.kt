/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import app.valen.ui.theme.Motion
import app.valen.ui.components.ScrollPage
import app.valen.ui.profile.forgetPicture
import app.valen.ui.components.QuietButton
import app.valen.core.PlaylistManager
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.valen.core.AudioQuality
import app.valen.core.PlaybackCoordinator
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.PresenceStatus
import app.valen.ui.rememberFolderOpener
import androidx.compose.ui.text.style.TextOverflow
import app.valen.ui.LocalAppScope
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.Group
import app.valen.ui.components.IconTap
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PageTitle
import app.valen.ui.components.PageTop
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.SegmentedFilter
import app.valen.ui.components.ServiceTile
import app.valen.ui.components.Toggle
import app.valen.ui.components.tap
import androidx.compose.ui.semantics.Role
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import app.valen.core.localEpochDay
import app.valen.core.week
import app.valen.ui.SystemBack
import app.valen.ui.components.ItemArrival
import app.valen.ui.components.KineticText
import app.valen.ui.components.LocalPageEntrance
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.arrive
import app.valen.ui.components.rememberPageEntrance
import app.valen.ui.components.rememberPageValue
import app.valen.ui.recap.listenedFor
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion

/**
 * Settings, in parts: the services and their order, how songs play and show their words, the
 * colour, downloads, Discord, what this app is (with your data), and switches for testing. A wide window
 * shows the parts as an index beside the one that is open; a phone lists them, and a part opens as
 * its own page. Every row reports real state; none of the buttons are decorative. Each group is
 * one raised surface with hairlines between its rows.
 */
@Composable
fun SettingsScreen(
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    playlists: PlaylistManager? = null,
    onAbout: () -> Unit = {},
    onCleared: () -> Unit = {},
) {
    val parts = remember(coordinator) {
        SettingsPart.entries.filter {
            when (it) {
                SettingsPart.Downloads -> coordinator.offline != null
                SettingsPart.Discord -> coordinator.presence != null
                else -> true
            }
        }
    }
    // Which part is open, kept above the width switch: a phone's open page is the wide window's
    // chosen part and back. Null on a phone is the index.
    var openName by rememberPageValue<String?>("settings:part", null)
    val open = parts.firstOrNull { it.name.equals(openName, ignoreCase = true) }
    val content: @Composable (SettingsPart) -> Unit = { part ->
        PartContent(part, coordinator, playlists, onAbout, onCleared)
    }

    Box(modifier = modifier.fillMaxSize().background(ValenColors.Background)) {
        if (LocalChrome.current.compact) {
            PhoneSettings(parts, open, onOpen = { openName = it?.name }, onBack = onBack, coordinator = coordinator, content = content)
        } else {
            WideSettings(parts, open ?: parts.first(), onOpen = { openName = it.name }, coordinator = coordinator, content = content)
        }
    }
}

/** The parts of Settings, in the order the index lists them. */
internal enum class SettingsPart(val title: String, val icon: ImageVector) {
    Services("Services", Icons.Rounded.Link),
    Playback("Playback", Icons.Rounded.Headphones),
    Look("Colour", Icons.Rounded.Palette),
    Downloads("Downloads", Icons.Rounded.Download),
    Discord("Discord", Icons.Rounded.Groups),
    About("About", Icons.Rounded.Info),
    Test("Test", Icons.Rounded.Science),
}

/**
 * A wide window: the title, then the index on the left — the open part marked by a droplet of
 * the container tone that pours from row to row — and the open part beside it, scrolling on its
 * own, so the index stays where it is.
 */
@Composable
private fun WideSettings(
    parts: List<SettingsPart>,
    open: SettingsPart,
    onOpen: (SettingsPart) -> Unit,
    coordinator: PlaybackCoordinator,
    content: @Composable (SettingsPart) -> Unit,
) {
    val chrome = LocalChrome.current
    val reduced = LocalReducedMotion.current
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier.fillMaxSize().padding(start = chrome.start),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Layout.settingsMaxWidth)
                .fillMaxSize()
                .padding(horizontal = chrome.gutter),
        ) {
            PageTop()
            PageTitle(title = "Settings")
            Spacer(Modifier.height(Space.step6))
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Index(
                    parts = parts,
                    open = open,
                    onOpen = onOpen,
                    coordinator = coordinator,
                    modifier = Modifier.width(Layout.settingsIndexWidth),
                )
                Spacer(Modifier.width(Space.step10))
                AnimatedContent(
                    targetState = open,
                    transitionSpec = {
                        if (reduced) {
                            fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                        } else {
                            // The new part rises a little as the old one sinks away.
                            (fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { it / 40 }) togetherWith
                                fadeOut(Motion.exit())
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = "settingsPart",
                ) { part ->
                    // Each part arrives like a page: its title rises letter by letter, its groups after it.
                    CompositionLocalProvider(LocalPageEntrance provides rememberPageEntrance(part)) {
                        ScrollPage(key = "settings:" + part.name, modifier = Modifier.fillMaxSize()) {
                            KineticText(
                                text = part.title,
                                style = MaterialTheme.typography.headlineLarge,
                                color = ValenColors.Text,
                                modifier = Modifier.padding(top = Space.step2),
                            )
                            Spacer(Modifier.height(Space.step2))
                            content(part)
                            PageBottom()
                        }
                    }
                }
            }
        }
    }
}

/**
 * The index of a wide window. Where you are is a droplet of the container tone, its two ends on
 * the droplet's springs: the one reaching for the new row quick, the other following slow, so it
 * stretches toward the row and gathers itself there.
 */
@Composable
private fun Index(
    parts: List<SettingsPart>,
    open: SettingsPart,
    onOpen: (SettingsPart) -> Unit,
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val row = Layout.settingsIndexRow
    val target: Dp? = parts.indexOf(open).takeIf { it >= 0 }?.let { row * it }
    val down = remember { mutableStateOf(true) }
    var last by remember { mutableStateOf(open) }
    if (last != open) {
        down.value = parts.indexOf(open) > parts.indexOf(last)
        last = open
    }
    val lead = if (reduced) tween<Dp>(Motion.ReducedTweenMs) else Motion.liquidLead()
    val trail = if (reduced) tween<Dp>(Motion.ReducedTweenMs) else Motion.liquidTrail()
    val top by animateDpAsState(target ?: 0.dp, if (down.value) trail else lead, label = "dropTop")
    val bottom by animateDpAsState((target ?: 0.dp) + row, if (down.value) lead else trail, label = "dropBottom")
    Box(modifier = modifier) {
        if (target != null) {
            Box(
                Modifier
                    .offset(y = top)
                    .fillMaxWidth()
                    .height((bottom - top).coerceAtLeast(row * 0.5f))
                    .clip(Radii.rowShape)
                    .background(ValenColors.Container),
            )
        }
        Column {
            parts.forEachIndexed { i, part ->
                val chosen = part == open
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(row)
                        .tap(onClick = { onOpen(part) }, shape = Radii.rowShape, role = Role.Tab)
                        .padding(horizontal = Space.step3)
                        .arrive(i),
                ) {
                    val ink = if (chosen) ValenColors.OnContainer else ValenColors.Text
                    Icon(part.icon, contentDescription = null, tint = if (chosen) ValenColors.OnContainer else ValenColors.Text2, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(Space.step3))
                    Column(Modifier.weight(1f)) {
                        Text(part.title, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1)
                        PartSummary(part, coordinator, color = if (chosen) ValenColors.OnContainer.copy(alpha = 0.72f) else ValenColors.Text2)
                    }
                }
            }
        }
    }
}

/**
 * A phone: the title and the parts as one group; a part opens as a page of its own, sliding in
 * from the side it leads to, with Back (or the system's back) to the list.
 */
@Composable
private fun PhoneSettings(
    parts: List<SettingsPart>,
    open: SettingsPart?,
    onOpen: (SettingsPart?) -> Unit,
    onBack: (() -> Unit)?,
    coordinator: PlaybackCoordinator,
    content: @Composable (SettingsPart) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    SystemBack(enabled = open != null) { onOpen(null) }
    AnimatedContent(
        targetState = open,
        transitionSpec = {
            val forward = targetState != null
            if (reduced) {
                fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            } else {
                (slideInHorizontally(Motion.enter()) { if (forward) it / 3 else -it / 3 } + fadeIn(Motion.enter())) togetherWith
                    (slideOutHorizontally(Motion.exit()) { if (forward) -it / 6 else it / 6 } + fadeOut(Motion.exit()))
            }.using(SizeTransform(clip = false))
        },
        modifier = Modifier.fillMaxSize(),
        label = "settingsPage",
    ) { part ->
        CompositionLocalProvider(LocalPageEntrance provides rememberPageEntrance(part)) {
            ScrollPage(key = "settings:" + (part?.name ?: "index"), modifier = Modifier.fillMaxSize()) {
                ContentColumn {
                    PageTop()
                    if (part == null) {
                        PageTitle(
                            title = "Settings",
                            leading = onBack?.let { back -> { IconTap(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back", onClick = back, tint = ValenColors.Text) } },
                        )
                        Spacer(Modifier.height(Space.step6))
                        Group(
                            parts.mapIndexed { i, p ->
                                {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = Layout.settingsIndexRow + Space.step2)
                                            .tap(onClick = { onOpen(p) })
                                            .padding(start = Space.step4, end = Space.step2)
                                            .arrive(i),
                                    ) {
                                        Icon(p.icon, contentDescription = null, tint = ValenColors.Text2, modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.width(Space.step4))
                                        Column(Modifier.weight(1f)) {
                                            Text(p.title, style = MaterialTheme.typography.titleMedium, color = ValenColors.Text, maxLines = 1)
                                            PartSummary(p, coordinator, color = ValenColors.Text2)
                                        }
                                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = ValenColors.Text3, modifier = Modifier.size(22.dp))
                                    }
                                }
                            },
                        )
                    } else {
                        PageTitle(
                            title = part.title,
                            leading = { IconTap(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back to settings", onClick = { onOpen(null) }, tint = ValenColors.Text) },
                        )
                        Spacer(Modifier.height(Space.step4))
                        content(part)
                    }
                }
                PageBottom()
            }
        }
    }
}

/** A part's state in one line, under its name in the index. */
@Composable
private fun PartSummary(part: SettingsPart, coordinator: PlaybackCoordinator, color: Color) {
    val settings by coordinator.settings.collectAsState()
    val line = when (part) {
        SettingsPart.Services -> {
            val sessions by coordinator.sessions.collectAsState()
            val signed = ProviderId.services.count { sessions[it] is SessionState.Connected }
            val first = coordinator.priorityOrder(settings.priority).firstOrNull { it.isService }
            listOfNotNull(
                when (signed) {
                    0 -> "None signed in"
                    ProviderId.services.size -> "All signed in"
                    else -> "$signed of ${ProviderId.services.size} signed in"
                },
                first?.let { "${it.label} first" },
            ).joinToString(" · ")
        }
        SettingsPart.Playback -> "${settings.quality.label} quality · lyrics ${if (settings.lyrics) "on" else "off"}"
        SettingsPart.Look -> if (settings.coverColour) "From the cover" else "Black and pink"
        SettingsPart.Downloads -> {
            val shelf = coordinator.offline
            val state = shelf?.state?.collectAsState()?.value
            when (val n = state?.files ?: 0) {
                0 -> "No songs yet"
                1 -> "1 song kept"
                else -> "$n songs kept"
            }
        }
        SettingsPart.Discord -> {
            val status = coordinator.presence?.status?.collectAsState()?.value
            when {
                !settings.presence.enabled -> "Off"
                status is PresenceStatus.Connected -> "Showing on Discord"
                status == PresenceStatus.NoDiscord -> "On · Discord is not open"
                else -> "On"
            }
        }
        SettingsPart.About -> "Valen 1.0.0 · your data"
        SettingsPart.Test -> if (settings.recapAlways) "Your week on Home always" else "For trying things out"
    }
    Text(line, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** What a part holds. */
@Composable
private fun PartContent(
    part: SettingsPart,
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager?,
    onAbout: () -> Unit,
    onCleared: () -> Unit,
) {
    when (part) {
        SettingsPart.Services -> ServicesPart(coordinator)
        SettingsPart.Playback -> PlaybackPart(coordinator)
        SettingsPart.Look -> LookPart(coordinator)
        SettingsPart.Downloads -> DownloadsPart(coordinator)
        SettingsPart.Discord -> DiscordPart(coordinator)
        SettingsPart.About -> AboutPart(coordinator, playlists, onAbout, onCleared)
        SettingsPart.Test -> TestPart(coordinator)
    }
}

@Composable
private fun ServicesPart(coordinator: PlaybackCoordinator) {
    val scope = LocalAppScope.current
    val sessions by coordinator.sessions.collectAsState()
    val settings by coordinator.settings.collectAsState()
    Note("Sign-in opens the service's own page. Valen never sees your password and never asks for it.", Modifier.arrive(1))
    Group(
        ProviderId.services.map { provider ->
            {
                ServiceTile(
                    provider = provider,
                    state = sessions[provider] ?: SessionState.SignedOut,
                    onConnect = { scope.launch { coordinator.connect(provider) } },
                    onDisconnect = { scope.launch { coordinator.disconnect(provider) } },
                    contained = true,
                )
            }
        },
        modifier = Modifier.arrive(2),
    )

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "Sources", modifier = Modifier.arrive(3, ItemArrival.Swing))
    Note(
        "A song that more than one service has appears once in search. It plays from the " +
            "first service here, and songs the services rank equally are listed in this order.",
        Modifier.arrive(4),
    )
    // Songs on this device always come first; only the services are put in order.
    val order = coordinator.priorityOrder(settings.priority)
    val priority = order.filter { it.isService }
    Group(
        priority.mapIndexed { index, provider ->
            {
                SourceRow(
                    position = index + 1,
                    provider = provider,
                    canRise = index > 0,
                    canFall = index < priority.lastIndex,
                    onRise = { coordinator.setPriority(priority.moved(index, index - 1)) },
                    onFall = { coordinator.setPriority(priority.moved(index, index + 1)) },
                )
            }
        },
        modifier = Modifier.arrive(5),
    )
}

@Composable
private fun PlaybackPart(coordinator: PlaybackCoordinator) {
    val scope = LocalAppScope.current
    val settings by coordinator.settings.collectAsState()
    SectionHeader(title = "Audio quality", modifier = Modifier.arrive(1, ItemArrival.Swing))
    Note(
        when (settings.quality) {
            AudioQuality.AUTO -> "Each service picks the best stream it can currently deliver."
            AudioQuality.STANDARD -> "Asks every service for its standard stream."
            AudioQuality.HIGH -> "Asks every service for the highest stream your account allows."
        },
        Modifier.arrive(2),
    )
    SegmentedFilter(
        options = AudioQuality.entries,
        selected = settings.quality,
        onSelect = { quality -> scope.launch { coordinator.setQuality(quality) } },
        label = { it.label },
        modifier = Modifier.arrive(3),
    )

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "Lyrics", modifier = Modifier.arrive(4, ItemArrival.Swing))
    Note(
        "Words come from Lyprio when it is set up, then from Spotify when you are signed in, " +
            "then from LRCLIB.",
        Modifier.arrive(5),
    )
    Group(
        listOf(
            {
                SwitchRow(
                    title = "Show on the stage",
                    body = "The song's words beside its cover, or in its place on a phone.",
                    checked = settings.lyrics,
                    onChange = coordinator::setLyricsShown,
                )
            },
            {
                SwitchRow(
                    title = "Pen marks",
                    body = "When words are timed one by one, the marker strikes, circles or crosses out some once they are sung.",
                    checked = settings.lyricsMarks,
                    onChange = coordinator::setLyricsMarks,
                )
            },
            {
                SwitchRow(
                    title = "Moving words",
                    body = "Sung words rise a little, held notes ripple and the lines pour after each other.",
                    checked = settings.lyricsMotion,
                    onChange = coordinator::setLyricsMotion,
                )
            },
        ),
        modifier = Modifier.arrive(6),
    )
}

@Composable
private fun LookPart(coordinator: PlaybackCoordinator) {
    val settings by coordinator.settings.collectAsState()
    Note(
        if (settings.coverColour) {
            "The app takes its one colour from the cover that is playing, in soft tones."
        } else {
            "Pink on black, whatever is playing."
        },
        Modifier.arrive(1),
    )
    SegmentedFilter(
        options = listOf(false, true),
        selected = settings.coverColour,
        onSelect = { coordinator.setCoverColour(it) },
        label = { if (it) "From the cover" else "Black and pink" },
        modifier = Modifier.arrive(2),
    )
}

/** Switches for trying things out before their time. */
@Composable
private fun TestPart(coordinator: PlaybackCoordinator) {
    val settings by coordinator.settings.collectAsState()
    Note(
        "For trying things out. Nothing here changes your music or your listening. Your week waits on Home " +
            "by itself at the end of each week, on Sunday and Monday.",
        Modifier.arrive(1),
    )
    Group(
        listOf {
            SwitchRow(
                title = "Your week on Home",
                body = "Shows the Your week card on Home and in your profile now, not only at the end of the week.",
                checked = settings.recapAlways,
                onChange = coordinator::setRecapAlways,
            )
        },
        modifier = Modifier.arrive(2),
    )
}

@Composable
private fun DownloadsPart(coordinator: PlaybackCoordinator) {
    val shelf = coordinator.offline ?: return
    val settings by coordinator.settings.collectAsState()
    Note(
        "A downloaded playlist plays with no network and no sign-in. Each song is the best stream its service offers, " +
            "kept as it came (only tagged, never encoded again). Spotify songs are kept as the same recording from " +
            "YouTube Music or SoundCloud, so Spotify is never asked for anything.",
        Modifier.arrive(1),
    )
    val offline by shelf.state.collectAsState()
    Group(
        listOf(
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = Space.step4, vertical = Space.step3),
                ) {
                    Column(Modifier.weight(1f).padding(end = Space.step4)) {
                        Text(text = "Folder", style = MaterialTheme.typography.bodyLarge, color = ValenColors.Text)
                        Text(
                            text = app.valen.ui.shortFolder(shelf.folder),
                            style = MaterialTheme.typography.bodySmall,
                            color = ValenColors.Text2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    rememberFolderOpener(shelf.folder)?.let { open -> QuietButton(label = "Open", small = true, onClick = open) }
                }
            },
            {
                AboutRow(
                    "Kept",
                    when (val n = offline.files) {
                        0 -> "No songs yet"
                        1 -> "1 song"
                        else -> "$n songs"
                    },
                )
            },
            {
                SwitchRow(
                    title = "Offline playlist",
                    body = "A list in your library of every song that plays with no network: downloaded, or kept whole after a listen.",
                    checked = settings.offlineList,
                    onChange = coordinator::setOfflineList,
                )
            },
        ),
        modifier = Modifier.arrive(2),
    )
}

@Composable
private fun DiscordPart(coordinator: PlaybackCoordinator) {
    val link = coordinator.presence ?: return
    val settings by coordinator.settings.collectAsState()
    val status by link.status.collectAsState()
    val presence = settings.presence
    Note(presenceSentence(status, presence.enabled), Modifier.arrive(1))
    Group(
        buildList {
            add {
                SwitchRow(
                    title = "Show on Discord",
                    body = "Your profile says Listening to Valen, with the song, its cover and the time bar.",
                    checked = presence.enabled,
                    onChange = { on -> coordinator.setPresence { it.copy(enabled = on) } },
                )
            }
            if (presence.enabled) {
                add {
                    SwitchRow(
                        title = "Cover",
                        body = "The song's cover, with Valen's mark in its corner.",
                        checked = presence.cover,
                        onChange = { on -> coordinator.setPresence { it.copy(cover = on) } },
                    )
                }
                add {
                    SwitchRow(
                        title = "Time bar",
                        body = "How far into the song you are, moving on its own.",
                        checked = presence.progress,
                        onChange = { on -> coordinator.setPresence { it.copy(progress = on) } },
                    )
                }
                add {
                    SwitchRow(
                        title = "The line being sung",
                        body = "When a song has timed lyrics, the line you hear takes the artist's place for a moment.",
                        checked = presence.lyricLine,
                        onChange = { on -> coordinator.setPresence { it.copy(lyricLine = on) } },
                    )
                }
                add {
                    SwitchRow(
                        title = "Where it plays from",
                        body = "Spotify, YouTube Music, SoundCloud or this device, under the cover.",
                        checked = presence.service,
                        onChange = { on -> coordinator.setPresence { it.copy(service = on) } },
                    )
                }
                add {
                    SwitchRow(
                        title = "Song button",
                        body = "Friends can open the song on its service from your profile.",
                        checked = presence.buttons,
                        onChange = { on -> coordinator.setPresence { it.copy(buttons = on) } },
                    )
                }
            }
        },
        modifier = Modifier.arrive(2),
    )
}

@Composable
private fun AboutPart(
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager?,
    onAbout: () -> Unit,
    onCleared: () -> Unit,
) {
    val scope = LocalAppScope.current
    val feed = LocalHomeFeed.current
    val settings by coordinator.settings.collectAsState()
    Spacer(Modifier.height(Space.step2))
    Group(
        listOf(
            { AboutRow("Version", "1.0.0") },
            { AboutRow("Licence", "GPL-3.0") },
            { AboutRow("Playback built on", "Orpheon") },
            { AboutRow("Interface after", "Mochy Music, Orpheon") },
        ),
        modifier = Modifier.arrive(1),
    )
    Spacer(Modifier.height(Space.step3))
    QuietButton(label = "Who made Valen", icon = Icons.Rounded.Info, onClick = onAbout, modifier = Modifier.arrive(2))

    Spacer(Modifier.height(Space.step8))
    SectionHeader(title = "Your data", modifier = Modifier.arrive(3, ItemArrival.Swing))
    Note("Playlists, listening, pictures and settings are kept on this device.", Modifier.arrive(4))
    Box(Modifier.arrive(5)) {
        ClearData(
            onClear = {
                scope.launch {
                    // Pictures first, while the settings and playlists still say which are ours.
                    forgetPicture(settings.avatar)
                    forgetPicture(settings.banner)
                    playlists?.library?.playlists?.value?.forEach { forgetPicture(it.artworkUrl) }
                    playlists?.library?.clear()
                    feed.forYou = emptyList()
                    coordinator.clearData()
                    onCleared()
                }
            },
        )
    }
}

/** Where the link to Discord stands, in one sentence. */
private fun presenceSentence(status: PresenceStatus, enabled: Boolean): String = when (status) {
    PresenceStatus.Off -> if (enabled) "Starting…" else "Off. Turn it on to show what you play on your Discord profile."
    PresenceStatus.NeedsAppId -> "The application id Valen was given is not a number. Remove it to use Valen's own."
    PresenceStatus.NoDiscord -> "Discord is not open. Valen looks for it every few seconds."
    PresenceStatus.Connecting -> "Looking for Discord…"
    is PresenceStatus.Connected -> status.user?.let { "Showing on Discord as $it." } ?: "Showing on Discord."
    is PresenceStatus.Failed -> status.reason
}

/** The line under a group's title that says what the group does. */
@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = ValenColors.Text2,
        modifier = modifier.padding(bottom = Space.step4),
    )
}

/** A setting that is on or off: its name, a line saying what it does, and the switch. The row is the press. */
@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tap(onClick = { onChange(!checked) }, role = Role.Switch)
            .padding(horizontal = Space.step4, vertical = Space.step3),
    ) {
        Column(Modifier.weight(1f).padding(end = Space.step4)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = ValenColors.Text)
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2)
        }
        Toggle(checked = checked, onChange = onChange, label = title)
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = Space.step4),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text2,
        )
    }
}

/** A service in the preference order, moved a place at a time; no drag gesture to misread. */
@Composable
private fun SourceRow(
    position: Int,
    provider: ProviderId,
    canRise: Boolean,
    canFall: Boolean,
    onRise: () -> Unit,
    onFall: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(start = Space.step3, end = Space.step2),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(ValenColors.Fill),
        ) {
            Text(text = "$position", style = timeStyle(), color = ValenColors.Text)
        }
        Text(
            text = provider.label,
            style = MaterialTheme.typography.titleMedium,
            color = ValenColors.Text,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        )
        IconTap(
            icon = Icons.Rounded.KeyboardArrowUp,
            label = "Move ${provider.label} up",
            onClick = onRise,
            enabled = canRise,
            tint = ValenColors.Text,
            iconSize = 24.dp,
        )
        IconTap(
            icon = Icons.Rounded.KeyboardArrowDown,
            label = "Move ${provider.label} down",
            onClick = onFall,
            enabled = canFall,
            tint = ValenColors.Text,
            iconSize = 24.dp,
        )
    }
}

private fun List<ProviderId>.moved(from: Int, to: Int): List<ProviderId> {
    if (from !in indices || to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

/**
 * Starting over: one button, and a second, plain question before anything goes. Service sign-ins
 * are the browser's and are kept.
 */
@Composable
private fun ClearData(onClear: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    AnimatedContent(
        targetState = asking,
        transitionSpec = { (fadeIn(Motion.enter()) + scaleIn(Motion.enter(), initialScale = 0.96f)) togetherWith fadeOut(Motion.exit()) },
        label = "clearData",
    ) { open ->
        if (!open) {
            QuietButton(label = "Clear data", icon = Icons.Rounded.DeleteForever, contentColor = ValenColors.Danger, onClick = { asking = true })
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.cardShape)
                    .background(ValenColors.Surface)
                    .padding(Space.step5),
            ) {
                Text("Start over?", style = MaterialTheme.typography.headlineMedium, color = ValenColors.Text)
                Spacer(Modifier.height(Space.step2))
                Text(
                    "This removes your playlists, listening, pictures and settings, then opens the welcome again. Your service sign-ins stay.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ValenColors.Text2,
                )
                Spacer(Modifier.height(Space.step4))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
                    QuietButton(label = "Clear everything", icon = Icons.Rounded.DeleteForever, contentColor = ValenColors.Danger, onClick = {
                        asking = false
                        onClear()
                    })
                    QuietButton(label = "Keep", onClick = { asking = false })
                }
            }
        }
    }
}
