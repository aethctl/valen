/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.recap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.Track
import app.valen.core.WeekRecap
import app.valen.ui.components.Artwork
import app.valen.ui.components.KineticText
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.QuietButton
import app.valen.ui.components.Tag
import app.valen.ui.components.drawDrip
import app.valen.ui.components.markerSwipe
import app.valen.ui.components.taped
import app.valen.ui.lyrics.Scribble
import app.valen.ui.lyrics.WordBox
import app.valen.ui.lyrics.scribbleInk
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.RecapType
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** One page's words and pictures, laid out on the poster's design width. */
@Composable
internal fun StoryPageContent(
    page: StoryPage,
    week: WeekRecap,
    coordinator: PlaybackCoordinator,
    sounding: Track?,
    onPlayAll: () -> Unit,
    onDone: () -> Unit,
) {
    when (page.kind) {
        StoryKind.Opening -> OpeningPage(week)
        StoryKind.Time -> TimePage(week)
        StoryKind.Artist -> ArtistPage(week)
        StoryKind.Artists -> ArtistsPage(week)
        StoryKind.Songs -> SongsPage(week, sounding)
        StoryKind.New -> NewPage(week)
        StoryKind.Hours -> HoursPage(week)
        StoryKind.Song -> SongPage(week)
        StoryKind.Outro -> OutroPage(week, onPlayAll, onDone)
    }
}

// ── The pages ──

/** The tag written on the wall, the story's name with a marker under it, its days, and the week's covers pasted up. */
@Composable
private fun OpeningPage(week: WeekRecap) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(0.5f))
        Tag(ink = ValenColors.Primary, mist = ValenColors.Primary, modifier = Modifier.width(132.dp))
        Spacer(Modifier.height(Space.step4))
        KineticText(text = "Your week", style = RecapType.title, color = ValenColors.Text, maxLines = 2, underline = ValenColors.Primary)
        Spacer(Modifier.height(Space.step2))
        Text(daysOf(week), style = MaterialTheme.typography.titleLarge, color = ValenColors.Text2, modifier = Modifier.risen(appear(700)))
        Spacer(Modifier.weight(1f))
        CoverFan(week.songs.take(3).map { it.track.artworkUrl }, from = 900)
        Spacer(Modifier.height(Space.step5))
        Text(
            "${songs(week.songCount)} by ${artists(week.artistCount)}. This is how they went.",
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text,
            modifier = Modifier.risen(appear(1500)),
        )
    }
}

/** The time, counted up big in the one colour, and the seven days poured down the wall. */
@Composable
private fun TimePage(week: WeekRecap) {
    val count by appear(250, Motion.StoryCountMs, Motion.Enter)
    val busiest = week.busiestDay
    Column(Modifier.fillMaxSize()) {
        Text("You listened for", style = MaterialTheme.typography.titleLarge, color = ValenColors.Text2, modifier = Modifier.risen(appear(0)))
        Text(
            listenedFor((week.totalMs * count).toLong()),
            style = RecapType.figure,
            color = ValenColors.Primary,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.height(Space.step2))
        Text(compared(week), style = MaterialTheme.typography.bodyLarge, color = ValenColors.Text, modifier = Modifier.risen(appear(1100)))
        Spacer(Modifier.height(Space.step8))
        WeekPaint(week, Modifier.fillMaxWidth().weight(1f))
        Spacer(Modifier.height(Space.step4))
        Row(modifier = Modifier.risen(appear(1500))) {
            Text("Most on ${weekdayName(week.firstDay + busiest)}", style = MaterialTheme.typography.titleLarge, color = ValenColors.Text)
            Text("  ·  " + listenedFor(week.days[busiest]), style = MaterialTheme.typography.titleLarge, color = ValenColors.Primary)
        }
    }
}

