/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android, `PlaybackService.kt`,
 * trimmed to the media session, its notification and the system's commands. See NOTICE.
 */
package app.valen.host.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import app.valen.core.PlayerSnapshot
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The foreground service behind [AndroidPlayback]. It plays nothing: Spotify's Web Player and the
 * hidden pages do, and this service only keeps the process in the foreground (so those WebViews keep
 * their audio) while it publishes the media session — notification, lock screen, Bluetooth buttons,
 * headsets — from the coordinator's snapshot and queue.
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    private var foreground = false
    private var lastNotified: PlayerSnapshot? = null
    // What the session was last told: a snapshot that only moved on as its clock said is not told again.
    private var lastState: PlayerSnapshot? = null
    private var lastStateAt = 0L
    private var lastRate = 1f
    private var lastQueue: List<Track>? = null
    private var lastIndex = -2
    private var lastNotifiedCover: Bitmap? = null
    private var coverUrl: String? = null
    private var cover: Bitmap? = null
    private var coverJob: Job? = null

    /** The last metadata sent (a bitmap copied to the system at each setMetadata call). */
    private var metadataTrack: String? = null
    private var metadataArt: Bitmap? = null

    private val covers = object : android.util.LruCache<String, Bitmap>(COVER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate() {
        super.onCreate()
        AndroidPlayback.service = this
        createChannel()
        session = MediaSessionCompat(this, "Valen").apply {
            setCallback(callback)
            setSessionActivity(launchIntent())
            // A session without a playback state gives the lock screen no play button.
            setPlaybackState(PlaybackStateCompat.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 0f)
                .build())
            isActive = true
        }
        publishNow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start may be a startForegroundService(): Android kills the app unless
        // startForeground() follows at once, paused or not.
        startForegroundCompat()
        when (intent?.action) {
            ACTION_PLAY -> AndroidPlayback.controls?.play()
            ACTION_PAUSE -> AndroidPlayback.controls?.pause()
            ACTION_TOGGLE -> {
                val c = AndroidPlayback.controls
                if (AndroidPlayback.current?.isPlaying == true) c?.pause() else c?.play()
            }
            ACTION_NEXT -> AndroidPlayback.controls?.next()
            ACTION_PREVIOUS -> AndroidPlayback.controls?.previous()
            ACTION_STOP -> endSession()
        }
        publishNow()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onDestroy() {
        AndroidPlayback.service = null
        foreground = false
        runCatching { session.release() }
        scope.cancel()
        super.onDestroy()
    }

    /** The app closed from the recent apps: the service and its music stay (background playback). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (AndroidPlayback.current?.isPlaying != true) {
            endSession()
        }
    }

    /** Applies the pushed snapshot: metadata, state, queue and notification. Main thread. */
    internal fun publish() = main.post { publishNow() }

    /** Applies the pushed queue. Main thread. */
    internal fun publishQueue() = main.post { lastQueue = AndroidPlayback.queue; applyQueue() }

    /** Ends the session: foreground and notification go, the service stops. */
    internal fun endSession() = main.post {
        AndroidPlayback.ended()
        covered(null)
        holdCpu(false)
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foreground = false
        runCatching { session.isActive = false }
        stopSelf()
    }

    /** A partial wake lock while the page plays: Doze would otherwise stall its timers and loads. */
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun holdCpu(on: Boolean) {
        runCatching {
            if (on) {
                val lock = wakeLock ?: getSystemService(android.os.PowerManager::class.java)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Valen:playback")
                    .apply { setReferenceCounted(false) }
                    .also { wakeLock = it }
                lock.acquire(LOCK_TIMEOUT_MS)
            } else {
                wakeLock?.takeIf { it.isHeld }?.release()
            }
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun publishNow() {
        if (!::session.isInitialized) return
        val info = AndroidPlayback.current
        val now = android.os.SystemClock.elapsedRealtime()
        val rate = AndroidPlayback.rate
        // The coordinator sends its clock a few times a second; the session extrapolates the
        // position itself, so it is only told what it could not have guessed.
        val steady = info != null && foreground && lastNotifiedCover === cover && rate == lastRate &&
            AndroidPlayback.queue === lastQueue && AndroidPlayback.index == lastIndex &&
            lastState?.let { onCourse(it, info, now - lastStateAt, rate) } == true
        if (steady) return
        lastState = info
        lastStateAt = now
        lastRate = rate
        lastIndex = AndroidPlayback.index
        // Also while the next song starts (it is looked up over the network with the screen off).
        holdCpu(info?.isPlaying == true || info?.isBuffering == true)
        applyMetadata(info)
        applyState(info)
        if (AndroidPlayback.queue !== lastQueue) {
            lastQueue = AndroidPlayback.queue
            applyQueue()
        }
        val renotify = lastNotified?.let { info != null && !sameNotification(it, info) } ?: (info != null)
        if (info?.track?.artworkUrl != coverUrl || (cover == null && !info?.track?.artworkUrl.isNullOrBlank() && coverJob?.isActive != true)) {
            loadCover(info?.track?.artworkUrl)
        }
        if (renotify || !foreground || lastNotifiedCover !== cover) {
            val notification = build()
            lastNotified = info
            lastNotifiedCover = cover
            if (!foreground) startForegroundCompat(notification)
            else runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun sameNotification(a: PlayerSnapshot, b: PlayerSnapshot): Boolean =
        a.track?.key == b.track?.key && a.isPlaying == b.isPlaying && a.isBuffering == b.isBuffering

    private fun applyMetadata(info: PlayerSnapshot?) {
        val track = info?.track
        val art = cover
        if (track?.key == metadataTrack && art === metadataArt) return
        metadataTrack = track?.key
        metadataArt = art
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track?.id ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track?.title ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, track?.artworkUrl)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, info?.durationMs ?: track?.durationMs ?: 0L)
        if (art != null && !art.isRecycled) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
        }
        runCatching { session.setMetadata(builder.build()) }
    }

    private fun applyState(info: PlayerSnapshot?) {
        val state = when {
            info?.isBuffering == true -> PlaybackStateCompat.STATE_BUFFERING
            info?.isPlaying == true -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val active = if (AndroidPlayback.index >= 0) AndroidPlayback.index.toLong() else MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong()
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(ACTIONS)
            .setState(state, info?.positionMs ?: 0L, if (info?.isPlaying == true && info.isBuffering.not()) AndroidPlayback.rate else 0f)
            .setActiveQueueItemId(active)
            .build())
    }

    /** [next] is [last] carried on by its own clock for [elapsedMs]: same song and state, position within a second and a half. */
    private fun onCourse(last: PlayerSnapshot, next: PlayerSnapshot, elapsedMs: Long, rate: Float): Boolean {
        if (last.track?.key != next.track?.key || last.isPlaying != next.isPlaying || last.isBuffering != next.isBuffering ||
            last.durationMs != next.durationMs || last.error != next.error || last.canSeek != next.canSeek) return false
        val moving = last.isPlaying && !last.isBuffering
        val expected = last.positionMs + if (moving) (elapsedMs * rate).toLong() else 0L
        return kotlin.math.abs(expected - next.positionMs) < DRIFT_MS
    }

    private fun applyQueue() {
        val queue = AndroidPlayback.queue
        session.setQueueTitle(AndroidPlayback.queueTitle)
        session.setQueue(queue.mapIndexed { i, track ->
            MediaSessionCompat.QueueItem(MediaDescriptionCompat.Builder()
                .setMediaId(track.key)
                .setTitle(track.title)
                .setSubtitle(track.artist)
                .build(), i.toLong())
        }.takeIf { it.isNotEmpty() })
    }

    private fun loadCover(url: String?) {
        coverJob?.cancel()
        coverUrl = url
        cover = url?.let { covers.get(it) }?.takeIf { !it.isRecycled }
        if (url.isNullOrBlank() || cover != null) return
        coverJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetch(url) }
            if (bitmap != null) {
                covers.put(url, bitmap)
                if (url == coverUrl) {
                    cover = bitmap
                    publishNow()
                }
            }
        }
    }

    private fun covered(bitmap: Bitmap?) {
        cover = bitmap
        coverUrl = null
        lastNotified = null
        lastState = null
        metadataTrack = null
        metadataArt = null
    }

    /** The artwork at [url] as a bitmap of at most [COVER_MAX_PX] a side; null on failure. */
    private fun fetch(url: String): Bitmap? = runCatching {
        val bytes = when {
            url.startsWith("http") -> http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body.bytes() else null
            }
            url.startsWith("spotify:image:") -> {
                val resolved = "https://i.scdn.co/image/" + url.removePrefix("spotify:image:")
                http.newCall(Request.Builder().url(resolved).build()).execute().use { r ->
                    if (r.isSuccessful) r.body.bytes() else null
                }
            }
            else -> contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
        } ?: return null
        decode(bytes)
    }.getOrNull()

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_MAX_PX) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= COVER_MAX_PX) return bitmap
        val scale = COVER_MAX_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = AndroidPlayback.controls?.play() ?: Unit
        override fun onPause() = AndroidPlayback.controls?.pause() ?: Unit
        override fun onSkipToNext() = AndroidPlayback.controls?.next() ?: Unit
        override fun onSkipToPrevious() = AndroidPlayback.controls?.previous() ?: Unit
        override fun onSeekTo(pos: Long) = AndroidPlayback.controls?.seek(pos) ?: Unit
        override fun onStop() { endSession() }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            val event = androidx.core.content.IntentCompat
                .getParcelableExtra(mediaButtonEvent, Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
            if (event != null && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                    android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> { AndroidPlayback.controls?.next(); return true }
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> { AndroidPlayback.controls?.previous(); return true }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                        val c = AndroidPlayback.controls
                        if (AndroidPlayback.current?.isPlaying == true) c?.pause() else c?.play()
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(mediaButtonEvent)
        }
    }

    private fun build(): Notification {
        val info = AndroidPlayback.current
        val track: Track? = info?.track
        val art = cover?.takeIf { !it.isRecycled }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_valen_notification)
            .setContentTitle(track?.title ?: getString(R.string.valen_app_name))
            .setContentText(track?.artist ?: "")
            .setSubText(track?.album)
            .setLargeIcon(art)
            .setContentIntent(launchIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(action(ACTION_STOP))
            .setOngoing(info?.isPlaying == true)
            .addAction(R.drawable.ic_valen_previous, getString(R.string.valen_previous), action(ACTION_PREVIOUS))
            .addAction(
                if (info?.isPlaying == true) R.drawable.ic_valen_pause else R.drawable.ic_valen_play,
                getString(if (info?.isPlaying == true) R.string.valen_pause else R.string.valen_play),
                action(ACTION_TOGGLE),
            )
            .addAction(R.drawable.ic_valen_next, getString(R.string.valen_next), action(ACTION_NEXT))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun action(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun startForegroundCompat(notification: Notification = build()) {
        if (foreground) return
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
            foreground = true
        }.onFailure { android.util.Log.w("Valen", "foreground not started: ${it.message}") }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.valen_channel_playback), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        /** How far the position may wander from the session's own clock before it is told again. */
        private const val DRIFT_MS = 1_500L
        private const val ACTION_PLAY = "app.valen.action.PLAY"
        private const val ACTION_PAUSE = "app.valen.action.PAUSE"
        private const val ACTION_TOGGLE = "app.valen.action.TOGGLE"
        private const val ACTION_NEXT = "app.valen.action.NEXT"
        private const val ACTION_PREVIOUS = "app.valen.action.PREVIOUS"
        private const val ACTION_STOP = "app.valen.action.STOP"

        /** What the lock screen, a headset and Bluetooth may ask for. */
        private val ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM

        /** Artwork kept for the notification and the session: Android's media controls expect 640 px at most. */
        private const val COVER_MAX_PX = 640
        private const val COVER_CACHE_BYTES = 8 * 1024 * 1024
        /** A safety net against a lock leaked by a missed pause; every state refresh renews it. */
        private const val LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
