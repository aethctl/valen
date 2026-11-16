/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.sound

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.valen.ui.LocalAppScope
import app.valen.ui.components.PrimaryButton
import app.valen.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.EQ_BANDS
import app.valen.core.EffectSupport
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistManager
import app.valen.core.SoundEffects
import app.valen.core.SoundNote
import app.valen.core.SoundPresets
import app.valen.core.Track
import app.valen.ui.components.Artwork
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.IconTap
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PageTitle
import app.valen.ui.components.PageTop
import app.valen.ui.components.QuietButton
import app.valen.ui.components.ScrollPage
import app.valen.ui.components.Toggle
import app.valen.ui.components.pasted
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.rememberPageValue
import app.valen.ui.components.taped
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import app.valen.ui.util.dayText
import app.valen.ui.util.formatTime
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** The tabs of the settings page, in the order its pages turn. */
private val Tabs = listOf("Presets", "EQ", "Time", "Space", "Tone")

/**
 * Sound, as a notebook. On the left page the song is pasted in with its few facts, and under it
 * the notebook keeps notes of what is done to the sound — written as each setting settles, struck
 * out and rewritten as it changes. On the right page, behind index tabs, are the settings
 * themselves: presets as stickers, the equalizer drawn as a line, time on two rulers, space and
 * tone on pencil lines. A phone stacks the two pages.
 *
 * The settings are the app's, for every song a player can take them for; a player that cannot
 * (Spotify) is named in the notes, and the settings wait for the next song that can.
 */
@Composable
fun SoundScreen(
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
    playlists: PlaylistManager? = null,
    onClose: (() -> Unit)? = null,
) {
    val scope = LocalAppScope.current
    val sound by coordinator.sound.collectAsState()
    val snapshot by coordinator.nowPlaying.collectAsState()
    val track = snapshot?.track
    val support = coordinator.effectSupport(track)
    // The playing song's service keeps its own sound (Spotify): the notebook is shut for it.
    val locked = track != null && support is EffectSupport.None
    val dim by animateFloatAsState(if (locked) LockedInk else 1f, Motion.standard(), label = "notebookDim")

    // The notebook writes a setting down once it has held still: a slider being dragged is not a
    // note yet, and a burst of changes is one note, not ten.
    var settled by remember { mutableStateOf(sound) }
    LaunchedEffect(sound) {
        delay(Motion.NoteSettleMs)
        settled = sound
    }
    val notes = notesFor(settled, track, support)

    /** A change from a control: effects come on, and a preset becomes "changed". */
    fun change(block: (SoundEffects) -> SoundEffects) {
        val next = block(sound)
        coordinator.setEffects(next.copy(on = true, edited = next.preset != null))
    }

    ScrollPage(key = "sound", modifier = modifier.fillMaxWidth()) {
        ContentColumn {
            PageTop()
            PageTitle(
                title = "Sound",
                leading = onClose?.let { close ->
                    { IconTap(icon = Icons.Rounded.Close, label = "Close sound", onClick = close, tint = ValenColors.Text) }
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.graphicsLayer { alpha = dim }) {
                        Text(
                            when {
                                locked -> "Off for ${track?.provider?.label}"
                                sound.on -> "On"
                                else -> "Off"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = ValenColors.Text2,
                        )
                        // Shown off while it cannot apply; the setting itself is kept for the next song.
                        Toggle(checked = sound.on && !locked, onChange = { if (!locked) coordinator.setEffects(sound.copy(on = it)) }, label = "Effects")
                    }
                },
            )
            Spacer(Modifier.height(Space.step6))
            Box(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth().graphicsLayer { alpha = dim }) {
                val spread = maxWidth >= 860.dp
                val songPage: @Composable (Modifier) -> Unit = { m ->
                    SongPage(track = track, notes = notes, dimmed = !sound.on, coordinator = coordinator, playlists = playlists, modifier = m)
                }
                val settingsPage: @Composable (Modifier) -> Unit = { m ->
                    SettingsPage(sound = sound, wide = spread, onChange = ::change, onPreset = { coordinator.usePreset(it) }, modifier = m)
                }
                if (spread) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        songPage(Modifier.weight(1f).fillMaxHeight())
                        Spiral(vertical = true, modifier = Modifier.width(30.dp).fillMaxHeight())
                        settingsPage(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    Column(Modifier.fillMaxWidth()) {
                        Spiral(vertical = false, modifier = Modifier.fillMaxWidth().height(20.dp))
                        songPage(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Space.step4))
                        settingsPage(Modifier.fillMaxWidth())
                    }
                }
            }
            if (locked && track != null) {
                // Nothing under the note can be changed while it is up.
                Box(Modifier.matchParentSize().swallowInput())
                LockedNote(
                    track = track,
                    onPlayWithEffects = { scope.launch { coordinator.playWithEffects(track) } },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = Space.step16),
                )
            }
            }
        }
        PageBottom()
    }
}

