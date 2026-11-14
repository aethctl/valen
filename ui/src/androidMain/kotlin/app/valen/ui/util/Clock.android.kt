/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.util

import java.util.Calendar

internal actual fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
