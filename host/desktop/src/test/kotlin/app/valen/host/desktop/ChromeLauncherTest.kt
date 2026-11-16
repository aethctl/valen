/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * The launcher reads the browser's mode from its process, never from `/json/version`: Valen starts
 * a headless engine with an ordinary user agent (the Web Player refuses a headless one), so the
 * endpoint always says "Chrome". Reading it there made a sign-in believe a window was already up,
 * and Connect did nothing at all.
 *
 * A stand-in process is used instead of Chrome: what is under test is how the browser on the port
 * is identified and what a sign-in may do to it, not the browser.
 */
class ChromeLauncherTest {

    @Test
    fun `a headless browser on the port is read as headless`() {
        FakeBrowser(port = 39331, headless = true).use { fake ->
            assertEquals(true, awaitMode(ChromeLauncher(port = fake.port)), "headless")
        }
    }

    @Test
    fun `a browser with a window on the port is read as visible`() {
        FakeBrowser(port = 39332, headless = false).use { fake ->
            assertEquals(false, awaitMode(ChromeLauncher(port = fake.port)), "visible")
        }
    }

    @Test
    fun `a port with no browser behind it says nothing`() = runBlocking {
        assertNull(ChromeLauncher(port = 39333).isHeadless(), "no process")
    }

    @Test
    fun `a sign-in can stop a headless browser it did not start`() {
        FakeBrowser(port = 39334, headless = true).use { fake ->
            // Never start()ed: this is the browser an earlier Valen run left behind.
            val launcher = ChromeLauncher(port = fake.port)
            assertEquals(true, awaitMode(launcher), "headless before")
            assertTrue(runBlocking { launcher.stopBrowser() }, "stopped")
            assertFalse(fake.process.isAlive, "process gone")
            assertNull(runBlocking { launcher.isHeadless() }, "nothing left on the port")
        }
    }

    /** Polls [ChromeLauncher.isHeadless] until it answers: a new process takes a moment to appear. */
    private fun awaitMode(launcher: ChromeLauncher): Boolean? {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
        var seen: Boolean? = null
        while (System.currentTimeMillis() < deadline) {
            seen = runBlocking { launcher.isHeadless() }
            if (seen != null) return seen
            Thread.sleep(50)
        }
        fail("the launcher never found the browser on port ${launcher.port}")
    }

    /**
     * A process whose command line looks like this launcher's browser: a Chromium-family executable
     * name and the DevTools port. A copy of the shell is used under the name `chrome`, because Java
     * reads argv[0] from the executable, not from what the process calls itself.
     */
    private class FakeBrowser(port: Int, headless: Boolean) : AutoCloseable {
        val port = port
        private val dir: Path = Files.createTempDirectory("chrome-test")
        val process: Process

        init {
            val exe = dir.resolve("chrome")
            Files.copy(shell(), exe)
            process = ProcessBuilder(
                buildList {
                    add(exe.toString())
                    add("-c")
                    add("while :; do sleep 0.2; done")
                    add("--remote-debugging-port=$port")
                    if (headless) add("--headless=new")
                },
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }

        override fun close() {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            runCatching { Files.deleteIfExists(dir.resolve("chrome")) }
            runCatching { Files.deleteIfExists(dir) }
        }

        private companion object {
            fun shell(): Path = listOf("/bin/bash", "/usr/bin/bash", "/bin/sh")
                .map { Paths.get(it) }
                .first { Files.isExecutable(it) }
        }
    }
}
