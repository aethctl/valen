/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.util

import java.util.Calendar

internal actual fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

internal actual fun clockText(): String =
    java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

internal actual fun dayText(): String =
    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
