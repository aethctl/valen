/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.android

import android.app.Application

/**
 * The process's entry point: builds the runtime (WebViews, providers, coordinator, media session)
 * before any activity, so music and sign-in survive the activity being recreated or closed.
 */
class ValenApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ValenRuntime.start(this)
    }
}
