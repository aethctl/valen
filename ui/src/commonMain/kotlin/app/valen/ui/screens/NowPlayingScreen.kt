/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlayerSnapshot
import app.valen.core.Track
import app.valen.ui.LocalAppScope
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.components.AmbientWash
import app.valen.ui.components.Artwork
import app.valen.ui.components.ArtworkSquare
import app.valen.ui.components.EmptyState
import app.valen.ui.components.IconTap
import app.valen.ui.components.QuietButton
import app.valen.ui.components.SeekBar
import app.valen.ui.components.TransportBar
import app.valen.ui.components.VolumeSlider
import app.valen.ui.components.tap
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.launch

/**
 * The stage. The cover's colours fill the room and drift while the song plays; the cover itself
 * is the largest thing in it. Beside it, or under it on a narrow window: the song, one thick rail,
 * three bare glyphs, and what comes next.
 */
@Composable
fun NowPlayingScreen(
    coordinator: PlaybackCoordinator,
    onOpenQueue: () -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val scope = LocalAppScope.current
    val chrome = LocalChrome.current
    val snapshot by coordinator.snapshot.collectAsState()
    val settings by coordinator.settings.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val index by coordinator.index.collectAsState()
    val track = snapshot?.track
    val tones = ArtworkPalette.tones(track?.artworkUrl)
    val next = if (index >= 0) queue.getOrNull(index + 1) else null

    val controls = StageControls(
        onSeek = { position -> scope.launch { coordinator.seek(position) } },
        onPrevious = { scope.launch { coordinator.previous() } },
        onToggle = { scope.launch { coordinator.toggle() } },
        onNext = { scope.launch { coordinator.next() } },
        onVolume = { value -> scope.launch { coordinator.setVolume(value) } },
    )

    Box(modifier = modifier.fillMaxSize().background(ValenColors.Background)) {
        AmbientWash(
            tones = tones,
            drift = snapshot?.isPlaying == true,
            fade = 0.72f,
            modifier = Modifier.fillMaxSize(),
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = chrome.start),
        ) {
            val side = maxWidth >= 820.dp && maxWidth >= maxHeight
            when {
                track == null -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().padding(horizontal = chrome.gutter),
                ) {
                    EmptyState(
                        title = "The stage is empty",
                        body = "Nothing is queued. Search for a song and it plays here.",
                        actionLabel = "Search music",
                        onAction = onBrowse,
                        centered = true,
                    )
                }

                side -> SideStage(
                    track = track,
                    snapshot = snapshot,
                    volume = settings.volume,
                    next = next,
                    controls = controls,
                    onOpenQueue = onOpenQueue,
                    stageWidth = maxWidth,
                    stageHeight = maxHeight,
                )

                else -> TallStage(
                    track = track,
                    snapshot = snapshot,
                    volume = settings.volume,
                    next = next,
                    controls = controls,
                    onOpenQueue = onOpenQueue,
                    stageWidth = maxWidth,
                    stageHeight = maxHeight,
                    showVolume = !chrome.compact,
                    topInset = if (onClose != null) Space.step12 else Space.step6,
                )
            }
            if (onClose != null) {
                IconTap(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    label = "Close the player",
                    onClick = onClose,
                    tint = ValenColors.Text,
                    iconSize = 28.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = chrome.gutter - Space.step2, top = Space.step2),
                )
            }
        }
    }
}

private class StageControls(
    val onSeek: (Long) -> Unit,
    val onPrevious: () -> Unit,
    val onToggle: () -> Unit,
    val onNext: () -> Unit,
    val onVolume: (Float) -> Unit,
)

/** The smallest a stage cover gets, the share of the height it may take, and where it stops. */
private val StageCoverFloor = 220.dp
private const val StageCoverShare = 0.62f
private val StageCoverMax = 720.dp

/** The words, rail and transport stop here, so the stage keeps its shape on a very wide window. */
private val StageWordsMax = 560.dp

/** Wide and landscape: the cover on the left, everything about the song beside it. */
@Composable
private fun SideStage(
    track: Track,
    snapshot: PlayerSnapshot?,
    volume: Float,
    next: Track?,
    controls: StageControls,
    onOpenQueue: () -> Unit,
    stageWidth: Dp,
    stageHeight: Dp,
) {
    val gutter = LocalChrome.current.gutter
    val gap = if (stageWidth >= 1100.dp) Space.step16 else Space.step10
    // The stage is a block, not a page, so it grows with the window instead of hugging one size:
    // a fixed cover on a 2880px-wide screen reads as a postage stamp. The cover may take a share of
    // the stage's height, up to a size that no longer needs to grow.
    val ceiling = maxOf(StageCoverFloor, min(stageHeight * StageCoverShare, StageCoverMax))
    val cover = min(stageHeight - Space.step24 - Space.step8, (stageWidth - gutter * 2 - gap) * 0.48f)
        .coerceIn(StageCoverFloor, ceiling)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = Space.step8),
    ) {
        Artwork(
            url = track.artworkUrl,
            shape = Radii.artworkShape,
            contentDescription = track.title,
            measure = true,
            iconSize = 64.dp,
            modifier = Modifier.size(cover),
        )
        Spacer(Modifier.width(gap))
        Column(
            // No weight: a weighted slot keeps its full share even when the column is narrower, so
            // the block would sit off-centre on a very wide window with all the slack on one side.
            modifier = Modifier
                .widthIn(max = StageWordsMax)
                .heightIn(min = min(cover, 420.dp)),
            verticalArrangement = Arrangement.Center,
        ) {
            StageWords(track = track, isPlaying = snapshot?.isPlaying == true, large = true)
            Spacer(Modifier.height(Space.step8))
            SeekBar(
                positionMs = snapshot?.positionMs ?: 0L,
                durationMs = snapshot?.durationMs ?: track.durationMs,
                enabled = snapshot?.canSeek ?: true,
                onSeek = controls.onSeek,
            )
            Spacer(Modifier.height(Space.step2))
            TransportBar(
                isPlaying = snapshot?.isPlaying == true,
                isBuffering = snapshot?.isBuffering == true,
                canSkipPrevious = snapshot?.canSkip != false,
                canSkipNext = snapshot?.canSkip != false,
                onPrevious = controls.onPrevious,
                onToggle = controls.onToggle,
                onNext = controls.onNext,
                playSize = 68.dp,
                skipSize = 44.dp,
                gap = Space.step6,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.step3))
            VolumeSlider(volume = volume, onVolume = controls.onVolume, railWidth = null, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.step8))
            UpNext(next = next, onOpenQueue = onOpenQueue)
        }
    }
}