/** The artist of the week: their most played cover pasted up and taped, their name under it in big type. */
@Composable
private fun ArtistPage(week: WeekRecap) {
    val artist = week.artists.firstOrNull() ?: return
    val top = artist.songs.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        Text("Your artist of the week", style = MaterialTheme.typography.titleLarge, color = ValenColors.Text2, modifier = Modifier.risen(appear(0)))
        Spacer(Modifier.height(Space.step6))
        Pasted(artist.artworkUrl, size = 236.dp, seed = artist.name.hashCode(), delay = 150, measure = true)
        Spacer(Modifier.weight(1f))
        KineticText(text = artist.name, style = TrackType.display, color = ValenColors.Text, maxLines = 2, underline = ValenColors.Primary)
        Spacer(Modifier.height(Space.step2))
        Row(modifier = Modifier.risen(appear(900))) {
            Text(listenedFor(artist.ms), style = MaterialTheme.typography.titleLarge, color = ValenColors.Primary)
            Text("  ·  " + plays(artist.plays), style = MaterialTheme.typography.titleLarge, color = ValenColors.Text2)
        }
        if (top != null) {
            Spacer(Modifier.height(Space.step1))
            Text(
                "Played most: ${top.track.title}",
                style = MaterialTheme.typography.bodyLarge,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.risen(appear(1150)),
            )
        }
    }
}

