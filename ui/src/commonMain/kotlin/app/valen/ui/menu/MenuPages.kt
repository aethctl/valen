/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.menu

import androidx.compose.material.icons.rounded.Person
import app.valen.ui.artist.LocalBrowser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistManager
import app.valen.core.SleepTimer
import app.valen.core.SoundPresets
import app.valen.core.Track
import app.valen.core.webLink
import app.valen.ui.LocalAppScope
import app.valen.ui.canKeepScreenOn
import app.valen.ui.components.Artwork
import app.valen.ui.components.Group
import app.valen.ui.components.IconTap
import app.valen.ui.components.NameField
import app.valen.ui.components.PlaylistCover
import app.valen.ui.components.QuietButton
import app.valen.ui.components.Toggle
import app.valen.ui.components.VolumeSlider
import app.valen.ui.components.playlistDetail
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.rise
import app.valen.ui.components.tap
import app.valen.ui.rememberSystemShare
import app.valen.ui.screens.openLink
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import app.valen.ui.util.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One thing the menu can do: as a tile at the top, or as a row under them. */
private class Action(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val chevron: Boolean = false,
)

/** The quick tiles: a component's own measurement. */
private val TileHeight = 96.dp
private val RowHeight = 52.dp

/** The step a tile or a group stands on: a white veil on glass, the next surface on the sheet. */
internal fun stepFill(glass: Boolean): Color = if (glass) ValenColors.Fill else ValenColors.Surface2

/**
 * The song, its heart, four quick things as tiles (the most likely first), then the rest as rows.
 * From a list: play next, add to queue, radio, add to playlist. From the player, where the song is
 * already playing: radio, add to playlist, share, open — and the player's own settings below.
 */
