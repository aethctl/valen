/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.artwork

import coil3.Image

/**
 * Re-reads an image Coil already decoded into a small pixel map. The pixel work stays in
 * commonMain; only "decode" and "read pixels" differ per platform.
 */
internal expect fun Image.toSmallPixelMap(maxSize: Int): PixelMap?
