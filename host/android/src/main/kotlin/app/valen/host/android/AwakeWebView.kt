/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android,
 * `WebPlayerHost.kt` (the `AwakeWebView` at its end). See NOTICE.
 */
package app.valen.host.android

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * A WebView that Chromium is told is on screen while [awake], so a page that plays audio keeps its
 * timers and its renderer at full speed with the app in the background — the state Valen's WebViews
 * need to keep playing music while the activity is gone.
 *
 * Only Chromium is told: the framework still sees the view's real visibility, so a hidden page is
 * never drawn. The detach that comes with the activity's window going away is held back too:
 * Chromium would otherwise tear down the page's compositor and the music would stop for a moment
 * while the foreground service is still there.
 */
internal class AwakeWebView(context: Context) : WebView(context) {
    private var awake = true
    private var realWindowVisibility = View.VISIBLE
    private var detachHeld = false

    override fun getVisibility(): Int = if (awake) View.VISIBLE else super.getVisibility()

    override fun getWindowVisibility(): Int = if (awake) View.VISIBLE else super.getWindowVisibility()

    override fun onWindowVisibilityChanged(visibility: Int) {
        realWindowVisibility = visibility
        super.onWindowVisibilityChanged(if (awake) View.VISIBLE else visibility)
    }

    /** Tells Chromium the view is visible (or the truth again). */
    fun setAwake(on: Boolean) {
        if (on == awake) return
        awake = on
        val window = if (on) View.VISIBLE else if (isAttachedToWindow) realWindowVisibility else View.GONE
        super.onWindowVisibilityChanged(window)
        onVisibilityChanged(this, getVisibility())
    }

    override fun onDetachedFromWindow() {
        detachHeld = true
    }

    override fun onAttachedToWindow() {
        releaseFromWindow()
        super.onAttachedToWindow()
    }

    /** Tells Chromium about a detach that was held back; no-op when none is pending. */
    fun releaseFromWindow() {
        if (!detachHeld) return
        detachHeld = false
        super.onDetachedFromWindow()
    }
}
