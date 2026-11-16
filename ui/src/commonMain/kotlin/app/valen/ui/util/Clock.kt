/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.util

/** The device's local hour, 0–23, for the one greeting on Home. */
internal expect fun currentHour(): Int

internal fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    in 18..22 -> "Good evening"
    else -> "Still listening"
}

/** The time now as a notebook stamps it: "21:14". */
internal expect fun clockText(): String

/** Today as a notebook heads its page: "Sat 15 Nov". */
internal expect fun dayText(): String
