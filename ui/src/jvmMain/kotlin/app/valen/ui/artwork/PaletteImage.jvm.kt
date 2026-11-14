/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.artwork

import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import coil3.Image
import coil3.toBitmap

/**
 * Skia converts and scales the decoded image in one step; only the small result is copied into
 * common code.
 */
internal actual fun Image.toSmallPixelMap(maxSize: Int): PixelMap? = runCatching {
    val pixels = toBitmap(maxSize, maxSize).asComposeImageBitmap().toPixelMap()
    PixelMap(pixels.width, pixels.height, pixels.buffer)
}.getOrNull()
