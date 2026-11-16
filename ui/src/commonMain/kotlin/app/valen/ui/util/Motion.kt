/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.util

import androidx.compose.runtime.Composable

/**
 * Whether the system asks for less motion: on Android when animations are turned off in the
 * developer settings or Battery Saver is on (the wash and the glass orbit stop, which is most of
 * what a phone spends drawing while a song plays, and the glass draws solid instead of blurred).
 * The desktop has no such signal here.
 */
@Composable
internal expect fun systemReducedMotion(): Boolean