/** How faint the notebook goes while the playing song cannot take effects. */
private const val LockedInk = 0.3f

/** Takes every press and drag before anything under it sees one. */
private fun Modifier.swallowInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/**
 * The note slapped over the notebook while a song from a service that keeps its own sound plays:
 * a sheet of the one colour, taped on, a little crooked, saying why nothing here applies, what
 * happens to the settings, and the one thing that can be done — the same song from a service that
 * takes effects, from the same place.
 */
@Composable
private fun LockedNote(track: Track, onPlayWithEffects: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val slap = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) slap.animateTo(1f, tween(Motion.KineticMs, easing = Motion.Kinetic)) }
    var looking by remember(track.key) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .widthIn(max = 440.dp)
            .padding(horizontal = Space.step4)
            .graphicsLayer {
                val p = slap.value
                alpha = (p * 2.5f).coerceIn(0f, 1f)
                val s = 1.3f - 0.3f * p
                scaleX = s
                scaleY = s
                rotationZ = -1.5f - 5f * (1f - p)
            }
            .taped(track.key)
            .clip(Radii.cardShape)
            .background(ValenColors.Container)
            .semantics(mergeDescendants = false) { contentDescription = "Effects are off for this song" }
            .padding(Space.step6),
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, tint = ValenColors.OnContainer, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(Space.step3))
        Text(
            "${track.provider.label} plays without effects",
            style = MaterialTheme.typography.headlineSmall,
            color = ValenColors.OnContainer,
        )
        Spacer(Modifier.height(Space.step2))
        Text(
            "${track.provider.label} locks its sound, so Valen cannot change how it plays. Your settings are kept, and they come back on the next YouTube Music or SoundCloud song.",
            style = MaterialTheme.typography.bodyMedium,
            color = ValenColors.OnContainer.copy(alpha = 0.8f),
        )
        Spacer(Modifier.height(Space.step5))
        PrimaryButton(
            label = if (looking) "Looking for it…" else "Play it with effects",
            icon = Icons.Rounded.GraphicEq,
            enabled = !looking,
            onClick = {
                looking = true
                onPlayWithEffects()
            },
        )
    }
}

/** What the notebook says: its heading, a player that cannot take effects, then each effect. */
private fun notesFor(sound: SoundEffects, track: Track?, support: EffectSupport?): List<SoundNote> = buildList {
    val preset = SoundPresets.byId(sound.preset)
    add(
        when {
            !sound.on -> SoundNote(TITLE, "Effects off", "the song plays as recorded")
            sound.isFlat -> SoundNote(TITLE, "As recorded", "nothing changed yet")
            preset != null && sound.edited -> SoundNote(TITLE, preset.name, "changed by hand")
            preset != null -> SoundNote(TITLE, preset.name, preset.words)
            else -> SoundNote(TITLE, "My sound", "set by hand")
        },
    )
    if (track != null && support is EffectSupport.None) {
        add(SoundNote("locked", "${track.provider.label} keeps its own sound", "effects play on YouTube Music and SoundCloud"))
    }
    if (!sound.isFlat || !sound.on) addAll(sound.normalized().notes())
}

// ── The left page: the song, and the notes ──

