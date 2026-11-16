/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.cdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A closed page connection must not leave commands waiting out the 30 s send timeout, and the
 * close has to be atomic with registering a new command: the old code cleared the map and only
 * then dropped the socket, so a `send` that registered in that window waited for an answer that
 * could never come (a Connect that looked hung).
 *
 * The timeout rule matters too: only this call's own `SEND_TIMEOUT_MS` may become a browser
 * failure. A screen that cancels its own command, or a caller's own `withTimeout`, must see its
 * cancellation — turning it into "the browser did not answer" hid the real cause and made a
 * cancelled call look like a broken browser.
 */
class CdpListenerTest {

    @Test
    fun `account probes include parent-domain cookies without matching other sites`() {
        assertTrue(cookieDomainMatches(".youtube.com", "music.youtube.com"))
        assertTrue(cookieDomainMatches(".spotify.com", "open.spotify.com"))
        assertTrue(cookieDomainMatches(".soundcloud.com", "soundcloud.com"))
        assertTrue(cookieDomainMatches("music.youtube.com", "music.youtube.com"))
        assertFalse(cookieDomainMatches("youtube.com", "music.youtube.com"))
        assertFalse(cookieDomainMatches(".youtube.com", "notyoutube.com"))
        assertFalse(cookieDomainMatches(".youtube.com", "youtube.com.example.org"))
        assertFalse(cookieDomainMatches("accounts.google.com", "music.youtube.com"))
    }

    @Test
    fun `closing a tab invalidates its cached connection`() {
        for (subject in subjects()) {
            assertTrue(subject.isOpen(), subject.name)
            subject.shutdown("the page connection failed")
            assertFalse(subject.isOpen(), subject.name)
        }
    }

    @Test
    fun `a shutdown fails a command that is already waiting`() = runBlocking {
        for (subject in subjects()) {
            val socket = FakeSocket()
            val result = async(Dispatchers.Default) {
                runCatching { subject.send(socket) }.exceptionOrNull()
            }
            socket.sends.await()

            subject.shutdown("the page connection was closed")

            val error = withTimeout(2_000) { result.await() }
            assertTrue(error is IllegalStateException, "${subject.name}: $error")
            assertEquals(
                "the page connection was closed",
                error?.message,
                "${subject.name}: the waiter was failed with the shutdown reason",
            )
        }
    }

    @Test
    fun `a command started after shutdown is refused instead of waiting`() = runBlocking {
        for (subject in subjects()) {
            subject.shutdown("the page connection was closed")

            val error = runCatching { subject.send(FakeSocket()) }.exceptionOrNull()

            assertTrue(error is IllegalStateException, "${subject.name}: $error")
            assertEquals(
                "the page connection was closed",
                error?.message,
                "${subject.name}: refused at once, not after the send timeout",
            )
        }
    }

    /** A refused send means the socket is gone: waiting for a reply cannot help. */
    @Test
    fun `a send the socket refuses fails at once`() = runBlocking {
        for (subject in subjects()) {
            val started = System.currentTimeMillis()
            val error = runCatching { subject.send(FakeSocket(accepts = false)) }.exceptionOrNull()

            assertTrue(error is IllegalStateException, "${subject.name}: $error")
            assertTrue(
                error?.message?.contains("not accepting commands") == true,
                "${subject.name}: ${error?.message}",
            )
            assertTrue(
                System.currentTimeMillis() - started < 2_000,
                "${subject.name}: it did not wait for an answer",
            )
        }
    }

    /**
     * The caller's timeout is the caller's: `withTimeoutOrNull` inside `send` only reports its own
     * expiry, so an outer cancellation is never rewritten as "the browser did not answer".
     */
    @Test
    fun `a caller's own timeout stays a cancellation`() = runBlocking {
        for (subject in subjects()) {
            val error = runCatching {
                withTimeout(120) { subject.send(FakeSocket()) }
            }.exceptionOrNull()

            assertTrue(error is TimeoutCancellationException, "${subject.name}: $error")
        }
    }

