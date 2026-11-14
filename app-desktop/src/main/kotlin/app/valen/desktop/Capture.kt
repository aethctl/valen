/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import app.valen.core.PlaybackCoordinator
import app.valen.ui.Route
import app.valen.ui.ValenApp
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
        route: Route,
        width: Int,
        height: Int,
        path: Path,
        query: String? = null,
    ): Boolean =
        runCatching {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val scene = ImageComposeScene(
                width = width,
                height = height,
                density = Density(1f),
            ) {
                ValenApp(coordinator, route.name, query)
            }
            try {
                // One frame composes the tree, then the scene is drawn repeatedly while the real
                // clock moves: searches, artwork and their colours arrive after the first frame.
                var nanos = 0L
                scene.render(nanos)
                val settleUntil = System.nanoTime() + SETTLE_MS * 1_000_000L
                while (System.nanoTime() < settleUntil) {
                    Thread.sleep(STEP_MS)
                    nanos += STEP_MS * 1_000_000L
                    scene.render(nanos)
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
