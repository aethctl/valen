/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.io.File
import java.net.URI
import kotlin.math.roundToInt

/** Desktop: a GIF is decoded by Skia's own codec into its frames, composed in order, and played. */
@Composable
actual fun rememberPicturePainter(uri: String?): Painter? {
    if (uri == null) return null
    if (!isGif(uri)) return rememberAsyncImagePainter(uri)
    val frames by produceState<Frames?>(null, uri) { value = withContext(Dispatchers.IO) { decodeFrames(uri) } }
    val loaded = frames
    // Until the frames are ready (or if the file is a still GIF), the still image stands in.
    if (loaded == null || loaded.images.size < 2) return rememberAsyncImagePainter(uri)
    val painter = remember(loaded) { FramePainter(loaded.images) }
    LaunchedEffect(loaded) {
        while (true) {
            for (i in loaded.images.indices) {
                painter.index = i
                delay(loaded.durations[i])
            }
        }
    }
    return painter
}

internal class Frames(val images: List<ImageBitmap>, val durations: LongArray)

/** No more than this many bytes of decoded frames are kept; a longer GIF keeps every n-th frame. */
private const val FrameBudget = 160L * 1024 * 1024

private fun decodeFrames(uri: String): Frames? = runCatching {
    val bytes = File(URI(uri)).readBytes()
    val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
    val count = codec.frameCount
    if (count < 2) return@runCatching null
    val info = codec.imageInfo
    val frameBytes = info.width.toLong() * info.height * 4
    val step = ((frameBytes * count) / FrameBudget + 1).toInt().coerceAtLeast(1)
    val infos = codec.framesInfo
    val composed = arrayOfNulls<Bitmap>(count)
    val images = ArrayList<ImageBitmap>()
    val durations = ArrayList<Long>()
    var pending = 0L
    for (i in 0 until count) {
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        val required = infos[i].requiredFrame
        val prior = if (required >= 0) composed[required] else null
        if (prior != null) {
            // A frame drawn over an earlier one starts from that one's finished picture.
            prior.readPixels(info, info.minRowBytes, 0, 0)?.let { bitmap.installPixels(info, it, info.minRowBytes) }
            codec.readPixels(bitmap, i, required)
        } else {
            codec.readPixels(bitmap, i)
        }
        composed[i] = bitmap
        pending += infos[i].duration.toLong().coerceAtLeast(20)
        if (i % step == 0) {
            images += bitmap.asComposeImageBitmap()
            durations += pending
            pending = 0L
        }
    }
    if (pending > 0 && durations.isNotEmpty()) durations[durations.lastIndex] = durations.last() + pending
    Frames(images, durations.toLongArray())
}.onFailure { System.err.println("valen: the GIF could not be decoded: ${it.message}") }.getOrNull()

/** Draws one of [frames], the one [index] names; reading it in the draw redraws on each frame. */
private class FramePainter(private val frames: List<ImageBitmap>) : Painter() {
    var index by mutableIntStateOf(0)
    override val intrinsicSize: Size = Size(frames[0].width.toFloat(), frames[0].height.toFloat())

    override fun DrawScope.onDraw() {
        val image = frames[index.coerceIn(0, frames.lastIndex)]
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}
