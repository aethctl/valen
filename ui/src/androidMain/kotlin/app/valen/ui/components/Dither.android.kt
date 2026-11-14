/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.nativePaint

internal actual fun Paint.dither() {
    nativePaint.isDither = true
}
