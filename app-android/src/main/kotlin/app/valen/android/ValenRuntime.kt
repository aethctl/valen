/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android, `MyspotiRuntime.kt`.
 * See NOTICE.
 */
package app.valen.android

import android.content.Context
import android.view.ViewGroup
import app.valen.core.PlaybackCoordinator
import app.valen.core.ProviderId
import app.valen.core.SettingsStore
import app.valen.host.android.AndroidPlayback
import app.valen.host.android.AndroidSignIn
import app.valen.host.android.HiddenPageHost
import app.valen.host.android.WebPlayerHost
import app.valen.providers.soundcloud.SoundcloudProvider
import app.valen.providers.spotify.SpotifyProvider
import app.valen.providers.ytmusic.YtmusicProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The app's single runtime: the hidden WebViews, one sign-in per service, the three providers, the
 * one coordinator and the media session behind them. Built on the main thread (a WebView
 * requirement), usually from the application's `onCreate`, and kept for the life of the process so
 * music outlives activities.
 */
object ValenRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var started = false

    lateinit var coordinator: PlaybackCoordinator
        private set

    lateinit var webPlayer: WebPlayerHost
        private set

    private val hidden = LinkedHashMap<ProviderId, HiddenPageHost>()
    private val signIns = LinkedHashMap<ProviderId, AndroidSignIn>()

    val isStarted: Boolean get() = started

    /** The hidden page of a service (YouTube Music, SoundCloud), when it was built. */
    fun hiddenHost(id: ProviderId): HiddenPageHost? = hidden[id]

    /** The sign-in of a service: its state is the session the UI shows. */
    fun signIn(id: ProviderId): AndroidSignIn? = signIns[id]

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val settings = SettingsStore(
            read = { File(app.filesDir, "settings.json").takeIf { it.isFile }?.readText() },
            write = { text -> runCatching { File(app.filesDir, "settings.json").writeText(text) } },
        )

        webPlayer = WebPlayerHost(app)
        val ytmHost = HiddenPageHost(
            app, "valen-ytmusic",
            AndroidSignIn.YTM_ORIGIN, AndroidSignIn.YTM_SESSION_URLS, WebPlayerHost.DESKTOP_UA,
        )
        val scHost = HiddenPageHost(
            app, "valen-soundcloud",
            AndroidSignIn.SC_ORIGIN, AndroidSignIn.SC_SESSION_URLS, WebPlayerHost.DESKTOP_UA,
        )
        hidden[ProviderId.YOUTUBE_MUSIC] = ytmHost
        hidden[ProviderId.SOUNDCLOUD] = scHost

        val spotifySignIn = AndroidSignIn.spotify(webPlayer, scope)
        val ytmSignIn = AndroidSignIn.ytmusic(ytmHost, scope)
        val scSignIn = AndroidSignIn.soundcloud(scHost, scope)
        signIns[ProviderId.SPOTIFY] = spotifySignIn
        signIns[ProviderId.YOUTUBE_MUSIC] = ytmSignIn
        signIns[ProviderId.SOUNDCLOUD] = scSignIn

        val spotify = SpotifyProvider(webPlayer, spotifySignIn, scope, http)
        // The vendored API injects the page script after each load; the host says when.
        webPlayer.onPageLoaded = { scope.launch { runCatching { spotify.api.onPageLoaded() } } }
        webPlayer.onPageStarting = { runCatching { spotify.api.onPageStarting() } }

        val ytmusic = YtmusicProvider(ytmHost, ytmSignIn, scope, ytmHost, http)
        val soundcloud = SoundcloudProvider(scHost, scSignIn, scope)

        coordinator = PlaybackCoordinator(
            providers = listOf(spotify, ytmusic, soundcloud),
            scope = scope,
            settingsStore = settings,
        )

        AndroidPlayback.controls = object : AndroidPlayback.Controls {
            override fun play() {
                scope.launch { if (coordinator.snapshot.value?.isPlaying != true) coordinator.toggle() }
            }

            override fun pause() {
                scope.launch { if (coordinator.snapshot.value?.isPlaying == true) coordinator.toggle() }
            }

            override fun next() {
                scope.launch { coordinator.next() }
            }

            override fun previous() {
                scope.launch { coordinator.previous() }
            }

            override fun seek(positionMs: Long) {
                scope.launch { coordinator.seek(positionMs) }
            }
        }

        scope.launch {
            coordinator.snapshot.collect { AndroidPlayback.setSnapshot(app, it) }
        }
        scope.launch {
            combine(coordinator.queue, coordinator.index) { queue, index -> queue to index }
                .collect { (queue, index) -> AndroidPlayback.setQueue(app, queue, index) }
        }

        // The Web Player is loaded at start: its sign-in check and the first Spotify play need it.
        webPlayer.load()
    }

    /**
     * Keeps every hidden WebView attached to a window (a WebView that never was runs no media). The
     * activity passes a 1 px container; a page that outlives the activity keeps playing through the
     * foreground service.
     */
    fun attachHidden(container: ViewGroup) {
        if (!started) return
        runCatching { webPlayer.attach(container, visible = false) }
        hidden.values.forEach { runCatching { it.attach(container) } }
    }

    /**
     * The activity's window is going: the pages leave it (the view tree would keep the activity
     * alive), but [AwakeWebView] holds Chromium's detach back, so the music keeps playing.
     */
    fun detachHidden(container: ViewGroup) {
        if (!started) return
        runCatching { webPlayer.detach(container) }
        hidden.values.forEach { runCatching { it.detach(container) } }
    }
}
