/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.SessionState
import app.valen.host.cdp.CdpHost
import app.valen.host.cdp.CdpPageHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException

/**
 * What a sign-in publishes while the browser under it is not there. Both cases were live bugs: a
 * probe during a sign-in's own browser replacement flashed "Offline" over a working login, and a
 * browser that had died showed the raw `Failed to connect to /127.0.0.1:9333` from the HTTP client.
 */
@Timeout(10)
class DesktopSignInTest {

    @Test
    fun `native accounts are restored from cookies without opening service pages`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tabs = FakeTabs(false).apply { cookies = setOf("SAPISID", "oauth_token") }
        try {
            val youtube = YtMusicSignIn(tabs, scope)
            val soundcloud = SoundCloudSignIn(tabs, scope)
            assertEquals(SessionState.Connected(), youtube.refresh())
            assertEquals(SessionState.Connected(), soundcloud.refresh())
            // No page exists in FakeTabs: either probe would throw if it tried to reopen one.
            assertEquals(2, tabs.recoveries)
            tabs.cookies = emptySet()
            assertEquals(SessionState.SignedOut, youtube.refresh())
            assertEquals(SessionState.SignedOut, soundcloud.refresh())
        } finally { scope.cancel() }
    }

    @Test
    fun `a browser that cannot recover is unavailable rather than signed out`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tabs = FakeTabs(false).apply { backgroundReady = false; cookies = setOf("SAPISID") }
        try {
            val signIn = YtMusicSignIn(tabs, scope)
            assertTrue(signIn.refresh() is SessionState.Unavailable)
            tabs.backgroundReady = true
            assertEquals(SessionState.Connected(), signIn.refresh())
        } finally { scope.cancel() }
    }

    @Test
    fun `a failed cookie deletion must not claim a successful signout`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tabs = FakeTabs(false).apply { cookies = setOf("SAPISID", "oauth_token") }
        try {
            for (signIn in listOf(YtMusicSignIn(tabs, scope), SoundCloudSignIn(tabs, scope))) {
                assertEquals(SessionState.Connected(), signIn.refresh())
                signIn.disconnect() // The fake page refuses cookie deletion.
                assertTrue(signIn.state.value is SessionState.Unavailable)
                assertEquals(SessionState.Connected(), signIn.refresh())
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `a probe cannot publish an old account after disconnect or shutdown`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val release = CompletableDeferred<Unit>()
            val signIn = object : DesktopSignIn(FakeTabs(false), scope) {
                override suspend fun probe(): SessionState { release.await(); return SessionState.Connected() }
                override suspend fun connect() {}
                override suspend fun disconnect() = signOut {}
            }
            val probe = async(start = CoroutineStart.UNDISPATCHED) { signIn.refresh() }
            val signout = async(start = CoroutineStart.UNDISPATCHED) { signIn.disconnect() }
            assertFalse(signout.isCompleted, "deletion waits for the outstanding probe")
            release.complete(Unit)
            probe.await()
            signout.await()
            assertEquals(SessionState.SignedOut, signIn.state.value)
            signIn.close()
            assertEquals(SessionState.SignedOut, signIn.refresh(), "shutdown must not start another probe")
        } finally { scope.cancel() }
    }

    @Test
    fun `a probe during a browser start keeps the last answer`() = withSignIn(browserStarting = true) { signIn ->
        signIn.markConnected("someone")
        // The page cannot answer while the browser is being replaced: that is not a sign-out.
        val next = runBlocking { signIn.refresh() }
        assertEquals(SessionState.Connected("someone"), next, "the last answer must stand")
        assertEquals(SessionState.Connected("someone"), signIn.state.value, "published a change")
    }

    @Test
    fun `a browser that is gone is not a raw connection error`() = withSignIn(browserStarting = false) { signIn ->
        val next = runBlocking { signIn.refresh() }
        assertEquals(
            SessionState.Unavailable("The browser is not running: press Connect to open it again"),
            next,
        )
    }

    /**
     * The reload that turns a tab left on Spotify's sign-in page into the player is owed once, and
     * it used to be spent on the wrong evidence: while the document was still loading the page
     * check answered "not the login page", so the transition counted as done before it could
     * happen. A login that finished in another tab then left this tab behind for good.
     */
    @Test
    fun `a page that is still loading keeps the reload eligible`() = runBlocking {
        assertEquals(PageKind.UNKNOWN, pageKindOf(null), "an unreadable page is not a player")
        assertEquals(PageKind.UNKNOWN, pageKindOf("\"unknown\""), "a loading page is not a player")
        var reloaded = false
        assertFalse(
            reloadDone(PageKind.UNKNOWN) { reloaded = true; true },
            "nothing was decided, so the reload is still owed",
        )
        assertFalse(reloaded, "a page that is still loading must not be reloaded yet")
    }

    @Test
    fun `the reload counts only once it was actually sent`() = runBlocking {
        assertEquals(PageKind.LOGIN, pageKindOf("\"login\""))
        assertFalse(reloadDone(PageKind.LOGIN) { false }, "a failed reload stays eligible")
        assertTrue(reloadDone(PageKind.LOGIN) { true }, "a sent reload is the transition")
    }

    @Test
    fun `a tab already on the player owes no reload`() = runBlocking {
        assertEquals(PageKind.PLAYER, pageKindOf("\"player\""))
        var reloaded = false
        assertTrue(reloadDone(PageKind.PLAYER) { reloaded = true; true })
        assertFalse(reloaded, "the player page was reloaded for nothing")
    }

    private fun withSignIn(browserStarting: Boolean, body: (TestSignIn) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            body(TestSignIn(FakeTabs(browserStarting), scope))
        } finally {
            scope.cancel()
        }
    }

    /** A sign-in whose probe is the test's, so the publishing rules can be checked on their own. */
    private class TestSignIn(tabs: SignInTabs, scope: CoroutineScope) : DesktopSignIn(tabs, scope) {
        override suspend fun probe(): SessionState = throw IOException("Failed to connect to /127.0.0.1:9333")
        override suspend fun connect() {}
        override suspend fun disconnect() {}

        fun markConnected(name: String?) {
            mutableState.value = SessionState.Connected(name)
        }
    }

    /** The tabs, as far as [DesktopSignIn] can tell: only the browser's state is ever read here. */
    private class FakeTabs(override val browserStarting: Boolean) : SignInTabs {
        var cookies: Set<String> = emptySet()
        var backgroundReady = true
        var recoveries = 0
        override suspend fun ensureVisible(): Boolean = false
        override suspend fun ensureBackground(): Boolean { recoveries++; return backgroundReady }
        override suspend fun cookieNames(url: String): Set<String> = cookies
        override val windowError: String? = null
        override suspend fun spotify(): CdpHost = unsupported()
        override suspend fun youtubeMusic(): CdpPageHost = unsupported()
        override suspend fun soundCloud(): CdpPageHost = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("no page in this test")
    }
}
