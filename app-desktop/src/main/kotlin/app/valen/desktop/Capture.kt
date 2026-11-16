/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.valen.ui.menu.MenuOrigin
import app.valen.ui.menu.MenuPage
import app.valen.ui.menu.SongMenuState
import app.valen.ui.components.PageMemory
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Density
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistManager
import app.valen.ui.Route
import app.valen.ui.ValenApp
import app.valen.ui.recap.RecapState
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `--screenshot` pass: `ValenApp` drawn at exactly the requested size, in one offscreen Skia
 * scene, and written as PNG. It is what the docs screenshots are made of.
 *
 * Not a screen capture on purpose: on Wayland a compositor does not hand a window's pixels to an
 * X11 client, so `java.awt.Robot` returns a black frame; the scene draws the same composition the
 * window draws, at the same pixel size, with no window manager in between.
 */
object Capture {

    @OptIn(ExperimentalComposeUiApi::class)
    fun render(
        coordinator: PlaybackCoordinator,
        playlists: PlaylistManager,
        route: Route,
        width: Int,
        height: Int,
        path: Path,
        query: String? = null,
    ): Boolean =
        runCatching {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            // Development only: VALEN_CAPTURE_GO switches to another route halfway through the
            // settle (so a page change can be seen), VALEN_CAPTURE_FRAMES saves every frame drawn.
            val shownRoute = mutableStateOf(route.name)
            val goTo = System.getenv("VALEN_CAPTURE_GO")?.let { Route.from(it).name }
            // VALEN_CAPTURE_MENU=main|playlist|sleep opens the playing song's menu on that page,
            // grown from about where the stage's ⋯ sits, at the same halfway point.
            val menuPage = when (System.getenv("VALEN_CAPTURE_MENU")) {
                "main" -> MenuPage.Main
                "playlist" -> MenuPage.AddToPlaylist
                "sleep" -> MenuPage.Sleep
                else -> null
            }
            val songMenu = SongMenuState()
            var menuOpened = false
            // VALEN_CAPTURE_PLAYLIST=<id> opens that playlist on the Playlists page; "new" puts a
            // blank sleeve on the shelf to be named.
            val pageMemory = PageMemory()
            when (val wanted = System.getenv("VALEN_CAPTURE_PLAYLIST")) {
                null -> Unit
                "new" -> pageMemory.value("playlists.creating", false).value = true
                else -> pageMemory.value<String?>("playlists.open", null).value = wanted
            }
            // VALEN_CAPTURE_SETTINGS=<part> opens that part of Settings (services, playback, week…).
            System.getenv("VALEN_CAPTURE_SETTINGS")?.let { pageMemory.value<String?>("settings:part", null).value = it }
            // VALEN_CAPTURE_SCROLL=<page key>:<px> starts that page scrolled (below-the-fold sections).
            System.getenv("VALEN_CAPTURE_SCROLL")?.split(':')?.takeIf { it.size == 2 }?.let { (key, px) ->
                px.toIntOrNull()?.let { pageMemory.startScrolled(key, it) }
            }
            // The sound notebook: VALEN_CAPTURE_TAB opens a tab of its settings page (0–4),
            // VALEN_CAPTURE_TURN turns to another halfway, VALEN_CAPTURE_SOUND picks a preset halfway.
            val soundTab = pageMemory.value("sound.tab", 0)
            System.getenv("VALEN_CAPTURE_TAB")?.toIntOrNull()?.let { soundTab.value = it }
            val turnTo = System.getenv("VALEN_CAPTURE_TURN")?.toIntOrNull()
            val presetTo = System.getenv("VALEN_CAPTURE_SOUND")?.let { app.valen.core.SoundPresets.byId(it) }
            var soundDone = false
            // VALEN_CAPTURE_SEEK=<ms> moves the song there once it plays (to see lyrics mid-song);
            // VALEN_CAPTURE_WORDS=1 turns the stage's lyrics on halfway (to see the swap).
            val seekTo = System.getenv("VALEN_CAPTURE_SEEK")?.toLongOrNull()
            var sought = seekTo == null
            val wordsHalfway = System.getenv("VALEN_CAPTURE_WORDS") != null
            // VALEN_CAPTURE_RECAP=<page> opens "Your week" on that page (0 is the first) after the
            // first few frames, so it pours in; VALEN_CAPTURE_RECAP_TURN=1 turns to the next page halfway.
            val recap = RecapState()
            val recapAt = System.getenv("VALEN_CAPTURE_RECAP")?.toIntOrNull()
            var recapOpened = recapAt == null
            var recapTurned = System.getenv("VALEN_CAPTURE_RECAP_TURN") == null
            val frames = System.getenv("VALEN_CAPTURE_FRAMES")?.let { Path.of(it) }?.also { Files.createDirectories(it) }
            var frame = 0
            val step = System.getenv("VALEN_CAPTURE_STEP_MS")?.toLongOrNull() ?: STEP_MS
            val scene = ImageComposeScene(
                width = width,
                height = height,
                density = Density(1f),
            ) {
                ValenApp(coordinator, playlists, shownRoute.value, query, songMenu = songMenu, pageMemory = pageMemory, recap = recap)
            }
            try {
                // One frame composes the tree, then the scene is drawn repeatedly while the real
                // clock moves: searches, artwork and their colours arrive after the first frame.
                var nanos = 0L
                scene.render(nanos).close()
                // VALEN_CAPTURE_SETTLE_MS: a longer settle, to see what still moves once the start of a
                // song's motion is over (the glass orbit rests after two laps).
                val settle = System.getenv("VALEN_CAPTURE_SETTLE_MS")?.toLongOrNull() ?: SETTLE_MS
                val settleUntil = System.nanoTime() + settle * 1_000_000L
                val halfway = System.nanoTime() + settle * 500_000L
                while (System.nanoTime() < settleUntil) {
                    Thread.sleep(step)
                    nanos += step * 1_000_000L
                    if (goTo != null && shownRoute.value != goTo && System.nanoTime() > halfway) shownRoute.value = goTo
                    if (!recapOpened && nanos >= 240_000_000L) {
                        recapOpened = true
                        recap.open(recapAt!!)
                    }
                    if (!recapTurned && System.nanoTime() > halfway) {
                        recapTurned = true
                        recap.turn()
                    }
                    if (!sought && coordinator.snapshot.value?.isPlaying == true) {
                        sought = true
                        kotlinx.coroutines.runBlocking { coordinator.seek(seekTo!!) }
                    }
                    if (!soundDone && System.nanoTime() > halfway) {
                        soundDone = true
                        if (wordsHalfway) coordinator.setLyricsShown(true)
                        turnTo?.let { soundTab.value = it }
                        presetTo?.let { coordinator.usePreset(it) }
                    }
                    if (menuPage != null && !menuOpened && System.nanoTime() > halfway) {
                        coordinator.snapshot.value?.track?.let { song ->
                            if (menuPage == MenuPage.Sleep) coordinator.sleepIn(30)
                            songMenu.open(song, MenuOrigin.Player, Rect(Offset(width - 76f, 8f), Size(44f, 44f)), alignEnd = true, page = menuPage)
                            menuOpened = true
                        }
                    }
                    // Each frame is a fresh native image: closed at once, or the settle holds
                    // gigabytes of them until the collector happens to finalise them.
                    scene.render(nanos).use { drawn ->
                        if (frames != null) {
                            drawn.encodeToData(EncodedImageFormat.PNG)?.let {
                                Files.write(frames.resolve("frame-%03d.png".format(frame++)), it.bytes)
                            }
                        }
                    }
                }
                if (System.getenv("VALEN_CAPTURE_IDLE") != null) {
                    // Development: after the settle nothing should still be asking for frames
                    // unless something on screen is really animating.
                    val busy = (1..20).count {
                        nanos += step * 1_000_000L
                        scene.render(nanos).close()
                        scene.hasInvalidations()
                    }
                    println("frames still invalidated after settle: $busy of 20")
                }
                val image = scene.render(nanos + 1_000_000_000L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("the image could not be encoded")
                Files.write(path.toAbsolutePath(), data.bytes)
                println("saved ${path.toAbsolutePath()} (${image.width}x${image.height})")
                true
            } finally {
                scene.close()
            }
        }.getOrElse {
            System.err.println("valen: could not render the window: ${it.message}")
            false
        }

    /** How long the scene keeps drawing so real network answers can reach a frame. */
    private const val SETTLE_MS = 5_000L
    private const val STEP_MS = 80L
}
