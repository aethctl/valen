/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.skiaPaint

internal actual fun Paint.dither() {
    skiaPaint.isDither = true
}
