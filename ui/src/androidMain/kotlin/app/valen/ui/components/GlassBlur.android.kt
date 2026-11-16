/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Android blurs a layer from 12 (API 31) and runs the lens shader from 13 (API 33). Below 12 the
 * glass falls back to its near-opaque fill, since an unblurred page behind chrome is unreadable.
 */
internal actual object GlassBlur {
    actual val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val blurred = android.graphics.RenderEffect.createBlurEffect(blur / 2f, blur / 2f, Shader.TileMode.CLAMP)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return blurred.asComposeRenderEffect()
        return lensOver(blurred, width, height, inset, corner, depth, bend, dispersion).asComposeRenderEffect()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun lensOver(
        blurred: android.graphics.RenderEffect,
        width: Float,
        height: Float,
        inset: Float,
        corner: Float,
        depth: Float,
        bend: Float,
        dispersion: Float,
    ): android.graphics.RenderEffect {
        val shader = RuntimeShader(LensShader)
        shader.setFloatUniform("rect", inset, inset, width - inset, height - inset)
        shader.setFloatUniform("radius", corner)
        shader.setFloatUniform("depth", depth)
        shader.setFloatUniform("bend", bend)
        shader.setFloatUniform("disperse", dispersion)
        return android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"),
            blurred,
        )
    }
}
