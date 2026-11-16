/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.valen.core.OfflineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a playlist download going when Valen is not on screen: a foreground service with one quiet
 * notification ("Downloading Night drive · 12 of 41", the song now, a bar), there only while a
 * kept list is being fetched. [follow] starts and stops it from the shelf's state.
 */
class DownloadService : Service() {

    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService() must be answered by startForeground() at once.
        show(line)
        if (line == null) stopSelf()
        return START_NOT_STICKY
    }

    /** Android 15 limits a data-sync service to six hours a day: the songs left wait for the app. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun show(line: Line?) {
        val notification = build(this, line ?: Line("Downloading", "", 0, 0))
        if (!foreground) {
            runCatching {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                )
                foreground = true
            }.onFailure { android.util.Log.w("Valen", "downloads not in the foreground: ${it.message}") }
        } else {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    /** What the notification says. [done] of [total] fills the bar; a total of 0 leaves it moving. */
    data class Line(val title: String, val text: String, val done: Int, val total: Int)

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2

        @Volatile private var line: Line? = null
        @Volatile private var instance: DownloadService? = null

        /**
         * Shows the shelf's downloads while any runs. [name] gives a kept list's name by its id.
         * Started only while a list is running, which a tap in the app begins.
         */
        fun follow(context: Context, scope: CoroutineScope, state: StateFlow<OfflineState>, name: (String) -> String?) {
            val app = context.applicationContext
            scope.launch {
                state.map { lineFor(it, name) }.distinctUntilChanged().collect { next ->
                    line = next
                    val running = instance
                    when {
                        next == null -> running?.let { it.stopForeground(STOP_FOREGROUND_REMOVE); it.stopSelf() }
                        running != null -> running.show(next)
                        else -> runCatching {
                            app.startForegroundService(Intent(app, DownloadService::class.java))
                        }.onFailure { android.util.Log.w("Valen", "downloads service not started: ${it.message}") }
                    }
                }
            }
        }

        fun lineFor(state: OfflineState, name: (String) -> String?): Line? {
            val running = state.playlists.filterValues { it.running }
            if (running.isEmpty()) return null
            val title = if (running.size == 1) "Downloading ${name(running.keys.first()) ?: "a playlist"}"
            else "Downloading ${running.size} playlists"
            val done = running.values.sumOf { it.done + it.missing.size }
            val total = running.values.sumOf { it.total }
            val now = running.values.firstNotNullOfOrNull { d -> d.current?.takeIf { it != "Waiting" } }
            val text = state.resting?.reason ?: now?.let { "$done of $total · $it" } ?: "$done of $total"
            return Line(title, text, done, total)
        }

        private fun build(context: Context, line: Line): Notification {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_valen_notification)
                .setContentTitle(line.title)
                .setContentText(line.text)
                .setContentIntent(launch)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(line.total, line.done, line.total == 0)
                .build()
        }

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.valen_channel_downloads), NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) },
                )
            }
        }
    }
}
