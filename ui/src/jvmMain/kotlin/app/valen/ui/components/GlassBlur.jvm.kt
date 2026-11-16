/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/** Skia does both halves on the desktop: a blur, then the lens shader over the blurred page. */
internal actual object GlassBlur {
    actual val supported: Boolean = true

    private val lens: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(LensShader) }
            .onFailure { System.err.println("valen: the glass rim shader did not compile: ${it.message}") }
            .getOrNull()
    }

    actual fun effect(
        width: Float,
        height: Float,
        inset: Float,
        corner: Float,
        blur: Float,
        depth: Float,
        bend: Float,
        dispersion: Float,
    ): RenderEffect? {
        val blurred = ImageFilter.makeBlur(blur / 2f, blur / 2f, FilterTileMode.CLAMP)
        val effect = lens ?: return blurred.asComposeRenderEffect()
        val builder = RuntimeShaderBuilder(effect)
        builder.uniform("rect", inset, inset, width - inset, height - inset)
        builder.uniform("radius", corner)
        builder.uniform("depth", depth)
        builder.uniform("bend", bend)
        builder.uniform("disperse", dispersion)
        return ImageFilter.makeRuntimeShader(builder, "content", blurred).asComposeRenderEffect()
    }
}
