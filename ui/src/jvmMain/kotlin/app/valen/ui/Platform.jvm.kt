/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

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

/** No share sheet on the desktop: the link is copied instead. */
@Composable
actual fun rememberSystemShare(): (subject: String, text: String) -> Boolean = { _, _ -> false }

actual val canKeepScreenOn: Boolean = false

@Composable
actual fun KeepScreenOn(on: Boolean) = Unit

@Composable
actual fun SystemBack(enabled: Boolean, onBack: () -> Unit) = Unit

/**
 * `xdg-open`, as its own process. Never AWT's `Desktop`: on Linux it loads GTK into Valen, and
 * GTK's accessibility bridge aborts the JVM on this kind of machine (see the picture chooser).
 */
@Composable
actual fun rememberFolderOpener(path: String): (() -> Unit)? = remember(path) { { openFolder(path) } }

private fun openFolder(path: String): Boolean = runCatching {
    ProcessBuilder("xdg-open", path)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .also { it.environment().remove("LD_LIBRARY_PATH"); it.environment().remove("LD_PRELOAD") }
        .start()
    true
}.getOrDefault(false)

/**
 * `zenity` or `kdialog` in its own process, off the UI thread, Swing's chooser without either; the
 * same rules as the picture chooser (never GTK inside Valen).
 */
@Composable
actual fun rememberLocalPicker(onPicked: (String) -> Unit, onAccess: () -> Unit): ((folder: Boolean) -> Unit)? {
    val latest = rememberUpdatedState(onPicked)
    val open = remember { AtomicBoolean(false) }
    return remember {
        { folder ->
            if (open.compareAndSet(false, true)) {
                thread(name = "valen-local-chooser", isDaemon = true) {
                    try {
                        val picked = chooseLocal(folder)
                        if (picked != null) SwingUtilities.invokeLater { latest.value(picked) }
                    } catch (failure: Throwable) {
                        System.err.println("valen: the chooser failed: ${failure.message}")
                    } finally {
                        open.set(false)
                    }
                }
            }
        }
    }
}

private val PlaylistPatterns = listOf("m3u", "m3u8", "pls", "xspf")

private fun chooseLocal(folder: Boolean): String? {
    val home = System.getProperty("user.home")
    val start = File(home, "Music").takeIf { it.isDirectory }?.path ?: home
    val title = if (folder) "Choose a folder of songs" else "Choose a playlist file"
    val patterns = PlaylistPatterns.joinToString(" ") { "*.$it" }
    val commands = listOf(
        listOf("zenity", "--file-selection", "--title=$title", "--filename=$start/") +
            (if (folder) listOf("--directory") else listOf("--file-filter=Playlists | $patterns")),
        listOf("kdialog", "--title", title) +
            (if (folder) listOf("--getexistingdirectory", start) else listOf("--getopenfilename", start, "Playlists ($patterns)")),
    )
    val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
    for (command in commands) {
        val program = path.map { File(it, command.first()) }.firstOrNull { it.canExecute() } ?: continue
        val process = runCatching {
            ProcessBuilder(listOf(program.path) + command.drop(1))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment().remove("LD_LIBRARY_PATH"); it.environment().remove("LD_PRELOAD") }
                .start()
        }.getOrNull() ?: continue
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroy()
            return null
        }
        return when (process.exitValue()) {
            0 -> output.lines().firstOrNull()?.takeIf { it.isNotBlank() }
            1 -> null
            else -> continue
        }
    }
    var picked: String? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser(start).apply {
            dialogTitle = title
            if (folder) {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            } else {
                fileFilter = FileNameExtensionFilter("Playlists", *PlaylistPatterns.toTypedArray())
            }
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picked = chooser.selectedFile.absolutePath
    }
    return picked
}
