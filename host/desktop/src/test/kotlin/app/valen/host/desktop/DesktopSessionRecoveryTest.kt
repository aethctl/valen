/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.SessionState
import app.valen.host.cdp.CdpHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

/** Optional real-browser test: isolated profile, dummy cookies, no service page or account login. */
@EnabledIfEnvironmentVariable(named = "VALEN_TEST_CHROME_BIN", matches = ".+")
@Timeout(90)
class DesktopSessionRecoveryTest {
    @Test
    fun `closing Chrome preserves cookies and recovers native accounts without a window`() = runBlocking {
        val profile = Files.createTempDirectory("valen-session-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chrome = ChromeLauncher(
            port = ServerSocket(0).use { it.localPort },
            profile = profile,
            binary = Path.of(System.getenv("VALEN_TEST_CHROME_BIN")),
            startUrl = "about:blank",
        )
        val hosts = DesktopHosts(scope, chrome)
        try {
            assertTrue(hosts.start(), chrome.lastError)
            hosts.spotifySignIn.stopPolling()
            hosts.ytMusicSignIn.stopPolling()
            hosts.soundCloudSignIn.stopPolling()
            val page = CdpHost.connect(cdpUrl = chrome.cdpUrl, startUrl = "about:blank")
            try {
                page.setCookies(listOf("SAPISID" to "valen-test-not-a-real-session"), ".youtube.com")
                page.setCookies(listOf("oauth_token" to "valen-test-not-a-real-session"), ".soundcloud.com")
            } finally { page.close() }

            assertEquals(SessionState.Connected(), hosts.ytMusicSignIn.refresh())
            assertEquals(SessionState.Connected(), hosts.soundCloudSignIn.refresh())
            val generation = hosts.generation.value
            assertTrue(chrome.stopBrowser())
            assertTrue(hosts.ensureBackground(), chrome.lastError)
            assertTrue(hosts.generation.value > generation)
            assertEquals(true, chrome.isHeadless())
            // These are fresh reads from the restarted browser, not cached Connected values.
            assertTrue("SAPISID" in hosts.cookieNames(YtMusicSignIn.ORIGIN))
            assertTrue("oauth_token" in hosts.cookieNames(SoundCloudSignIn.ORIGIN))
            assertEquals(SessionState.Connected(), hosts.ytMusicSignIn.refresh())
            assertEquals(SessionState.Connected(), hosts.soundCloudSignIn.refresh())

            hosts.close()
            assertFalse(hosts.ensureBackground(), "shutdown was followed by another launch")
        } finally {
            hosts.close()
            scope.cancel()
            profile.toFile().deleteRecursively()
        }
    }
}
