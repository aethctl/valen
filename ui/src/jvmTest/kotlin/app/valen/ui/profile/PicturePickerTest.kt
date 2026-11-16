/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The desktop picture chooser runs in its own process, because AWT's FileDialog loads GTK into
 * Valen and GTK's accessibility bridge aborted the JVM on niri. These pin the process plumbing
 * with stand-in choosers, so no real dialog opens.
 */
class PicturePickerTest {

    private fun fake(dir: File, name: String, script: String) = File(dir, name).apply {
        writeText("#!/bin/sh\n$script\n")
        setExecutable(true)
    }

    @Test
    fun `a chosen file comes back from the chooser process`(@TempDir dir: File) {
        val picture = File(dir, "me.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        fake(dir, "zenity", "echo '${picture.path}'")
        assertEquals(Choice.Picked(picture), chooseExternally("Choose", dir.path))
    }

    @Test
    fun `closing the chooser is a cancel, not a fall back to another chooser`(@TempDir dir: File) {
        fake(dir, "zenity", "exit 1")
        assertEquals(Choice.Cancelled, chooseExternally("Choose", dir.path))
    }

    @Test
    fun `kdialog is used when zenity is missing, and nothing at all says unavailable`(@TempDir dir: File) {
        assertEquals(Choice.Unavailable, chooseExternally("Choose", dir.path))
        val picture = File(dir, "banner.jpg").apply { writeBytes(byteArrayOf(4)) }
        fake(dir, "kdialog", "echo '${picture.path}'")
        assertEquals(Choice.Picked(picture), chooseExternally("Choose", dir.path))
    }
}
