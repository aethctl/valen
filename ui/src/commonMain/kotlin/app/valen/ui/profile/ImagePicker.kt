/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.profile

import androidx.compose.runtime.Composable

/** The pictures a profile keeps; each is stored in Valen's own folder under its kind's name. */
enum class PictureKind(val file: String) {
    Avatar("avatar"),
    Banner("banner"),

    /** A playlist's own picture: many can exist, so older ones are never cleared by a new one. */
    Cover("cover"),
}

/**
 * Asks the system for a picture — a file chooser on the desktop, the photo picker on Android —
 * copies it into Valen's own folder (so it survives the original being moved or deleted), and
 * hands back its file URI. The returned function opens the chooser. A cancelled choice calls
 * nothing.
 *
 * Each copy has a new name, so an image cache never shows the picture it replaced, and the
 * previous copy of the same kind is removed.
 */
@Composable
expect fun rememberPicturePicker(kind: PictureKind, onPicked: (String) -> Unit): () -> Unit

/** The extensions a chosen picture may have. */
internal val PictureExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

/** Copies [bytes] into [folder] as a fresh file of [kind], removing older copies of that kind. */
internal fun storePicture(folder: java.io.File, kind: PictureKind, bytes: ByteArray, extension: String): String {
    folder.mkdirs()
    if (kind != PictureKind.Cover) folder.listFiles()?.filter { it.name.startsWith(kind.file + "-") }?.forEach { it.delete() }
    val ext = extension.lowercase().takeIf { it in PictureExtensions } ?: "png"
    val file = java.io.File(folder, "${kind.file}-${System.currentTimeMillis()}.$ext")
    file.writeBytes(bytes)
    return file.toURI().toString()
}
