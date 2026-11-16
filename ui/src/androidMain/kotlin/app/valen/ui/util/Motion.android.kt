/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun systemReducedMotion(): Boolean {
    val context = LocalContext.current.applicationContext
    var reduced by remember { mutableStateOf(wantsLess(context)) }
    DisposableEffect(context) {
        // Battery Saver can come and go while the app is open; the animation scale is read again with it.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { reduced = wantsLess(context) }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return reduced
}

private fun wantsLess(context: Context): Boolean {
    val saver = runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
    val still = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    return saver || still
}
