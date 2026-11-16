/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale

/**
 * A profile picture or banner, as something to draw. A GIF moves — every frame, at its own
 * timing — and anything else is a still image; null while there is no picture. The painter
 * redraws whoever draws it as the frames change, so the television's tube plays a GIF too.
 */
@Composable
expect fun rememberPicturePainter(uri: String?): Painter?

/** A profile picture filling [modifier]'s bounds, cropped to fill, moving if it is a GIF. */
@Composable
fun PictureImage(uri: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val painter = rememberPicturePainter(uri) ?: return
    Image(painter = painter, contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier)
}

/** Whether a stored picture is an animation to decode frame by frame. */
internal fun isGif(uri: String): Boolean = uri.substringBefore('?').endsWith(".gif", ignoreCase = true)

/** Deletes Valen's own copy of a picture that is being removed; a picture elsewhere is never touched. */
internal fun forgetPicture(uri: String?) {
    if (uri == null || !uri.startsWith("file:")) return
    runCatching {
        val file = java.io.File(java.net.URI(uri))
        if (file.parentFile?.name == "profile") file.delete()
    }
}