@Composable
private fun SongPage(
    track: Track?,
    notes: List<SoundNote>,
    dimmed: Boolean,
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager?,
    modifier: Modifier,
) {
    val log by coordinator.stats.log.collectAsState()
    val kept = playlists?.library?.playlists?.collectAsState()?.value.orEmpty()
    val compact = LocalChrome.current.compact
    val cover = if (compact) 104.dp else 120.dp
    Column(modifier.ruledPage(top = RuleGap)) {
        // The page's head: the date, as a diary has it.
        Box(Modifier.fillMaxWidth().height(RuleGap * 2)) {
            Text(
                dayText(),
                style = MaterialTheme.typography.labelLarge,
                color = ValenColors.Text3,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = Space.step5, bottom = 5.dp),
            )
        }
        // The song, pasted in over the margin, and what is known about it.
        Row(Modifier.fillMaxWidth().height(RuleGap * 5).padding(start = Space.step5, end = Space.step4)) {
            val source = rememberInteraction()
            Artwork(
                url = track?.artworkUrl,
                shape = Radii.coverShape,
                contentDescription = null,
                iconSize = 30.dp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(cover)
                    .pasted(seed = (track?.key ?: "none").hashCode(), source = source)
                    .taped(track?.key),
            )
            Column(Modifier.weight(1f).padding(start = Space.step5, top = 2.dp)) {
                Text(
                    track?.title ?: "Nothing playing",
                    style = MaterialTheme.typography.titleLarge,
                    color = ValenColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track?.artist ?: "Effects wait for the next song",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ValenColors.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space.step2))
                if (track != null) {
                    val heard = log.songs[track.key]
                    val liked = kept.firstOrNull { it.isLiked }?.tracks?.any { it.key == track.key } == true
                    Fact("on", track.provider.label)
                    if (track.durationMs > 0) Fact("length", formatTime(track.durationMs))
                    track.album?.takeIf { it.isNotBlank() && it != track.title }?.let { Fact("from", it) }
                    if (heard != null && heard.plays > 0) {
                        Fact("played", if (heard.plays == 1) "once" else "${heard.plays} times")
                    }
                    if (liked) Fact("kept", "in Liked songs")
                }
            }
        }
        Spacer(Modifier.height(RuleGap))
        Notes(notes = notes, dimmed = dimmed)
        Spacer(Modifier.height(RuleGap))
    }
}

