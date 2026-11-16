/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

/**
 * The desktop's file chooser, in its own process: `zenity` (GTK) or `kdialog` (KDE), whichever the
 * machine has, run off the UI thread so the window keeps drawing while it is open. Without either,
 * Swing's own chooser.
 *
 * Never AWT's `FileDialog`: on Linux it loads GTK 3 into Valen's own process, and GTK's
 * accessibility bridge then aborts the whole JVM inside `g_bus_get_sync` (seen on niri: SIGABRT
 * in `atk_bridge_adaptor_init` under `gtk_init_check`, every time the set was clicked). A chooser
 * in another process cannot take the app down with it.
 *
 * The copy goes to `$XDG_DATA_HOME/valen/profile`, beside the playlists.
 */
@Composable
actual fun rememberPicturePicker(kind: PictureKind, onPicked: (String) -> Unit): () -> Unit {
    val latest = rememberUpdatedState(onPicked)
    val open = remember { AtomicBoolean(false) }
    return remember(kind) {
        {
            // One chooser at a time: a second click while it is open does nothing.
            if (open.compareAndSet(false, true)) {
                val title = when (kind) {
                    PictureKind.Avatar -> "Choose a profile picture"
                    PictureKind.Banner -> "Choose a banner"
                    PictureKind.Cover -> "Choose a playlist picture"
                }
                thread(name = "valen-picture-chooser", isDaemon = true) {
                    try {
                        val chosen = when (val external = chooseExternally(title)) {
                            is Choice.Picked -> external.file
                            Choice.Cancelled -> null
                            Choice.Unavailable -> chooseWithSwing(title)
                        }
                        if (chosen != null && chosen.isFile) {
                            runCatching { storePicture(profileFolder(), kind, chosen.readBytes(), chosen.extension) }
                                .onSuccess { uri -> SwingUtilities.invokeLater { latest.value(uri) } }
                                .onFailure { System.err.println("valen: the picture could not be copied: ${it.message}") }
                        }
                    } catch (failure: Throwable) {
                        System.err.println("valen: the picture chooser failed: ${failure.message}")
                    } finally {
                        open.set(false)
                    }
                }
            }
        }
    }
}

internal sealed interface Choice {
    data class Picked(val file: File) : Choice
    data object Cancelled : Choice
    data object Unavailable : Choice
}

/** The first chooser program on [path], asked for one picture; [Choice.Unavailable] if there is none. */
internal fun chooseExternally(title: String, path: String = System.getenv("PATH").orEmpty()): Choice {
    val patterns = PictureExtensions.joinToString(" ") { "*.$it" }
    val commands = listOf(
        listOf("zenity", "--file-selection", "--title=$title", "--file-filter=Pictures | $patterns"),
        listOf("kdialog", "--title", title, "--getopenfilename", System.getProperty("user.home"), "Pictures ($patterns)"),
    )
    for (command in commands) {
        val program = onPath(command.first(), path) ?: continue
        val process = runCatching {
            ProcessBuilder(listOf(program.path) + command.drop(1))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { builder ->
                    // A packaged Valen (an AppImage) may point the loader at its own libraries;
                    // the chooser must load the system's.
                    builder.environment().remove("LD_LIBRARY_PATH")
                    builder.environment().remove("LD_PRELOAD")
                }
                .start()
        }.getOrNull() ?: continue
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroy()
            return Choice.Cancelled
        }
        return when {
            process.exitValue() == 0 && output.isNotEmpty() -> Choice.Picked(File(output.lines().first()))
            // 1 is "closed without choosing" for both; anything else, try the next chooser.
            process.exitValue() == 1 -> Choice.Cancelled
            else -> continue
        }
    }
    return Choice.Unavailable
}

private fun onPath(program: String, path: String): File? =
    path.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it, program) }.firstOrNull { it.canExecute() }

/** Swing's chooser, on the Swing thread, with its own look (it never loads GTK). */
private fun chooseWithSwing(title: String): File? {
    var picked: File? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileFilter = FileNameExtensionFilter("Pictures", *PictureExtensions.toTypedArray())
            isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picked = chooser.selectedFile
    }
    return picked
}

private fun profileFolder(): File {
    val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: (System.getProperty("user.home") + "/.local/share")
    return File(data, "valen/profile")
}