    /**
     * A message that arrives while a subscriber is busy must wait for it, not be dropped. The old
     * bridge pump used a 256-slot flow with `tryEmit`, so everything past 256 frames was lost — a
     * lost reply leaves the call that wanted it waiting out the full timeout, and that call can be
     * the play the user just asked for. Both hosts share the pump, so both are checked.
     */
    @Test
    fun `a slow subscriber still gets every message, in order`() = runBlocking {
        val payloads = (1..300).map { "payload-$it" }
        for (subject in subjects()) {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val slow = ConcurrentLinkedQueue<String>()
            val fast = ConcurrentLinkedQueue<String>()
            val release = CompletableDeferred<Unit>()
            try {
                // Undispatched: each launch registers its collector before returning, so both are
                // subscribed before the first frame is delivered. A subscriber that joined later
                // would miss the earlier frames (the flow keeps no replay) and prove nothing.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    subject.messages.collect { payload ->
                        slow += payload
                        // One blocked subscriber holds the pump, as a screen computing on a frame
                        // does: the remaining messages must be buffered, not dropped.
                        if (slow.size == 1) release.await()
                    }
                }
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    subject.messages.collect { fast += it }
                }

                payloads.forEach(subject.deliverBinding)
                withTimeout(2_000) {
                    while (slow.size < 1) delay(5)
                }
                release.complete(Unit)

                withTimeout(5_000) {
                    while (slow.size < payloads.size || fast.size < payloads.size) delay(5)
                }
                assertEquals(payloads, slow.toList(), "${subject.name}: the slow subscriber lost or reordered a message")
                assertEquals(payloads, fast.toList(), "${subject.name}: the fast subscriber lost or reordered a message")
            } finally {
                release.complete(Unit)
                subject.shutdown("test done")
                scope.cancel()
            }
        }
    }

    /** The two hosts share the rule, so they are checked together. */
    private fun subjects(): List<Subject> = listOf(
        CdpHost.Listener(CdpHost.BINDING).let { listener ->
            val socket = FakeSocket()
            Subject(
                name = "CdpHost",
                isOpen = { listener.isOpen },
                send = { ws -> listener.send(ws, METHOD, JsonObject(emptyMap())) },
                shutdown = listener::shutdown,
                messages = listener.messages,
                deliverBinding = { payload ->
                    listener.onMessage(socket, bindingFrame(CdpHost.BINDING, payload))
                },
            )
        },
        CdpPageHost.Listener(CdpPageHost.BINDING).let { listener ->
            val socket = FakeSocket()
            Subject(
                name = "CdpPageHost",
                isOpen = { listener.isOpen },
                send = { ws -> listener.send(ws, METHOD, JsonObject(emptyMap())) },
                shutdown = listener::shutdown,
                messages = listener.messages,
                deliverBinding = { payload ->
                    listener.onMessage(socket, bindingFrame(CdpPageHost.BINDING, payload))
                },
            )
        },
    )

    /** A `Runtime.bindingCalled` frame, as the browser sends it to the page host's binding. */
    private fun bindingFrame(binding: String, payload: String): String = buildJsonObject {
        put("method", "Runtime.bindingCalled")
        putJsonObject("params") {
            put("name", binding)
            put("payload", payload)
        }
    }.toString()

    private class Subject(
        val name: String,
        val isOpen: () -> Boolean,
        val send: suspend (WebSocket) -> JsonObject,
        val shutdown: (String) -> Unit,
        val messages: SharedFlow<String>,
        val deliverBinding: (String) -> Unit,
    )

    /**
     * A socket whose frames go nowhere: [sends] completes when the frame was handed over, which is
     * after the command registered its waiter, so a test can close the host at exactly that point.
     */
    private class FakeSocket(private val accepts: Boolean = true) : WebSocket {
        val sends = CompletableDeferred<String>()

        override fun request(): Request = Request.Builder().url("ws://127.0.0.1:1/devtools/page/test").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            sends.complete(text)
            return accepts
        }
        override fun send(bytes: ByteString): Boolean = accepts
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    private companion object {
        const val METHOD = "Runtime.evaluate"
    }
}
