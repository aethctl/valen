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
import java.io.IOException

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

    /**
     * True while the browser is being launched or replaced. A probe during that is meaningless
     * (there is no page yet) and must not be published: it would flash "Offline" over a sign-in
     * that is working.
     */
    val browserStarting: Boolean

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
 * when its account changes: a session cookie appearing or disappearing is the whole signal MPRIS
 * and the queue need. (Spotify's `sp_dc`, YouTube Music's `SAPISID`, SoundCloud's `oauth_token`.)
 */
abstract class DesktopSignIn(
    protected val tabs: SignInTabs,
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
        // A browser being launched or replaced has no page to answer yet. Publishing that as a
        // failure would flash "Offline" over a sign-in that is working, so the last answer stands.
        if (tabs.browserStarting) return mutableState.value
        val next = try {
            probe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // The browser is gone (closed, replaced by a crash). "Failed to connect to
            // /127.0.0.1:9333" is not something a person can act on.
            SessionState.Unavailable("The browser is not running: press Connect to open it again")
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
 * Spotify: the Web Player tab signed in. Connected when the browser holds Spotify's account
 * cookie (`sp_dc`), the same signal the Web Player itself trusts.
 *
 * The page's markup was the old signal ("no login button on open.spotify.com"). It read the
 * "Unsupported browser" page as connected: that page is on open.spotify.com, has no login button
 * and no player either, so the tile claimed an account while every play timed out. A cookie is
 * what a session actually is.
 */
class SpotifySignIn(
    tabs: SignInTabs,
    scope: CoroutineScope,
    pollMs: Long = 4_000,
) : DesktopSignIn(tabs, scope, pollMs) {

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
        // Leaving the page to accounts.spotify.com/logout was not a sign-out: the Web Player's tab
        // kept the session's cookies, so the next probe (and the next play) was still signed in.
        val cleared = host != null && runCatching {
            host.clearCookies(SESSION_URLS)
            host.navigate(HOME)
        }.isSuccess
        if (!cleared) {
            // Do not claim a sign-out that did not happen; the browser still has the account.
            mutableState.value = SessionState.Unavailable(
                "Could not clear Spotify's session in the browser: quit its window and try again",
            )
            return
        }
        mutableState.value = SessionState.SignedOut
    }

    override suspend fun probe(): SessionState {
        val host = tabs.spotify()
        if (ACCOUNT_COOKIE !in host.cookieNames(HOME)) {
            wasConnected = false
            return SessionState.SignedOut
        }
        // Signed in, but the tab may still be sitting on Spotify's sign-in page: the login can
        // finish in a page of its own (accounts.spotify.com, a popup) and leave this tab behind.
        // The cookies are now there, so one reload turns this tab into the player the bridge needs.
        if (!wasConnected) {
            wasConnected = reloadDone(host.pageKind()) { runCatching { host.reload() }.isSuccess }
        }
        val name = runCatching {
            host.evaluateValue("document.querySelector('[data-testid=\"user-widget-link\"]')?.getAttribute('aria-label') ?? 'null'")
                ?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).toString() }
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
        return SessionState.Connected(name)
    }

    /** Whether an account was already seen: that transition is what earns the one reload. */
    @Volatile private var wasConnected = false

    private companion object {
        /** `/preferences#login` is where an account-less Web Player tab lands; the account cookie is the truth. */
        const val HOME = "https://open.spotify.com/"

        /** Spotify's own account cookie. Its value never leaves the browser. */
        const val ACCOUNT_COOKIE = "sp_dc"

        /** Every site whose cookies hold a Spotify session (all deleted at sign-out). */
        val SESSION_URLS = listOf(
            "https://open.spotify.com", "https://accounts.spotify.com",
            "https://www.spotify.com", "https://spotify.com", "https://api.spotify.com",
        )
    }
}

/**
 * What the Web Player's tab is showing. [UNKNOWN] is deliberately not "not the login page": the
 * document can still be loading (its `readyState` is then not `complete`), and a page that cannot
 * be read is no evidence either. Treating either as "already a player" spent the one reload before
 * the sign-in page had even rendered, so a login that finished in another tab left this one behind.
 */
internal enum class PageKind { LOGIN, PLAYER, UNKNOWN }

/** The page kind from the JSON [CdpHost.evaluateValue] returned; anything unexpected is unknown. */
internal fun pageKindOf(value: String?): PageKind = when (value) {
    "\"login\"" -> PageKind.LOGIN
    "\"player\"" -> PageKind.PLAYER
    else -> PageKind.UNKNOWN
}

/**
 * Which page this tab is on. Used to decide whether one reload is owed after an account appeared in
 * the browser (the login may have happened in another tab, leaving this one on the sign-in screen
 * with no player behind it).
 */
private suspend fun CdpHost.pageKind(): PageKind = pageKindOf(
    runCatching {
        evaluateValue(
            // A page with Valen's bridge in it is the player, whatever its route: the bridge parks
            // the Web Player on /preferences once it boots (the light page; see player-bridge.js
            // IDLE_ROUTE), and reading that route as "the sign-in page" reloaded the player at
            // every start, killing a song started in the first seconds.
            "(function(){try{return document.readyState !== 'complete' ? 'unknown' : (window.Myspoti ? 'player' :" +
                " (location.pathname.indexOf('/login') >= 0" +
                " || location.pathname.indexOf('/preferences') >= 0" +
                " || document.querySelector('[data-testid=\"login-button\"]') !== null ? 'login' : 'player'))}catch(e){return 'unknown'}})()",
        )
    }.getOrNull(),
)

/**
 * Whether the one reload owed after an account appeared has been done. A reload only counts once it
 * was actually sent; a page that is still loading, could not be read, or whose reload failed stays
 * eligible, so a login that finishes in another tab is picked up by a later probe instead of being
 * written off by the first one.
 */
internal suspend fun reloadDone(kind: PageKind, reload: suspend () -> Boolean): Boolean = when (kind) {
    PageKind.LOGIN -> reload()
    PageKind.PLAYER -> true
    PageKind.UNKNOWN -> false
}

/**
 * YouTube Music: connected when the browser holds the account cookie the web app's auth header is
 * made from (`SAPISID`, or its `__Secure-3PAPISID` twin).
 */
class YtMusicSignIn(
    tabs: SignInTabs,
    scope: CoroutineScope,
    pollMs: Long = 4_000,
) : DesktopSignIn(tabs, scope, pollMs) {

    override suspend fun connect() {
        mutableState.value = SessionState.Connecting
        tabs.requireWindow("in to YouTube Music")?.let { mutableState.value = it; return }
        // A page that cannot be opened is a Connect that must say so, not an exception thrown at
        // the UI (Spotify already did this; the other two left the button looking dead).
        val page = runCatching { tabs.youtubeMusic() }.getOrElse {
            mutableState.value = SessionState.Unavailable(it.message ?: "YouTube Music did not open")
            return
        }
        // A page whose socket is gone cannot be signed in on. Say so and stop: refreshing would
        // only repeat the same failure, and a poller started here could never recover.
        try {
            page.navigate(HOME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.value = SessionState.Unavailable(e.message ?: "YouTube Music did not open")
            return
        }
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
    tabs: SignInTabs,
    scope: CoroutineScope,
    pollMs: Long = 4_000,
) : DesktopSignIn(tabs, scope, pollMs) {

    override suspend fun connect() {
        mutableState.value = SessionState.Connecting
        tabs.requireWindow("in to SoundCloud")?.let { mutableState.value = it; return }
        val page = runCatching { tabs.soundCloud() }.getOrElse {
            mutableState.value = SessionState.Unavailable(it.message ?: "SoundCloud did not open")
            return
        }
        // See `YtMusicSignIn.connect`: a navigation that cannot be sent is the whole failure.
        try {
            page.navigate(HOME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.value = SessionState.Unavailable(e.message ?: "SoundCloud did not open")
            return
        }
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
