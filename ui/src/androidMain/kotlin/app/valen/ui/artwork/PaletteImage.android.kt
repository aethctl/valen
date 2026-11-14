/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.artwork

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import coil3.Image
import coil3.toBitmap

internal actual fun Image.toSmallPixelMap(maxSize: Int): PixelMap? = runCatching {
    val source = toBitmap()
    val longest = maxOf(source.width, source.height)
    val scaled = if (longest > maxSize && longest > 0) {
        val ratio = maxSize.toFloat() / longest
        Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        source
    }
    val pixels = scaled.asImageBitmap().toPixelMap()
    PixelMap(pixels.width, pixels.height, pixels.buffer)
}.getOrNull()