@Composable
internal fun MainPage(
    request: MenuRequest,
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    glass: Boolean,
    onPage: (MenuPage) -> Unit,
    onClose: () -> Unit,
    onSound: () -> Unit = {},
) {
    val track = request.track
    val origin = request.origin
    val scope = LocalAppScope.current
    val all by playlists.library.playlists.collectAsState()
    val snapshot by coordinator.nowPlaying.collectAsState()
    val settings by coordinator.settings.collectAsState()
    val sleep by coordinator.sleep.collectAsState()
    val liked = all.firstOrNull { it.isLiked }?.tracks?.any { it.key == track.key } == true
    val isCurrent = snapshot?.track?.key == track.key
    val link = track.webLink()
    val share = rememberSystemShare()
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val fill = stepFill(glass)
    val browser = LocalBrowser.current

    val candidates = buildList {
        if (origin != MenuOrigin.Player && !isCurrent) {
            add(Action(Icons.Rounded.QueuePlayNext, "Play next", { coordinator.playNext(track); onClose() }))
            if (origin !is MenuOrigin.Queue) add(Action(Icons.AutoMirrored.Rounded.QueueMusic, "Add to queue", { coordinator.enqueue(track); onClose() }))
        }
        add(Action(Icons.Rounded.Sensors, "Start radio", { scope.launch { coordinator.startRadio(track) }; onClose() }))
        add(Action(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", { onPage(MenuPage.AddToPlaylist) }, chevron = true))
        add(Action(Icons.Rounded.Person, "Go to artist", { browser.openArtistOf(track.artist); onClose() }))
        add(
            Action(Icons.Rounded.Share, "Share", {
                val text = link ?: "${track.title} · ${track.artist}"
                if (!share(track.title, text)) {
                    clipboard.setText(AnnotatedString(text))
                    coordinator.confirm(if (link != null) "Link copied" else "Copied")
                }
                onClose()
            }),
        )
        if (link != null) {
            add(Action(Icons.AutoMirrored.Rounded.OpenInNew, "Open on ${track.provider.short}", { openLink(uri, link); onClose() }))
        }
    }
    val tiles = candidates.take(4)
    val overflow = candidates.drop(4)

    MenuHeader(track = track, liked = liked, onLike = { scope.launch { playlists.library.toggleLiked(track) } }, modifier = Modifier.rise(0))
    Spacer(Modifier.height(Space.step4))
    Column(verticalArrangement = Arrangement.spacedBy(Space.step2)) {
        tiles.chunked(2).forEachIndexed { r, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.step2), modifier = Modifier.rise(1 + r)) {
                pair.forEach { MenuTile(it, fill, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    val rows = buildList<@Composable () -> Unit> {
        if (origin == MenuOrigin.Player) {
            if (!glass) {
                add {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(horizontal = Space.step4),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = ValenColors.Text, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(Space.step3))
                        VolumeSlider(
                            volume = settings.volume,
                            onVolume = { v -> scope.launch { coordinator.setVolume(v) } },
                            railWidth = null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            add {
                val sound by coordinator.sound.collectAsState()
                MenuRow(Icons.Rounded.GraphicEq, "Sound", onClick = onSound) {
                    Text(
                        text = when {
                            coordinator.effectSupport(track) is app.valen.core.EffectSupport.None -> "Off on ${track.provider.short}"
                            !sound.on || sound.isFlat -> "As recorded"
                            else -> SoundPresets.byId(sound.preset)?.name ?: "My sound"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sound.on && !sound.isFlat) ValenColors.Primary else ValenColors.Text2,
                        maxLines = 1,
                    )
                    Chevron()
                }
            }
            add {
                MenuRow(Icons.Rounded.Bedtime, "Sleep timer", onClick = { onPage(MenuPage.Sleep) }) {
                    Text(
                        text = sleepLabel(sleep),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sleep != null) ValenColors.Primary else ValenColors.Text2,
                    )
                    Chevron()
                }
            }
            if (canKeepScreenOn) {
                add {
                    MenuRow(Icons.Rounded.BrightnessHigh, "Keep screen on", onClick = { coordinator.setKeepScreenOn(!settings.keepScreenOn) }) {
                        Toggle(checked = settings.keepScreenOn, onChange = coordinator::setKeepScreenOn, label = "Keep screen on")
                    }
                }
            }
        }
        val shelf = coordinator.offline
        if (shelf != null && track.provider != app.valen.core.ProviderId.LOCAL) {
            add { DownloadRow(track, shelf, coordinator) }
        }
        overflow.forEach { action ->
            add { MenuRow(action.icon, action.label, onClick = action.onClick) { if (action.chevron) Chevron() } }
        }
        when (origin) {
            is MenuOrigin.InPlaylist -> {
                val list = all.firstOrNull { it.id == origin.playlistId }
                if (list != null) {
                    add {
                        MenuRow(
                            icon = Icons.Rounded.RemoveCircleOutline,
                            label = if (list.isLiked) "Remove from Liked songs" else "Remove from this playlist",
                            danger = true,
                            onClick = {
                                scope.launch {
                                    if (playlists.library.removeTrack(list.id, track.key)) coordinator.confirm("Removed from ${list.name}")
                                }
                                onClose()
                            },
                        )
                    }
                }
            }
            is MenuOrigin.Queue -> add {
                MenuRow(
                    icon = Icons.Rounded.RemoveCircleOutline,
                    label = "Remove from queue",
                    danger = true,
                    onClick = {
                        val queue = coordinator.queue.value
                        val at = origin.index.takeIf { queue.getOrNull(it)?.key == track.key } ?: queue.indexOfFirst { it.key == track.key }
                        if (at >= 0) coordinator.removeAt(at)
                        onClose()
                    },
                )
            }
            else -> Unit
        }
    }
    if (rows.isNotEmpty()) {
        Spacer(Modifier.height(Space.step3))
        Group(rows = rows, fill = fill, shape = Radii.rowShape, modifier = Modifier.rise(4))
    }
}

/**
 * The song on this device: Download, then its progress in place ("Waiting", "40 %"), then
 * Remove download. A song kept because a downloaded playlist has it says so and is left to the
 * list; one no service could offer says why, and a press tries again.
 */
@Composable
private fun DownloadRow(track: Track, shelf: app.valen.core.OfflineShelf, coordinator: PlaybackCoordinator) {
    val scope = LocalAppScope.current
    val state by shelf.state.collectAsState()
    val key = track.key
    val fraction = state.fetching[key]
    when {
        fraction != null || key in state.waiting -> MenuRow(Icons.Rounded.Downloading, "Downloading", onClick = null) {
            Text(
                text = if (fraction == null) "Waiting" else "${(fraction * 100).toInt()} %",
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = ValenColors.Primary,
                maxLines = 1,
            )
        }
        key in state.kept && key in state.singles -> MenuRow(
            Icons.Rounded.DownloadDone,
            "Remove download",
            onClick = {
                scope.launch { shelf.forgetSong(track) }
                coordinator.confirm("Removed from this device")
            },
        ) {
            Text("On this device", style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2, maxLines = 1)
        }
        key in state.kept -> MenuRow(Icons.Rounded.DownloadDone, "Downloaded", onClick = null) {
            Text("With a playlist", style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2, maxLines = 1)
        }
        else -> MenuRow(Icons.Rounded.Download, "Download", onClick = { shelf.keepSong(track) }) {
            state.unavailable[key]?.let {
                Text("Not found", style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text3, maxLines = 1)
            }
        }
    }
}

/** "Off", "23 min", "End of song": the timer as the row beside it says it. */
private fun sleepLabel(sleep: SleepTimer?): String = when (sleep) {
    null -> "Off"
    SleepTimer.EndOfSong -> "End of song"
    is SleepTimer.At -> {
        val left = ((sleep.endsAt - System.currentTimeMillis()).coerceAtLeast(0) + 59_999) / 60_000
        "$left min"
    }
}

/**
 * Every playlist but Liked songs — your own, saved service lists and mixes — each with a mark that
 * says whether the song is in it; a press puts it in or takes it out. "New playlist" opens a name typed straight into the row, and the new list is made
 * with the song already in it.
 */
@Composable
internal fun AddToPlaylistPage(
    track: Track,
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    glass: Boolean,
    onBack: () -> Unit,
) {
    val scope = LocalAppScope.current
    val all by playlists.library.playlists.collectAsState()
    // Every list but Liked songs: your own, saved service lists and mixes (their additions are kept).
    val own = all.filter { it.takesSongs }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    fun create() {
        val wanted = name
        naming = false
        name = ""
        scope.launch {
            val made = playlists.library.create(wanted)
            playlists.library.addTracks(made.id, listOf(track))
            coordinator.confirm("Added to ${made.name}")
        }
    }

    PageHead("Add to playlist", onBack, Modifier.rise(0))
    Spacer(Modifier.height(Space.step3))
    val rows = buildList<@Composable () -> Unit> {
        add {
            if (naming) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = Space.step3, end = Space.step2),
                ) {
                    AddMark()
                    Spacer(Modifier.width(Space.step3))
                    NameField(
                        value = name,
                        onValue = { name = it },
                        onDone = { create() },
                        onCancel = { naming = false; name = "" },
                        style = MaterialTheme.typography.titleMedium,
                        label = "Playlist name",
                        modifier = Modifier.weight(1f),
                    )
                    QuietButton(label = "Create", small = true, onClick = { create() })
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .tap(onClick = { naming = true })
                        .padding(horizontal = Space.step3),
                ) {
                    AddMark()
                    Spacer(Modifier.width(Space.step3))
                    Text("New playlist", style = MaterialTheme.typography.titleMedium, color = ValenColors.Text)
                }
            }
        }
        own.forEach { list ->
            add {
                val inside = list.tracks.any { it.key == track.key }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .semantics { contentDescription = if (inside) "In ${list.name}" else "Add to ${list.name}" }
                        .tap(onClick = {
                            scope.launch {
                                if (inside) playlists.library.removeTrack(list.id, track.key) else playlists.library.addTracks(list.id, listOf(track))
                            }
                        })
                        .padding(start = Space.step3, end = Space.step4),
                ) {
                    PlaylistCover(playlist = list, size = 40.dp, shape = Radii.coverShape, iconSize = 18.dp)
                    Column(Modifier.weight(1f).padding(horizontal = Space.step3)) {
                        Text(list.name, style = MaterialTheme.typography.titleMedium, color = ValenColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(playlistDetail(list), style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2, maxLines = 1)
                    }
                    CheckMark(inside)
                }
            }
        }
    }
    Group(rows = rows, fill = stepFill(glass), shape = Radii.rowShape, modifier = Modifier.rise(1))
    if (own.isEmpty() && !naming) {
        Text(
            text = "Your playlists show here.",
            style = MaterialTheme.typography.bodySmall,
            color = ValenColors.Text2,
            modifier = Modifier.padding(top = Space.step3, start = Space.step1).rise(2),
        )
    }
}

/**
 * The sleep timer: how long, or to the end of the song. While one runs, a ring drains with the
 * time left (or with the song, for "End of song") beside the countdown.
 */
@Composable
internal fun SleepPage(coordinator: PlaybackCoordinator, glass: Boolean, onBack: () -> Unit) {
    val sleep by coordinator.sleep.collectAsState()
    val snapshot by coordinator.snapshot.collectAsState()
    // A second's tick, so the countdown moves.
    val second by produceState(0L) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    PageHead("Sleep timer", onBack, Modifier.rise(0))
    Spacer(Modifier.height(Space.step3))
    AnimatedVisibility(
        visible = sleep != null,
        enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
        exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
    ) {
        val timer = sleep
        val (left, whole) = when (timer) {
            is SleepTimer.At -> (timer.endsAt - second).coerceAtLeast(0L) to timer.minutes * 60_000L
            else -> {
                val d = snapshot?.durationMs?.takeIf { it > 0 } ?: 1L
                (d - (snapshot?.positionMs ?: 0L)).coerceAtLeast(0) to d
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = Space.step1, bottom = Space.step4),
        ) {
            DrainRing(fraction = (left.toFloat() / whole).coerceIn(0f, 1f))
            Spacer(Modifier.width(Space.step4))
            Column {
                Text(formatTime(left), style = TrackType.headline.copy(fontFeatureSettings = "tnum"), color = ValenColors.Text)
                Text(
                    text = if (timer is SleepTimer.At) "until the music stops" else "Stops when this song ends",
                    style = MaterialTheme.typography.bodySmall,
                    color = ValenColors.Text2,
                )
            }
        }
    }
    val chosen = (sleep as? SleepTimer.At)?.minutes
    val options = listOf(15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour")
    val rows = buildList<@Composable () -> Unit> {
        options.forEach { (minutes, label) ->
            add {
                MenuRow(null, label, onClick = { coordinator.sleepIn(minutes) }) { CheckMark(chosen == minutes, quiet = true) }
            }
        }
        add {
            MenuRow(null, "End of song", onClick = { coordinator.sleepAtEndOfSong() }) {
                CheckMark(sleep == SleepTimer.EndOfSong, quiet = true)
            }
        }
        if (sleep != null) {
            add { MenuRow(null, "Turn off", danger = true, onClick = { coordinator.cancelSleep() }) }
        }
    }
    Group(rows = rows, fill = stepFill(glass), shape = Radii.rowShape, modifier = Modifier.rise(1))
}

/** The song at the top of the menu: its cover, its two lines, and its heart. */
@Composable
private fun MenuHeader(track: Track, liked: Boolean, onLike: () -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Artwork(url = track.artworkUrl, shape = Radii.coverShape, contentDescription = null, iconSize = 22.dp, modifier = Modifier.size(52.dp))
        Column(Modifier.weight(1f).padding(horizontal = Space.step3)) {
            Text(track.title, style = MaterialTheme.typography.titleLarge, color = ValenColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${track.artist}  ·  ${track.provider.short}",
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LiquidHeart(liked = liked, onToggle = onLike)
    }
}

/** A quick thing: its glyph in the top corner, its name at the foot, on one raised step. */
@Composable
private fun MenuTile(action: Action, fill: Color, modifier: Modifier = Modifier) {
    val source = rememberInteraction()
    Column(
        modifier = modifier
            .height(TileHeight)
            .pressScale(source)
            .tap(onClick = action.onClick, shape = Radii.rowShape, source = source, rest = fill)
            .padding(Space.step4),
    ) {
        Icon(action.icon, contentDescription = null, tint = ValenColors.Text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.weight(1f))
        Text(action.label, style = MaterialTheme.typography.titleMedium, color = ValenColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A row of a menu group: a glyph, a name, and whatever it shows at its end. */
@Composable
private fun MenuRow(
    icon: ImageVector?,
    label: String,
    onClick: (() -> Unit)?,
    danger: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val ink = if (danger) ValenColors.Danger else ValenColors.Text
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier)
            .padding(start = Space.step4, end = Space.step3),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.step3))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = ValenColors.Text3,
        modifier = Modifier.padding(start = Space.step1).size(20.dp),
    )
}

/** A sub-page's head: back, and the page's name in the app's voice. */
@Composable
private fun PageHead(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        IconTap(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back", onClick = onBack, tint = ValenColors.Text)
        Spacer(Modifier.width(Space.step1))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = ValenColors.Text)
    }
}

/** The plus at the head of "New playlist", in a cover-sized well. */
@Composable
private fun AddMark() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(Radii.coverShape)
            .background(ValenColors.Fill),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = ValenColors.Text, modifier = Modifier.size(22.dp))
    }
}