/** The five most heard artists, each with a bar of their time; the pen circles the first. */
@Composable
private fun ArtistsPage(week: WeekRecap) {
    val most = week.artists.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
    Column(Modifier.fillMaxSize()) {
        KineticText(text = "Your top artists", style = MaterialTheme.typography.displaySmall, color = ValenColors.Text, maxLines = 2)
        Spacer(Modifier.height(Space.step5))
        week.artists.forEachIndexed { i, artist ->
            RankRow(
                rank = i,
                artwork = artist.artworkUrl,
                title = artist.name,
                detail = listenedFor(artist.ms) + "  ·  " + plays(artist.plays),
                share = artist.ms.toFloat() / most,
                delay = 250 + i * Motion.StoryStepMs,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "${artists(week.artistCount)} in all this week.",
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text2,
            modifier = Modifier.risen(appear(1100)),
        )
    }
}

/** The five most heard songs; the one playing under the page is marked like the playing row. */
@Composable
private fun SongsPage(week: WeekRecap, sounding: Track?) {
    Column(Modifier.fillMaxSize()) {
        KineticText(text = "Your top songs", style = MaterialTheme.typography.displaySmall, color = ValenColors.Text, maxLines = 2)
        Spacer(Modifier.height(Space.step5))
        week.songs.take(5).forEachIndexed { i, song ->
            RankRow(
                rank = i,
                artwork = song.track.artworkUrl,
                title = song.track.title,
                detail = song.track.artist,
                trailing = plays(song.plays),
                marked = song.track.key == sounding?.key,
                delay = 250 + i * Motion.StoryStepMs,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "${songs(week.songCount)} and ${plays(week.plays)} in all.",
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text2,
            modifier = Modifier.risen(appear(1100)),
        )
    }
}

/** Songs heard for the first time this week: how many, and the three played most, pasted up. */
@Composable
private fun NewPage(week: WeekRecap) {
    val fresh = week.newSongs.orEmpty()
    val count by appear(250, Motion.StoryCountMs, Motion.Enter)
    Column(Modifier.fillMaxSize()) {
        KineticText(text = "New to you", style = MaterialTheme.typography.displaySmall, color = ValenColors.Text)
        Spacer(Modifier.height(Space.step3))
        Text("${(week.newSongCount * count).toInt()}", style = RecapType.figure, color = ValenColors.Primary, maxLines = 1)
        Text(
            if (week.newSongCount == 1) "song you had never played before" else "songs you had never played before",
            style = MaterialTheme.typography.titleLarge,
            color = ValenColors.Text,
            modifier = Modifier.risen(appear(700)),
        )
        if (week.newArtistCount > 0) {
            Text(
                "and ${artists(week.newArtistCount)} new to you",
                style = MaterialTheme.typography.bodyLarge,
                color = ValenColors.Text2,
                modifier = Modifier.risen(appear(900)),
            )
        }
        Spacer(Modifier.weight(1f))
        CoverFan(fresh.take(3).map { it.track.artworkUrl }, from = 700)
        fresh.firstOrNull()?.let { first ->
            Spacer(Modifier.height(Space.step4))
            Text(
                "Played most of them: ${first.track.title}",
                style = MaterialTheme.typography.bodyLarge,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.risen(appear(1400)),
            )
        }
    }
}

/** The day as a clock of paint: a bar for each hour, the busiest circled by the pen. */
@Composable
private fun HoursPage(week: WeekRecap) {
    val hours = week.hours ?: return
    val peak = week.peakHour ?: return
    val part = partOfDay(hours)
    Column(Modifier.fillMaxSize()) {
        KineticText(text = "When you listen", style = MaterialTheme.typography.displaySmall, color = ValenColors.Text)
        Spacer(Modifier.weight(0.6f))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            HourDial(hours, peak, Modifier.size(340.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.risen(appear(900))) {
                Text(hourName(peak), style = MaterialTheme.typography.displayMedium, color = ValenColors.Primary)
                Text("your hour", style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Most of it ${part.words}, and most of all around ${hourName(peak)}.",
            style = MaterialTheme.typography.titleLarge,
            color = ValenColors.Text,
            modifier = Modifier.risen(appear(1500)),
        )
    }
}

/** The song of the week: its cover big, pasted and taped, its name in the stage's type, the pen round its plays. */
@Composable
private fun SongPage(week: WeekRecap) {
    val song = week.songs.firstOrNull() ?: return
    Column(Modifier.fillMaxSize()) {
        Text("Your song of the week", style = MaterialTheme.typography.titleLarge, color = ValenColors.Text2, modifier = Modifier.risen(appear(0)))
        Spacer(Modifier.height(Space.step6))
        Pasted(song.track.artworkUrl, size = 264.dp, seed = song.track.key.hashCode(), delay = 150, measure = true, shape = Radii.artworkShape)
        Spacer(Modifier.weight(1f))
        KineticText(text = song.track.title, style = TrackType.title, color = ValenColors.Text, maxLines = 2, underline = ValenColors.Primary)
        Spacer(Modifier.height(Space.step1))
        Text(
            song.track.artist,
            style = MaterialTheme.typography.titleLarge,
            color = ValenColors.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.risen(appear(700)),
        )
        Spacer(Modifier.height(Space.step4))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.risen(appear(950))) {
            val style = MaterialTheme.typography.titleLarge
            Text(
                plays(song.plays),
                style = style,
                color = ValenColors.Primary,
                modifier = Modifier.pen(Scribble.Loop, appear(1700, Motion.ScribbleLongMs, LinearEasing), song.track.key.hashCode(), ValenColors.Primary, style.fontSize),
            )
            Text("  ·  " + listenedFor(song.ms), style = style, color = ValenColors.Text2)
        }
    }
}

/** The end: the week's covers pasted up together, the sum of it, and the way to play them. */
@Composable
private fun OutroPage(week: WeekRecap, onPlayAll: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        KineticText(text = "That was your week", style = RecapType.title, color = ValenColors.Text, maxLines = 3, underline = ValenColors.Primary)
        Spacer(Modifier.height(Space.step3))
        Text(
            listenedFor(week.totalMs) + "  ·  " + songs(week.songCount) + "  ·  " + artists(week.artistCount),
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text2,
            modifier = Modifier.risen(appear(800)),
        )
        Spacer(Modifier.weight(1f))
        Collage(week.songs.take(5).map { it.track.artworkUrl }, Modifier.fillMaxWidth().height(232.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.step2), modifier = Modifier.risen(appear(1500))) {
            PrimaryButton(label = "Play these", icon = Icons.Rounded.PlayArrow, onClick = onPlayAll)
            QuietButton(label = "Done", onClick = onDone)
        }
    }
}

// ── Pieces ──

/** A ranked row: the rank in the app's voice, the cover, the name and its line, and a bar or a count. */
@Composable
private fun RankRow(
    rank: Int,
    artwork: String?,
    title: String,
    detail: String,
    delay: Int,
    share: Float? = null,
    trailing: String? = null,
    marked: Boolean = false,
) {
    val shown = appear(delay)
    val bar by appear(delay + 200, Motion.EnterMs * 2, Motion.Enter)
    // The playing row is swiped once it has landed, as a list marks the song that plays.
    var swiped by remember { mutableStateOf(false) }
    LaunchedEffect(marked) {
        if (!marked) {
            swiped = false
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay((delay + Motion.EnterMs).toLong())
        swiped = true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(66.dp).risen(shown),
    ) {
        val number = MaterialTheme.typography.headlineLarge
        Box(Modifier.width(36.dp)) {
            Text(
                "${rank + 1}",
                style = number,
                color = if (rank == 0) ValenColors.Primary else ValenColors.Text3,
                modifier = if (rank == 0) {
                    Modifier.pen(Scribble.Loop, appear(delay + 900, Motion.ScribbleLongMs, LinearEasing), title.hashCode(), ValenColors.Primary, number.fontSize)
                } else {
                    Modifier
                },
            )
        }
        Artwork(url = artwork, shape = Radii.coverShape, modifier = Modifier.size(50.dp))
        Column(Modifier.weight(1f).padding(horizontal = Space.step3)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.markerSwipe(active = swiped, color = ValenColors.Container, seed = title.hashCode()),
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (share != null) {
                Spacer(Modifier.height(Space.step1))
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .drawWithCache {
                            val r = CornerRadius(size.height / 2f, size.height / 2f)
                            onDrawBehind {
                                drawRoundRect(ValenColors.Fill, cornerRadius = r)
                                drawRoundRect(
                                    if (rank == 0) ValenColors.Primary else ValenColors.OnContainer.copy(alpha = 0.55f),
                                    size = Size(size.width * share * bar, size.height),
                                    cornerRadius = r,
                                )
                            }
                        },
                )
            }
        }
        if (trailing != null) Text(trailing, style = timeStyle(large = true), color = ValenColors.Text2)
    }
}

/** A cover pasted to the wall by hand: slapped down from big and turned, then taped at its top corners. */
@Composable
private fun Pasted(
    url: String?,
    size: Dp,
    seed: Int,
    delay: Int,
    measure: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = Radii.cardShape,
    modifier: Modifier = Modifier,
) {
    val rest = remember(seed) { (Random(seed).nextFloat() * 2f - 1f) * 3f }
    Box(
        modifier = modifier
            .size(size)
            .slapped(appear(delay, Motion.KineticMs, Motion.Kinetic), turn = if (rest >= 0f) 7f else -7f, rest = rest)
            .taped(url),
    ) {
        Artwork(url = url, shape = shape, measure = measure, modifier = Modifier.fillMaxSize())
    }
}

/** Up to three covers fanned like flyers, slapped up one after another, the middle one taped. */
@Composable
private fun CoverFan(urls: List<String?>, from: Int) {
    if (urls.isEmpty()) return
    val cover = 156.dp
    // Where each lands across the page, how far it is turned, and how high it sits.
    val spots = when (urls.size) {
        1 -> listOf(Triple(0.dp, -3f, 0.dp))
        2 -> listOf(Triple(0.dp, -6f, 10.dp), Triple(120.dp, 5f, 0.dp))
        else -> listOf(Triple(0.dp, -8f, 18.dp), Triple(98.dp, 3f, 0.dp), Triple(196.dp, 9f, 22.dp))
    }
    Box(Modifier.fillMaxWidth().height(cover + 28.dp)) {
        urls.zip(spots).forEachIndexed { i, (url, spot) ->
            val (x, turn, y) = spot
            Box(
                Modifier
                    .offset(x = x, y = y)
                    .size(cover)
                    .slapped(appear(from + i * 160, Motion.KineticMs, Motion.Kinetic), turn = turn * 1.6f, rest = turn)
                    .then(if (i == 1 || urls.size == 1) Modifier.taped(url) else Modifier),
            ) {
                Artwork(url = url, shape = Radii.cardShape, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Up to five covers pasted over each other on the wall, the week's first in front and taped. */
@Composable
private fun Collage(urls: List<String?>, modifier: Modifier = Modifier) {
    // Across (a share of the width), down (a share of the height left under it), size (a share of
    // the width) and the turn at rest, from the back to the front.
    val spots = listOf(
        floatArrayOf(0.60f, 0.00f, 0.36f, 8f),
        floatArrayOf(0.00f, 0.95f, 0.34f, -9f),
        floatArrayOf(0.66f, 1.00f, 0.32f, 5f),
        floatArrayOf(0.03f, 0.05f, 0.40f, -5f),
        floatArrayOf(0.27f, 0.45f, 0.48f, 2f),
    )
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // The first song is drawn last, so it lands on top of the others.
        val order = urls.indices.reversed().toList()
        order.forEachIndexed { step, i ->
            val spot = spots[(spots.size - urls.size + step).coerceIn(0, spots.lastIndex)]
            val side = maxWidth * spot[2]
            Box(
                Modifier
                    .offset(x = maxWidth * spot[0], y = (maxHeight - side).coerceAtLeast(0.dp) * spot[1])
                    .size(side)
                    .slapped(appear(400 + step * 150, Motion.KineticMs, Motion.Kinetic), turn = spot[3] * 1.8f, rest = spot[3])
                    .then(if (i == 0) Modifier.taped(urls[i]) else Modifier),
            ) {
                Artwork(url = urls[i], shape = Radii.cardShape, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * The seven days as paint poured down the wall from a marker line: a column for each day, as long
 * as the time on it, with paint running on from its end. The busiest day is in the one colour.
 */
@Composable
private fun WeekPaint(week: WeekRecap, modifier: Modifier = Modifier) {
    val rule by appear(150, Motion.MarkerMs, Motion.Standard)
    val pour by appear(0, 420 + 7 * Motion.StoryStepMs + 900, LinearEasing)
    val measurer = rememberTextMeasurer()
    val label = timeStyle(large = true)
    val busiest = week.busiestDay
    val most = week.days.maxOrNull()?.coerceAtLeast(1) ?: 1
    val ink = ValenColors.Primary
    val calm = ValenColors.Container
    val text3 = ValenColors.Text3
    val text = ValenColors.Text
    val total = (420 + 7 * Motion.StoryStepMs + 900).toFloat()
    Canvas(modifier) {
        val labelH = 20.dp.toPx()
        val ruleY = labelH + 8.dp.toPx()
        val col = size.width / 7f
        val stroke = 24.dp.toPx().coerceAtMost(col * 0.6f)
        val room = size.height - ruleY - 34.dp.toPx()
        // The marker line the paint hangs from, drawn on left to right with a slight sag.
        val line = Path().apply {
            moveTo(-2.dp.toPx(), ruleY + 1.dp.toPx())
            for (i in 1..16) {
                val t = i / 16f
                lineTo(size.width * t, ruleY + sin(t * PI.toFloat()) * 2.5f.dp.toPx() + sin(t * 9f) * 0.8f.dp.toPx())
            }
        }
        val measure = PathMeasure().apply { setPath(line, false) }
        val piece = Path()
        measure.getSegment(0f, measure.length * rule, piece, true)
        drawPath(piece, ink, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (i in 0 until 7) {
            val cx = col * (i + 0.5f)
            val name = weekdayName(week.firstDay + i).take(1)
            val laid = measurer.measure(name, label)
            drawText(laid, color = if (i == busiest) text else text3, topLeft = Offset(cx - laid.size.width / 2f, 0f))
            val ms = week.days[i]
            val local = ((pour * total - (420 + i * Motion.StoryStepMs)) / 900f).coerceIn(0f, 1f)
            val grow = Motion.Enter.transform(local)
            val color = if (i == busiest) ink else calm
            if (ms <= 0) {
                if (grow > 0f) drawCircle(color.copy(alpha = 0.6f), radius = 3.dp.toPx() * grow, center = Offset(cx, ruleY + 8.dp.toPx()))
                continue
            }
            val length = (room * ms / most).coerceAtLeast(8.dp.toPx()) * grow
            drawRoundRect(
                color,
                topLeft = Offset(cx - stroke / 2f, ruleY - stroke / 2f),
                size = Size(stroke, length + stroke / 2f),
                cornerRadius = CornerRadius(stroke / 2f, stroke / 2f),
            )
            // Paint running on from the end: longer for the longer days.
            val run = (6.dp.toPx() + 18.dp.toPx() * ms / most) * Motion.Enter.transform(((local - 0.55f) / 0.45f).coerceIn(0f, 1f))
            if (run > 0f) drawDrip(Offset(cx + stroke * 0.12f, ruleY + length - stroke * 0.1f), run, stroke * 0.46f, color)
            if (i == busiest && local >= 1f) {
                val laidTime = measurer.measure(shortTime(ms), label)
                drawText(laidTime, color = ink, topLeft = Offset(cx - laidTime.size.width / 2f, ruleY + length + run + 6.dp.toPx()))
            }
        }
    }
}

/**
 * The day as a dial: midnight at the top, an hour a spoke, each spoke as long as the time in it,
 * sweeping round from midnight as the page lands; the pen loops the busiest hour's end.
 */
@Composable
private fun HourDial(hours: List<Long>, peak: Int, modifier: Modifier = Modifier) {
    val sweep by appear(250, 24 * 35 + 700, LinearEasing)
    val loop = appear(250 + 24 * 35 + 700, Motion.ScribbleLongMs, LinearEasing)
    val measurer = rememberTextMeasurer()
    val label = timeStyle(large = true)
    val most = hours.maxOrNull()?.coerceAtLeast(1) ?: 1
    val ink = ValenColors.Primary
    val calm = ValenColors.Container
    val guide = ValenColors.Hairline
    val track = ValenColors.Hover
    val text3 = ValenColors.Text3
    Box(modifier.drawWithCache {
        val c = Offset(size.width / 2f, size.height / 2f)
        val outer = size.minDimension / 2f - 24.dp.toPx()
        val inner = outer * 0.46f
        val pen = 10.dp.toPx()
        val tipOf = { h: Int, reach: Float ->
            val a = (h / 24f) * 2f * PI.toFloat() - PI.toFloat() / 2f
            Offset(c.x + cos(a) * reach, c.y + sin(a) * reach)
        }
        val peakReach = inner + (outer - inner) * (0.12f + 0.88f * hours[peak] / most)
        val tip = tipOf(peak, peakReach)
        val em = 22.dp.toPx()
        val ink2 = scribbleInk(Scribble.Loop, WordBox(tip.x - 0.35f * em, tip.x + 0.35f * em, tip.y + 0.36f * em, em), peak * 31 + 7, density)
        val marks = listOf(0 to "12 am", 6 to "6 am", 12 to "12 pm", 18 to "6 pm").map { (h, words) -> h to measurer.measure(words, label) }
        onDrawBehind {
            drawCircle(guide, radius = inner - 8.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
            for (h in 0 until 24) {
                val local = ((sweep * (24 * 35 + 700) - h * 35) / 700f).coerceIn(0f, 1f)
                val grow = Motion.Enter.transform(local)
                // Each hour's track, the whole way out, so the dial reads as a clock face.
                drawLine(track, start = tipOf(h, inner), end = tipOf(h, outer), strokeWidth = pen, cap = StrokeCap.Round)
                if (grow <= 0f) continue
                val share = 0.12f + 0.88f * hours[h] / most
                val reach = inner + (outer - inner) * share * grow
                drawLine(
                    if (h == peak) ink else calm,
                    start = tipOf(h, inner),
                    end = tipOf(h, reach.coerceAtLeast(inner + 0.5f)),
                    strokeWidth = pen,
                    cap = StrokeCap.Round,
                )
            }
            for ((h, laid) in marks) {
                val at = tipOf(h, outer + 15.dp.toPx())
                drawText(laid, color = text3, topLeft = Offset(at.x - laid.size.width / 2f, at.y - laid.size.height / 2f))
            }
            ink2.draw(this, loop.value, ink)
        }
    })
}

// ── Motion on the poster ──

/**
 * 0 → 1 once, [delay] ms after the page lands, over [duration]: what each item of a page rides
 * on. A page composed again (going back to it) runs it again. At once under reduced motion.
 */
@Composable
private fun appear(delay: Int, duration: Int = Motion.EnterMs + 120, easing: Easing = Motion.Enter): State<Float> {
    val reduced = LocalReducedMotion.current
    val value = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) value.animateTo(1f, tween(duration, delayMillis = delay, easing = easing))
    }
    return value.asState()
}

/** Rises into place: fades up from a little below. Read in the draw, so it never recomposes. */
private fun Modifier.risen(p: State<Float>): Modifier = graphicsLayer {
    val v = p.value
    alpha = v.coerceIn(0f, 1f)
    translationY = (1f - v) * Motion.RiseDistance.toPx()
}

/** Slapped down like a sticker: from big and turned by [turn] to flat at [rest], with a bounce. */
private fun Modifier.slapped(p: State<Float>, turn: Float, rest: Float): Modifier = graphicsLayer {
    val v = p.value
    alpha = (v * 2.5f).coerceIn(0f, 1f)
    val s = 1.25f - 0.25f * v
    scaleX = s
    scaleY = s
    rotationZ = rest + turn * (1f - v)
}

/**
 * The pen of the lyrics, on the poster: [kind] drawn over this one line of text at [progress],
 * sized by its letters ([em]) and seeded so it is drawn the same way each time.
 */
private fun Modifier.pen(kind: Scribble, progress: State<Float>, seed: Int, color: Color, em: TextUnit): Modifier = drawWithCache {
    val size = em.toPx()
    val ink = scribbleInk(kind, WordBox(0f, this.size.width, this.size.height * 0.78f, size), seed, density)
    onDrawWithContent {
        drawContent()
        ink.draw(this, progress.value, color)
    }
}

// ── Words ──

private val Months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val Weekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** Year, month (1–12) and day of an epoch day, by the civil-from-days method. */
private fun civil(epochDay: Long): Triple<Long, Int, Int> {
    val z = epochDay + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return Triple(y, m, d)
}

/** "10 – 16 November", or "28 October – 3 November" across two months. */
internal fun daysOf(week: WeekRecap): String {
    val (_, m1, d1) = civil(week.firstDay)
    val (_, m2, d2) = civil(week.lastDay)
    return if (m1 == m2) "$d1 – $d2 ${Months[m2 - 1]}" else "$d1 ${Months[m1 - 1]} – $d2 ${Months[m2 - 1]}"
}

/** "Saturday", for an epoch day (1970-01-01 was a Thursday). */
private fun weekdayName(epochDay: Long): String = Weekdays[Math.floorMod(epochDay + 3, 7L).toInt()]

/** "11 pm", "12 am", "3 pm". */
private fun hourName(hour: Int): String = when {
    hour == 0 -> "12 am"
    hour < 12 -> "$hour am"
    hour == 12 -> "12 pm"
    else -> "${hour - 12} pm"
}

private enum class PartOfDay(val words: String, val hours: IntRange) {
    Morning("in the morning", 5..11),
    Afternoon("in the afternoon", 12..16),
    Evening("in the evening", 17..21),
    Night("at night", 22..28),
}

/** The part of the day with the most listening (night runs past midnight to four). */
private fun partOfDay(hours: List<Long>): PartOfDay =
    PartOfDay.entries.maxByOrNull { part -> part.hours.sumOf { hours[it % 24] } } ?: PartOfDay.Evening

/** How the week compares, in one plain sentence. */
private fun compared(week: WeekRecap): String {
    val before = week.previousMs ?: return "About ${listenedFor(week.totalMs / 7)} a day."
    val diff = week.totalMs - before
    return when {
        before <= 0 -> "The week before, you played nothing here."
        kotlin.math.abs(diff) < 10 * 60_000 -> "About the same as the week before."
        diff > 0 -> "${listenedFor(diff)} more than the week before."
        else -> "${listenedFor(-diff)} less than the week before."
    }
}

/** "4h12", "38m": a day's time where room is short. */
private fun shortTime(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}" else "${minutes}m"
}

private fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"
private fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"
private fun artists(n: Int) = if (n == 1) "1 artist" else "$n artists"
