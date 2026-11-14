/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.util

import java.time.LocalTime

internal actual fun currentHour(): Int = LocalTime.now().hour