/**
 * Whether the song is in a list (or an option is chosen): an empty ring, or a drop of the primary
 * tone with a tick that springs in. [quiet] leaves the empty ring out, for a list of options.
 */
@Composable
private fun CheckMark(on: Boolean, quiet: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val grow by animateFloatAsState(if (on) 1f else 0f, if (reduced) Motion.fade() else Motion.squish(), label = "check")
    val ring by animateColorAsState(if (on) ValenColors.Primary else ValenColors.Text3, Motion.standard(), label = "checkRing")
    val primary = ValenColors.Primary
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp)) {
        Canvas(Modifier.size(22.dp)) {
            val r = size.minDimension / 2f
            if (!quiet) drawCircle(ring, r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            if (grow > 0.01f) drawCircle(primary, r * grow.coerceAtMost(1.15f))
        }
        if (grow > 0.01f) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = ValenColors.OnPrimary,
                modifier = Modifier.size(16.dp).graphicsLayer { scaleX = grow; scaleY = grow },
            )
        }
    }
}

/** A ring of the primary tone that empties as the time runs out, from the top, clockwise. */
@Composable
private fun DrainRing(fraction: Float) {
    val shown by animateFloatAsState(fraction, Motion.standard(), label = "drain")
    val track = ValenColors.Fill
    val primary = ValenColors.Primary
    Canvas(Modifier.size(56.dp)) {
        val stroke = 5.dp.toPx()
        val inset = stroke / 2f
        val box = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(stroke))
        drawArc(primary, -90f, 360f * shown, useCenter = false, topLeft = Offset(inset, inset), size = box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
