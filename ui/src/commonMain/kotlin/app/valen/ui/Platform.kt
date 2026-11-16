/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.runtime.Composable

/**
 * Hands a song's link to the system's own share sheet: `(subject, text) -> shown`. False where
 * there is no share sheet (the desktop), and the caller copies the link instead.
 */
@Composable
expect fun rememberSystemShare(): (subject: String, text: String) -> Boolean

/** Whether the screen can be kept awake here (a phone can; a desktop leaves that to its own settings). */
expect val canKeepScreenOn: Boolean

/** Keeps the screen awake while this is composed and [on]. */
@Composable
expect fun KeepScreenOn(on: Boolean)

/** The system's back (a phone's gesture or button) does [onBack] while [enabled]; nothing on a desktop. */
@Composable
expect fun SystemBack(enabled: Boolean, onBack: () -> Unit)

/** Opens the folder at [path] in the system's file manager; null where that cannot be done. */
@Composable
expect fun rememberFolderOpener(path: String): (() -> Unit)?

/**
 * The chooser for a playlist file or a folder of songs on this device: call it with `folder`
 * true for a folder; [onPicked] gets a path. A phone asks for its music first, and [onAccess] says
 * it was given (the device's playlist files can be listed again).
 */
@Composable
expect fun rememberLocalPicker(onPicked: (String) -> Unit, onAccess: () -> Unit = {}): ((folder: Boolean) -> Unit)?

/** A folder as a person reads it: `~/Music/Valen` on a desktop, `Music/Valen` on a phone. */
fun shortFolder(path: String): String =
    path.replace(Regex("^/home/[^/]+"), "~").replace(Regex("^/storage/emulated/\\d+/"), "")
