/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.ProviderSignIn
import app.valen.core.SessionState
import app.valen.host.cdp.CdpHost
import app.valen.host.cdp.CdpPageHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The tabs the desktop sign-ins talk to. [DesktopHosts] owns the Chrome and the CDP hosts; the
 * sign-ins ask for a provider's tab and get the same one every time (attached lazily, so an app
 * started without Chrome still opens).
 */
interface SignInTabs {
    /**
     * Puts a *window* on screen when the browser runs headless: a person cannot log in to a page
     * they cannot see. Playback never calls this. Returns false when no window could be opened;
     * [windowError] then says why, because a Connect that does nothing is a broken button.
     */
    suspend fun ensureVisible(): Boolean

    /** Why the last [ensureVisible] could not open a window, when it could not. */
    val windowError: String?

    /** The Spotify Web Player tab: the tab the adapter plays through. */
    suspend fun spotify(): CdpHost

    suspend fun youtubeMusic(): CdpPageHost

    suspend fun soundCloud(): CdpPageHost
}

/**
 * Opens the window a sign-in happens in, or returns the state that says why none could be opened.
 * Every `connect` below starts with this: a sign-in that cannot show its page must say so instead
 * of leaving the person staring at a button that did nothing.
 */
private suspend fun SignInTabs.requireWindow(label: String): SessionState.Unavailable? =
    if (ensureVisible()) null
    else SessionState.Unavailable(windowError ?: "Could not open a browser window to sign $label in")

/**
 * Sign-in on the desktop: the service's own page in the private Chrome, exactly as the user would
 * use it. No credentials pass through Valen; the session is what the browser keeps.
 *
 * [state] is polled rather than pushed, because the page under it is a web app that says nothing
 * when its account changes: a cookie appearing (YouTube Music, SoundCloud) or the player replacing
 * the login button (Spotify) is the whole signal MPRIS and the queue need.
 */
abstract class DesktopSignIn(
    protected val scope: CoroutineScope,
    private val pollMs: Long = 4_000,
) : ProviderSignIn {

    protected val mutableState = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private var poller: Job? = null

    /** One probe of the service, run while the sign-in polls. */
    protected abstract suspend fun probe(): SessionState

    /** Runs [probe] now and publishes the answer. */
    suspend fun refresh(): SessionState {
        val next = try {
            probe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionState.Unavailable(e.message ?: "The sign-in page is not reachable")
        }
        mutableState.value = next
        return next
    }

    /** Polls until [close]; safe to call more than once. */
    fun startPolling() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                refresh()
                delay(pollMs)
            }
        }
    }

    fun stopPolling() {
        poller?.cancel()
        poller = null
    }

    /** Signs out in the browser and stops the poller. */
    fun close() {
        stopPolling()
        mutableState.value = SessionState.SignedOut
    }
}

/**
 * Spotify: the Web Player tab signed in. Connected when the page is open.spotify.com and no login
 * button is on it, which is exactly what the player shows a signed-in account.
 */
class SpotifySignIn(
    private val tabs: SignInTabs,
    scope: CoroutineScope,
    pollMs: Long = 4_000,
) : DesktopSignIn(scope, pollMs) {

    override suspend fun connect() {
        mutableState.value = SessionState.Connecting
        // The login has to happen in a window, even though playback does not need one.
        tabs.requireWindow("in to Spotify")?.let { mutableState.value = it; return }
        val host = runCatching { tabs.spotify() }.getOrElse {
            mutableState.value = SessionState.Unavailable(it.message ?: "Spotify did not open")
            return
        }
        // A window that is already up is not necessarily in front: raise it on the login page.
        runCatching { host.activate() }
        refresh()
        startPolling()
    }

    override suspend fun disconnect() {
        val host = runCatching { tabs.spotify() }.getOrNull()
        runCatching { host?.evaluate("location.href = 'https://accounts.spotify.com/logout'") }
        mutableState.value = SessionState.SignedOut
    }

    override suspend fun probe(): SessionState {
        val host = tabs.spotify()
        val signedIn = host.evaluateValue(PROBE) == "true"
        if (!signedIn) return SessionState.SignedOut
        val name = runCatching {
            host.evaluateValue("document.querySelector('[data-testid=\"user-widget-link\"]')?.getAttribute('aria-label') ?? 'null'")
                ?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).toString() }
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
        return SessionState.Connected(name)
    }

    private companion object {
        /**
         * The player, not a login: no `login-button` in the page's own markup, the document is
         * loaded, and the page is the Web Player's own host.
         */
        const val PROBE =
            "(function(){try{return document.readyState === 'complete' && location.host.endsWith('open.spotify.com')" +
                " && document.querySelector('[data-testid=\"login-button\"]') === null}catch(e){return false}})()"
    }
}

/**
 * YouTube Music: connected when the browser holds the account cookie the web app's auth header is
 * made from (`SAPISID`, or its `__Secure-3PAPISID` twin).
 */
class YtMusicSignIn(
    private val tabs: SignInTabs,
    scope: CoroutineScope,
    pollMs: Long = 4_000,
) : DesktopSignIn(scope, pollMs) {

    override suspend fun connect() {
        mutableState.value = SessionState.Connecting
        tabs.requireWindow("in to YouTube Music")?.let { mutableState.value = it; return }
        val page = tabs.youtubeMusic()
        page.navigate(HOME)
        runCatching { page.activate() }
        refresh()
        startPolling()
    }

    override suspend fun disconnect() {
        val page = runCatching { tabs.youtubeMusic() }.getOrNull()
        runCatching { page?.clear() }
        runCatching { page?.navigate(HOME) }
        mutableState.value = SessionState.SignedOut
    }

    override suspend fun probe(): SessionState {
        val names = tabs.youtubeMusic().cookieNames(ORIGIN)
        return if (names.any { it in SIGN_IN_COOKIES }) SessionState.Connected() else SessionState.SignedOut
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        const val HOME = "$ORIGIN/"
        private val SIGN_IN_COOKIES = setOf("SAPISID", "__Secure-3PAPISID")
    }
}

/** SoundCloud: connected when the browser holds its `oauth_token` cookie. */
class SoundCloudSignIn(
    private val tabs: SignInTabs,
    scope: CoroutineScope,
    pollMs: Long = 4_000,
) : DesktopSignIn(scope, pollMs) {

    override suspend fun connect() {
        mutableState.value = SessionState.Connecting
        tabs.requireWindow("in to SoundCloud")?.let { mutableState.value = it; return }
        val page = tabs.soundCloud()
        page.navigate(HOME)
        runCatching { page.activate() }
        refresh()
        startPolling()
    }

    override suspend fun disconnect() {
        val page = runCatching { tabs.soundCloud() }.getOrNull()
        runCatching { page?.clear() }
        runCatching { page?.navigate(HOME) }
        mutableState.value = SessionState.SignedOut
    }

    override suspend fun probe(): SessionState {
        val names = tabs.soundCloud().cookieNames(ORIGIN)
        return if (SIGN_IN_COOKIE in names) SessionState.Connected() else SessionState.SignedOut
    }

    companion object {
        const val ORIGIN = "https://soundcloud.com"
        const val HOME = "$ORIGIN/"
        private const val SIGN_IN_COOKIE = "oauth_token"
    }
}
