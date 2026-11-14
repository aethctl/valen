/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android, `Widevine.kt`.
 * See NOTICE.
 */
package app.valen.host.android

/**
 * The phone's Widevine, as MediaDrm reports it. Spotify's licence refusals (403 from the first
 * song) were seen on phones on Widevine 15.0 or 16.0, L1 and L3 alike; phones on 18.0 played. Kept
 * so a refused play can say which DRM the device offers.
 */
object Widevine {
    /** "15.0.0", "18.0.0@343213000", or null when MediaDrm does not answer. Read once. */
    val version: String? by lazy {
        runCatching {
            val drm = android.media.MediaDrm(java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"))
            try { drm.getPropertyString("version") }
            finally { if (android.os.Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release() }
        }.getOrNull()
    }

    /** True for a version below 16.1, the ones seen refused; false when it cannot be read. */
    fun tooOld(version: String?): Boolean {
        val m = Regex("""^(\d+)\.(\d+)""").find(version ?: return false) ?: return false
        val major = m.groupValues[1].toInt()
        return major < 16 || (major == 16 && m.groupValues[2].toInt() < 1)
    }
}
