/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android, `PlaybackService.kt`
 * (the `NowPlayingInfo` state and the static update/end entry points). See NOTICE.
 */
package app.valen.host.android

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.valen.core.PlayerSnapshot
import app.valen.core.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's side of the media session. The WebViews play; this only tells the system what is playing
 * (notification, lock screen, Bluetooth, headsets) and hands the system's commands back to the
 * [PlaybackCoordinator][app.valen.core.PlaybackCoordinator] through [controls].
 *
 * All methods are safe from any thread; the service applies them on the main thread.
 */
object AndroidPlayback {
    /** Commands from the system: notification buttons, a headset, the lock screen. */
    interface Controls {
        fun play()
        fun pause()
        fun next()
        fun previous()
        fun seek(positionMs: Long)
    }

    /** Set once by the app's runtime; the coordinator behind it. */
    @Volatile
    var controls: Controls? = null

    @Volatile
    internal var current: PlayerSnapshot? = null

    @Volatile
    internal var queue: List<Track> = emptyList()

    @Volatile
    internal var index: Int = -1

    /** The queue's title (the service shows it for Android Auto and some lock screens). */
    @Volatile
    internal var queueTitle: String? = null

    @Volatile
    internal var service: PlaybackService? = null

    private val _playing = MutableStateFlow(false)

    /** Whether a session is up (the notification is showing). */
    val sessionUp: StateFlow<Boolean> = _playing.asStateFlow()

    /**
     * Pushes the coordinator's snapshot. A song that starts playing starts the foreground service;
     * [snapshot] null ends the session and its notification.
     */
    fun setSnapshot(context: Context, snapshot: PlayerSnapshot?) {
        current = snapshot
        if (snapshot == null) {
            stop(context)
            return
        }
        _playing.value = true
        val svc = service
        if (svc != null) svc.publish()
        else if (snapshot.isPlaying) start(context)
    }

    /** Pushes the app-owned queue and the row now playing (-1: none). */
    fun setQueue(context: Context, tracks: List<Track>, index: Int) {
        queue = tracks
        this.index = index
        queueTitle = if (tracks.isEmpty()) null else "Playing next"
        service?.publishQueue()
    }

    /** Starts the service (a play is about to make sound, so a foreground service must be there). */
    fun start(context: Context) {
        if (service != null) return
        runCatching {
            ContextCompat.startForegroundService(context.applicationContext, Intent(context, PlaybackService::class.java))
        }
    }

    /** Ends the session: the notification and the foreground go with it. */
    fun stop(context: Context) {
        _playing.value = false
        val svc = service
        if (svc != null) svc.endSession() else ended()
    }

    internal fun ended() {
        current = null
        queue = emptyList()
        index = -1
        queueTitle = null
        _playing.value = false
    }
}
