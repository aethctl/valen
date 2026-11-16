/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.host.cdp.CdpHost
import app.valen.host.cdp.CdpPageHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The desktop host: one Chrome with Valen's private profile, one CDP host on the Spotify Web Player
 * tab, one page host per page-based service (YouTube Music, SoundCloud), and the three sign-ins
 * built on them.
 *
 * Everything is attached lazily: an app that starts with Chrome missing still runs, and a provider
 * whose tab is never opened never opens one. [close] stops what this object started.
 */
class DesktopHosts(
    val scope: CoroutineScope,
    val chrome: ChromeLauncher = ChromeLauncher(),
) : SignInTabs, AutoCloseable {

    val cdpUrl: String get() = chrome.cdpUrl

    /**
     * Bumped whenever the browser is replaced (a headless engine restarted as a window for a
     * sign-in). An adapter built against the old browser is stale from that moment.
     */
    val generation = kotlinx.coroutines.flow.MutableStateFlow(0)

    val spotifySignIn = SpotifySignIn(this, scope)
    val ytMusicSignIn = YtMusicSignIn(this, scope)
    val soundCloudSignIn = SoundCloudSignIn(this, scope)

    private val lock = Mutex()
    private var spotifyHost: CdpHost? = null
    private var ytMusicHost: CdpPageHost? = null
    private var soundCloudHost: CdpPageHost? = null

    /**
     * Launches (or reuses) Chrome and attaches the Spotify tab; false when Chrome could not start.
     * [visible] is for a sign-in: the user has to see the page they log in on.
     */
    suspend fun start(visible: Boolean = false): Boolean {
        val before = chrome.launchCount
        val ok = chrome.start(visible)
        if (!ok) return false
        if (chrome.launchCount != before) {
            // The browser was replaced: every host of the old one is dead.
            dropHosts()
            generation.value = generation.value + 1
        }
        runCatching { spotify() }
        spotifySignIn.startPolling()
        return true
    }

    /**
     * Restarts the browser with a window, for a sign-in that a person has to complete. False when
     * no window could be opened; [windowError] then says why.
     */
    override suspend fun ensureVisible(): Boolean {
        val before = chrome.launchCount
        if (!chrome.start(visible = true)) return false
        if (chrome.launchCount != before) {
            dropHosts()
            generation.value = generation.value + 1
        }
        return true
    }

    override val windowError: String? get() = chrome.lastError

    /** True while a browser is coming up; a probe then has no page to ask. */
    override val browserStarting: Boolean get() = chrome.starting

    private suspend fun dropHosts() = lock.withLock { closeHosts() }

    private fun closeHosts() {
        spotifyHost?.close()
        ytMusicHost?.close()
        soundCloudHost?.close()
        spotifyHost = null
        ytMusicHost = null
        soundCloudHost = null
    }

    override suspend fun spotify(): CdpHost = lock.withLock {
        spotifyHost ?: CdpHost.connect(cdpUrl = cdpUrl).also { spotifyHost = it }
    }

    override suspend fun youtubeMusic(): CdpPageHost = lock.withLock {
        ytMusicHost ?: CdpPageHost.connect(
            origin = YtMusicSignIn.ORIGIN,
            startUrl = YtMusicSignIn.HOME,
            sessionUrls = YOUTUBE_SESSION_URLS,
            cdpUrl = cdpUrl,
        ).also { ytMusicHost = it }
    }

    override suspend fun soundCloud(): CdpPageHost = lock.withLock {
        soundCloudHost ?: CdpPageHost.connect(
            origin = SoundCloudSignIn.ORIGIN,
            startUrl = SoundCloudSignIn.HOME,
            sessionUrls = SOUNDCLOUD_SESSION_URLS,
            cdpUrl = cdpUrl,
        ).also { soundCloudHost = it }
    }

    /** Why Chrome is not there, when [start] returned false. */
    val startError: String? get() = chrome.lastError

    /**
     * The browser's cookies for [url], read through the Web Player tab that already exists. Only a
     * reachable browser answers: this never launches Chrome, so an account read cannot start one.
     * Null also means no browser is running, which is the same thing to a caller (no session).
     */
    suspend fun cookiesFor(url: String): String? =
        runCatching { spotify().cookieHeader(url) }.getOrNull()

    /**
     * Whether the browser Valen plays Spotify through still answers. A browser that is being
     * started (or replaced by a sign-in) counts as alive: the port is briefly free during a
     * restart, and taking that for a crash would declare Spotify closed in the middle of a login.
     */
    suspend fun browserAlive(): Boolean = chrome.starting || chrome.probe()

    override fun close() {
        spotifySignIn.close()
        ytMusicSignIn.close()
        soundCloudSignIn.close()
        closeHosts()
        chrome.close()
    }

    companion object {
        /** The sites whose cookies hold a YouTube account (all deleted at sign-out). */
        val YOUTUBE_SESSION_URLS = listOf(
            YtMusicSignIn.ORIGIN, "https://www.youtube.com", "https://youtube.com",
            "https://accounts.google.com", "https://www.google.com", "https://google.com",
        )

        /** The sites whose cookies hold a SoundCloud session (all deleted at sign-out). */
        val SOUNDCLOUD_SESSION_URLS = listOf(
            SoundCloudSignIn.ORIGIN, "https://www.soundcloud.com", "https://api.soundcloud.com",
            "https://secure.soundcloud.com",
        )
    }
}
