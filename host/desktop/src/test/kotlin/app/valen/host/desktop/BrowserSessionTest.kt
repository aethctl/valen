/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class BrowserSessionTest {
    @Test
    fun `a closed browser is recovered silently and a login remains explicit`() = runBlocking {
        val modes = mutableListOf<Boolean>()
        var running = false
        var launches = 0
        val session = BrowserSession(
            start = { visible ->
                modes += visible
                if (!running) { launches++; running = true }
                true
            },
            stop = { running = false },
            reportFailure = { fail("unexpected failure") },
        )
        assertTrue(session.ensure(visible = true))
        running = false // Closing the login window, without deleting its profile.
        assertTrue(session.ensure())
        assertTrue(session.ensure())
        assertEquals(listOf(true, false, false), modes)
        assertEquals(2, launches)
        session.close()
        assertFalse(running)
        assertFalse(session.ensure(visible = true))
        assertEquals(2, launches)
    }

    @Test
    fun `concurrent polls share a single launch`() = runBlocking {
        var running = false
        var launches = 0
        val session = BrowserSession(
            start = {
                if (!running) { delay(20); launches++; running = true }
                true
            },
            stop = { running = false },
            reportFailure = {},
        )
        try {
            val results = List(12) { async { session.ensure() } }.awaitAll()
            assertTrue(results.all { it })
            assertEquals(1, launches)
        } finally { session.close() }
    }

    @Test
    fun `three failed recoveries stop polling until an explicit retry`() = runBlocking {
        var attempts = 0
        var reports = 0
        var succeeds = false
        val session = BrowserSession(
            start = { attempts++; succeeds }, stop = {}, reportFailure = { reports++ },
        )
        repeat(10) { assertFalse(session.ensure()) }
        assertEquals(3, attempts)
        assertEquals(1, reports)
        succeeds = true
        assertFalse(session.ensure())
        assertTrue(session.ensure(visible = true))
        assertEquals(4, attempts)
        assertEquals(1, reports)
        session.close()
    }

    @Test
    fun `shutdown also stops a browser that finishes launching late`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var running = false
        val session = BrowserSession(
            start = { release.await(); running = true; true },
            stop = { running = false }, reportFailure = {},
        )
        val pending = async(start = CoroutineStart.UNDISPATCHED) { session.ensure() }
        assertTrue(session.starting)
        session.close()
        release.complete(Unit)
        assertFalse(pending.await())
        assertFalse(running)
        assertFalse(session.starting)
        assertFalse(session.ensure())
    }

    @Test
    fun `cancellation is not counted as a failed recovery`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        var reports = 0
        val session = BrowserSession(
            start = { attempts++; release.await(); true }, stop = {}, reportFailure = { reports++ },
        )
        repeat(4) {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { session.ensure() }
            pending.cancel()
            pending.join()
        }
        release.complete(Unit)
        assertTrue(session.ensure())
        assertEquals(5, attempts)
        assertEquals(0, reports)
        session.close()
    }
}
