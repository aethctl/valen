/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.profile

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import kotlin.math.roundToInt

/** Android: a GIF is the platform's own animated drawable, started while it is shown. */
@Composable
actual fun rememberPicturePainter(uri: String?): Painter? {
    if (uri == null) return null
    if (!isGif(uri)) return rememberAsyncImagePainter(uri)
    val drawable by produceState<Drawable?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(URI(uri)))) }.getOrNull()
        }
    }
    val loaded = drawable ?: return rememberAsyncImagePainter(uri)
    val painter = remember(loaded) { DrawablePainter(loaded) }
    DisposableEffect(loaded) {
        (loaded as? Animatable)?.start()
        onDispose { (loaded as? Animatable)?.stop() }
    }
    return painter
}

/** A drawable as a painter; each invalidation the drawable asks for redraws whoever draws this. */
private class DrawablePainter(private val drawable: Drawable) : Painter() {
    private var tick by mutableIntStateOf(0)
    private val main = Handler(Looper.getMainLooper())

    // Held here: a drawable keeps only a weak reference to its callback.
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { tick++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { main.postAtTime(what, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { main.removeCallbacks(what) }
    }

    init {
        drawable.callback = callback
    }

    override val intrinsicSize: Size = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun DrawScope.onDraw() {
        @Suppress("UNUSED_VARIABLE")
        val frame = tick
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