/** A phone, or a window taller than it is wide: the cover on top, the song under it. */
@Composable
private fun TallStage(
    track: Track,
    snapshot: PlayerSnapshot?,
    volume: Float,
    next: Track?,
    controls: StageControls,
    onOpenQueue: () -> Unit,
    stageWidth: Dp,
    stageHeight: Dp,
    showVolume: Boolean,
    topInset: Dp,
) {
    val gutter = LocalChrome.current.gutter
    val column = min(stageWidth - gutter * 2, 520.dp)
    val cover = min(column, stageHeight * 0.44f).coerceAtLeast(160.dp)
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .width(column)
                .heightIn(min = stageHeight)
                .padding(top = topInset, bottom = Space.step6),
        ) {
            Artwork(
                url = track.artworkUrl,
                shape = Radii.artworkShape,
                contentDescription = track.title,
                measure = true,
                iconSize = 56.dp,
                modifier = Modifier
                    .size(cover)
                    .align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(Space.step8))
            StageWords(track = track, isPlaying = snapshot?.isPlaying == true, large = false)
            Spacer(Modifier.height(Space.step5))
            SeekBar(
                positionMs = snapshot?.positionMs ?: 0L,
                durationMs = snapshot?.durationMs ?: track.durationMs,
                enabled = snapshot?.canSeek ?: true,
                onSeek = controls.onSeek,
            )
            Spacer(Modifier.height(Space.step2))
            TransportBar(
                isPlaying = snapshot?.isPlaying == true,
                isBuffering = snapshot?.isBuffering == true,
                canSkipPrevious = snapshot?.canSkip != false,
                canSkipNext = snapshot?.canSkip != false,
                onPrevious = controls.onPrevious,
                onToggle = controls.onToggle,
                onNext = controls.onNext,
                playSize = 72.dp,
                skipSize = 44.dp,
                gap = Space.step8,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showVolume) {
                Spacer(Modifier.height(Space.step3))
                VolumeSlider(volume = volume, onVolume = controls.onVolume, railWidth = null, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(Space.step6))
            UpNext(next = next, onOpenQueue = onOpenQueue)
        }
    }
}

/** The song, set left like a headline, cross-fading when the song changes. */
@Composable
private fun StageWords(track: Track, isPlaying: Boolean, large: Boolean) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = track,
        contentKey = { it.key },
        transitionSpec = {
            if (reduced) {
                fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            } else {
                fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit())
            }
        },
        label = "stageWords",
    ) { shown ->
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = ValenColors.Text2)) {
                        append(if (isPlaying) "Playing from " else "Paused on ")
                    }
                    append(shown.provider.label)
                },
                style = MaterialTheme.typography.titleSmall,
                color = ValenColors.Text,
                maxLines = 1,
            )
            Spacer(Modifier.height(Space.step2))
            Text(
                text = shown.title,
                style = if (large) TrackType.title else TrackType.headline,
                color = ValenColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.step1))
            Text(
                text = listOfNotNull(shown.artist, shown.album?.takeIf { it.isNotBlank() && it != shown.title })
                    .joinToString("  ·  "),
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The way to the queue. When a song is waiting it is named, with its cover; when none is, the
 * button just says where it leads.
 */
@Composable
private fun UpNext(next: Track?, onOpenQueue: () -> Unit) {
    if (next == null) {
        QuietButton(label = "Queue", icon = Icons.AutoMirrored.Rounded.QueueMusic, onClick = onOpenQueue)
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .tap(onClick = onOpenQueue, shape = Radii.rowShape, rest = ValenColors.Fill)
            .padding(start = Space.step2, end = Space.step3),
    ) {
        ArtworkSquare(url = next.artworkUrl, size = 44.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        ) {
            Text(
                text = "Up next",
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 1,
            )
            Text(
                text = "${next.title}  ·  ${next.artist}",
                style = MaterialTheme.typography.titleSmall,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = ValenColors.Text2,
            modifier = Modifier.size(24.dp),
        )
    }
}
