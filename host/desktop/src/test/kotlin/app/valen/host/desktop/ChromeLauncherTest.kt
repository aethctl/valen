/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.TimeUnit

/**
 * The launcher reads the browser's mode from its process, never from `/json/version`: Valen starts
 * a headless engine with an ordinary user agent (the Web Player refuses a headless one), so the
 * endpoint always says "Chrome". Reading it there made a sign-in believe a window was already up,
 * and Connect did nothing at all.
 *
 * A stand-in process is used instead of Chrome: what is under test is the identification of the
 * browser on the port and what a sign-in may do to it, not the browser.
 */
class ChromeLauncherTest {

    @Test
    fun `a headless browser on the port is read as headless`() {
        val port = 39331
        val browser = fakeBrowser(port, headless = true)
        try {
            assertEquals(true, awaitMode(ChromeLauncher(port = port)) { it == true }, "headless")
        } finally {
            browser.destroyForcibly()
        }
    }

    @Test
    fun `a browser with a window on the port is read as visible`() {
        val port = 39332
        val browser = fakeBrowser(port, headless = false)
        try {
            assertEquals(false, awaitMode(ChromeLauncher(port = port)) { it == false }, "visible")
        } finally {
            browser.destroyForcibly()
        }
    }

    @Test
    fun `a port with no browser behind it says nothing`() = runBlocking {
        assertNull(ChromeLauncher(port = 39333).isHeadless(), "no process")
    }

    @Test
    fun `a sign-in can stop a headless browser it did not start`() = runBlocking {
        val port = 39334
        val browser = fakeBrowser(port, headless = true)
        // Never start()ed: this is the browser an earlier Valen run left behind.
        val launcher = ChromeLauncher(port = port)
        try {
            assertEquals(true, awaitMode(launcher) { it == true }, "headless before")
            assertTrue(launcher.stopBrowser(), "stopped")
            assertFalse(browser.isAlive, "process gone")
            assertNull(launcher.isHeadless(), "nothing left on the port")
        } finally {
            browser.destroyForcibly()
        }
    }

    /** Polls [isHeadless] until it satisfies [matches]: a new process takes a moment to appear. */
    private fun awaitMode(launcher: ChromeLauncher, matches: (Boolean?) -> Boolean): Boolean? {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
        var seen: Boolean? = null
        while (System.currentTimeMillis() < deadline) {
            seen = runBlocking { launcher.isHeadless() }
            if (matches(seen)) return seen
            Thread.sleep(50)
        }
        fail("the launcher never reported the browser; last answer was $seen")
    }

    /**
     * A process whose command line looks like this launcher's browser: a Chromium-family argv[0]
     * and the DevTools port. `exec -a` gives it that name without needing Chrome on the machine.
     */
    private fun fakeBrowser(port: Int, headless: Boolean): Process {
        val args = buildList {
            add("exec -a chrome bash -c 'while :; do sleep 0.2; done'")
            add("--remote-debugging-port=$port")
            if (headless) add("--headless=new")
        }
        return ProcessBuilder("bash", "-c", args.joinToString(" "))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}