/** One fact about the song: a quiet word and its value, the way a field note lists them. */
@Composable
private fun Fact(label: String, value: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ValenColors.Text3, modifier = Modifier.width(52.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, color = ValenColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── The right page: the settings, behind tabs ──

@Composable
private fun SettingsPage(
    sound: SoundEffects,
    wide: Boolean,
    onChange: ((SoundEffects) -> SoundEffects) -> Unit,
    onPreset: (app.valen.core.SoundPreset) -> Unit,
    modifier: Modifier,
) {
    var tab by rememberPageValue("sound.tab", 0)
    Column(modifier) {
        NotebookTabs(Tabs, selected = tab, onSelect = { tab = it }, even = !wide)
        // Side by side, the settings page takes the height the notes page has under the tabs.
        PageTurn(target = tab, order = { it }, modifier = Modifier.fillMaxWidth().then(if (wide) Modifier.weight(1f) else Modifier)) { shown ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .let { if (wide) it.fillMaxHeight() else it }
                    .dotPage()
                    .padding(Space.step5),
            ) {
                when (shown) {
                    0 -> PresetsTab(sound, wide, onPreset)
                    1 -> EqTab(sound, onChange)
                    2 -> TimeTab(sound, onChange)
                    3 -> SpaceTab(sound, onChange)
                    else -> ToneTab(sound, onChange)
                }
            }
        }
    }
}

@Composable
private fun PresetsTab(sound: SoundEffects, wide: Boolean, onPreset: (app.valen.core.SoundPreset) -> Unit) {
    val columns = if (wide) 3 else 2
    Column(verticalArrangement = Arrangement.spacedBy(Space.step3)) {
        SoundPresets.all.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.step3)) {
                row.forEach { preset ->
                    PresetSticker(
                        name = preset.name,
                        words = preset.words,
                        icon = presetIcon(preset.id),
                        chosen = sound.on && (sound.preset == preset.id || (preset.id == "flat" && sound.isFlat && sound.preset == null)),
                        seed = preset.id.hashCode(),
                        onClick = { onPreset(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun EqTab(sound: SoundEffects, onChange: ((SoundEffects) -> SoundEffects) -> Unit) {
    EqSketch(effects = sound, onBand = { i, g -> onChange { s -> s.copy(bands = s.normalized().bands.toMutableList().also { it[i] = g }) } })
    Spacer(Modifier.height(Space.step2))
    Text(
        "Drag a band up or down. Double tap a band to flatten it.",
        style = MaterialTheme.typography.bodySmall,
        color = ValenColors.Text2,
    )
    Spacer(Modifier.height(Space.step4))
    Row(verticalAlignment = Alignment.CenterVertically) {
        QuietButton(
            label = "Flatten",
            small = true,
            enabled = sound.normalized().bands.any { abs(it) >= 0.05f },
            onClick = { onChange { it.copy(bands = SoundEffects.FLAT_BANDS) } },
        )
        Spacer(Modifier.width(Space.step3))
        Text(
            "${EQ_BANDS.size} bands, 31 Hz to 16 kHz",
            style = MaterialTheme.typography.labelMedium,
            color = ValenColors.Text3,
        )
    }
}

@Composable
private fun TimeTab(sound: SoundEffects, onChange: ((SoundEffects) -> SoundEffects) -> Unit) {
    val n = sound.normalized()
    Big(
        value = "${n.speed.fixed2()}×",
        words = when {
            abs(n.speed - 1f) < 0.005f -> "as recorded"
            n.speed < 1f -> "slower"
            else -> "faster"
        },
    )
    Ruler(
        value = n.speed,
        onValue = { v -> onChange { it.copy(speed = v) } },
        range = 0.5f..2f,
        step = 0.01f,
        mid = 5,
        major = 25,
        tick = { "${it.fixed2().trimEnd('0').trimEnd('.')}×" },
        label = "Speed",
        display = { "${it.fixed2()}×" },
        rest = 1f,
    )
    Spacer(Modifier.height(Space.step6))
    val pitch = n.heardPitch
    Big(
        value = semitones(pitch),
        words = when {
            n.vinyl -> "follows the speed"
            abs(pitch) < 0.05f -> "as recorded"
            pitch < 0f -> "deeper"
            else -> "higher"
        },
    )
    Ruler(
        value = if (n.vinyl) pitch.coerceIn(-12f, 12f) else n.pitch,
        onValue = { v -> onChange { it.copy(pitch = v) } },
        range = -12f..12f,
        step = 0.5f,
        mid = 2,
        major = 4,
        tick = { if (it > 0f) "+${it.roundToInt()}" else if (it < 0f) "−${(-it).roundToInt()}" else "0" },
        label = "Pitch",
        display = { semitones(it) },
        rest = 0f,
        spacing = 11.dp,
        enabled = !n.vinyl,
    )
    Spacer(Modifier.height(Space.step5))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Pitch follows speed", style = MaterialTheme.typography.titleSmall, color = ValenColors.Text)
            Text("Like a turntable: slower is deeper, faster is higher.", style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2)
        }
        Toggle(checked = n.vinyl, onChange = { on -> onChange { it.copy(vinyl = on) } }, label = "Pitch follows speed")
    }
    if (abs(n.speed - 1f) >= 0.005f || abs(n.pitch) >= 0.05f) {
        Spacer(Modifier.height(Space.step3))
        QuietButton(label = "Back to 1×", small = true, onClick = { onChange { it.copy(speed = 1f, pitch = 0f) } })
    }
}

@Composable
private fun SpaceTab(sound: SoundEffects, onChange: ((SoundEffects) -> SoundEffects) -> Unit) {
    val n = sound.normalized()
    Section("Reverb")
    PencilSlider("Amount", n.reverb, { v -> onChange { it.copy(reverb = v) } }, 0f..1f, ::percent, step = 0.01f)
    PencilSlider("Room", n.room, { v -> onChange { it.copy(room = v) } }, 0f..1f, { SoundEffects.roomName(it).replaceFirstChar { c -> c.uppercase() } }, rest = 0.5f, step = 0.01f, enabled = n.reverb > 0f)
    PencilSlider("Tone", n.brightness, { v -> onChange { it.copy(brightness = v) } }, 0f..1f, { if (it < 0.3f) "Dark" else if (it > 0.7f) "Bright" else "Even" }, rest = 0.5f, step = 0.01f, enabled = n.reverb > 0f)
    Section("Echo")
    PencilSlider("Amount", n.echo, { v -> onChange { it.copy(echo = v) } }, 0f..1f, ::percent, step = 0.01f)
    PencilSlider("Time", n.echoMs, { v -> onChange { it.copy(echoMs = v) } }, 60f..1200f, { "${it.roundToInt()} ms" }, rest = 320f, step = 10f, enabled = n.echo > 0f)
    PencilSlider("Repeats", n.echoRepeats, { v -> onChange { it.copy(echoRepeats = v) } }, 0f..0.85f, ::percent, rest = 0.35f, step = 0.01f, enabled = n.echo > 0f)
    Section("Width")
    PencilSlider(
        "Stereo", n.width, { v -> onChange { it.copy(width = v) } }, 0f..2f,
        { if (it < 0.02f) "Mono" else if (abs(it - 1f) < 0.01f) "As recorded" else percent(it) },
        rest = 1f, step = 0.01f,
    )
    Section("8D")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Turn around your head", style = MaterialTheme.typography.titleSmall, color = ValenColors.Text, modifier = Modifier.weight(1f))
        Toggle(checked = n.rotate > 0f, onChange = { on -> onChange { it.copy(rotate = if (on) 10f else 0f) } }, label = "8D")
    }
    PencilSlider(
        "One turn every", if (n.rotate > 0f) n.rotate else 10f, { v -> onChange { it.copy(rotate = v) } }, 2f..30f,
        { "${it.roundToInt()} s" }, rest = 10f, step = 1f, enabled = n.rotate > 0f,
    )
}

@Composable
private fun ToneTab(sound: SoundEffects, onChange: ((SoundEffects) -> SoundEffects) -> Unit) {
    val n = sound.normalized()
    Section("Shelves")
    PencilSlider("Bass", n.bass, { v -> onChange { it.copy(bass = v) } }, -12f..12f, ::decibels, rest = 0f, step = 0.5f)
    PencilSlider("Treble", n.treble, { v -> onChange { it.copy(treble = v) } }, -12f..12f, ::decibels, rest = 0f, step = 0.5f)
    Section("Cuts")
    PencilSlider(
        "Low cut", n.lowCut, { v -> onChange { it.copy(lowCut = v) } }, 20f..500f,
        { if (it == 0f) "Off" else SoundEffects.hertz(it) }, rest = 0f, log = true, off = 0f,
    )
    PencilSlider(
        "Muffle", n.highCut, { v -> onChange { it.copy(highCut = v) } }, 500f..20000f,
        { if (it == 0f) "Off" else "above ${SoundEffects.hertz(it)}" }, rest = 0f, log = true, off = 0f, reversed = true,
    )
    Section("Level")
    PencilSlider("Even out", n.evenOut, { v -> onChange { it.copy(evenOut = v) } }, 0f..1f, ::percent, step = 0.01f)
    PencilSlider("Preamp", n.preamp, { v -> onChange { it.copy(preamp = v) } }, -12f..12f, ::decibels, rest = 0f, step = 0.5f)
}

/** A section of a settings page: its name with a dab of the marker before it. */
@Composable
private fun Section(title: String) {
    val primary = ValenColors.Primary
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = ValenColors.Text,
        modifier = Modifier
            .padding(top = Space.step5, bottom = Space.step1)
            .drawBehind { drawCircle(primary, 3.dp.toPx(), Offset(-9.dp.toPx(), size.height / 2f)) }
            .padding(start = 2.dp),
    )
}

/** A value as the head of a ruler: big, tabular, with a word about it. */
@Composable
private fun Big(value: String, words: String) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(bottom = Space.step1)) {
        Text(value, style = TrackType.headline.copy(fontFeatureSettings = "tnum"), color = ValenColors.Text)
        Spacer(Modifier.width(Space.step3))
        Text(words, style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2, modifier = Modifier.padding(bottom = 3.dp))
    }
}

private fun presetIcon(id: String): ImageVector = when (id) {
    "flat" -> Icons.Rounded.HorizontalRule
    "bass" -> Icons.Rounded.Speaker
    "vocal" -> Icons.Rounded.RecordVoiceOver
    "bright" -> Icons.Rounded.LightMode
    "warm" -> Icons.Rounded.LocalFireDepartment
    "acoustic" -> Icons.Rounded.Piano
    "rock" -> Icons.Rounded.ElectricBolt
    "hiphop" -> Icons.Rounded.Headphones
    "electronic" -> Icons.Rounded.Equalizer
    "slowed" -> Icons.Rounded.SlowMotionVideo
    "nightcore" -> Icons.Rounded.FastForward
    "hall" -> Icons.Rounded.AccountBalance
    "eightd" -> Icons.Rounded.SurroundSound
    "lofi" -> Icons.Rounded.Album
    "underwater" -> Icons.Rounded.WaterDrop
    else -> Icons.Rounded.Bedtime
}

private fun percent(v: Float): String = "${(v * 100).roundToInt()}%"

private fun decibels(v: Float): String = when {
    abs(v) < 0.05f -> "0 dB"
    v > 0f -> "+${half(v)} dB"
    else -> "−${half(-v)} dB"
}

private fun semitones(v: Float): String = when {
    abs(v) < 0.05f -> "0"
    v > 0f -> "+${oneDecimal(v)}"
    else -> "−${oneDecimal(-v)}"
} + " st"

private fun half(v: Float): String {
    val r = (v * 2f).roundToInt() / 2f
    return if (r == r.roundToInt().toFloat()) r.roundToInt().toString() else r.toString()
}

private fun oneDecimal(v: Float): String {
    val r = (v * 10f).roundToInt() / 10f
    return if (r == r.roundToInt().toFloat()) r.roundToInt().toString() else r.toString()
}

private fun Float.fixed2(): String {
    val n = (this * 100f).roundToInt()
    return "${n / 100}.${(n % 100).toString().padStart(2, '0')}"
}
