/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.cdp

import app.myspoti.web.PageHost
import app.myspoti.web.SessionCookies
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * An account provider's hidden page ([PageHost]) in a tab of a desktop Chrome started with
 * `--remote-debugging-port`, for a PC: the tab of [origin] (or a new one), the page script's
 * messages through a CDP binding, the page loads, and the session's cookies as that Chrome keeps
 * them ([SessionCookies]). Unlike the Spotify host, it blocks and rewrites nothing. The user signs
 * in in that tab themself. Copied from Orpheon's `base/host-cdp`.
 */
class CdpPageHost private constructor(
    private val socket: WebSocket,
    private val listener: Listener,
    private val sessionUrls: List<String>,
) : PageHost, SessionCookies {
    override val messages: SharedFlow<String> = listener.messages

    /** False as soon as this tab disconnects, even if the browser still answers HTTP. */
    val isOpen: Boolean get() = listener.isOpen
    override val loads: SharedFlow<String> = listener.loads

    override suspend fun evaluate(script: String) {
        send("Runtime.evaluate", buildJsonObject { put("expression", script); put("awaitPromise", false) })
    }

    /**
     * Runs [script] and returns the JSON of its value (`"true"`, `"\"x\""`, `"null"`, …), like
     * the Android host's `evaluateJavascript` callback. Throws when the page cannot be reached.
     */
    suspend fun evaluateValue(script: String): String? {
        val result = send("Runtime.evaluate", buildJsonObject {
            put("expression", script)
            put("awaitPromise", true)
            put("returnByValue", true)
        })
        val exception = result["exceptionDetails"]?.jsonObject
        if (exception != null) {
            val text = exception["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.content
                ?: exception["text"]?.jsonPrimitive?.content
                ?: "the page threw"
            throw IllegalStateException(text)
        }
        return result["result"]?.jsonObject?.get("value")?.toString()
    }

    override suspend fun navigate(url: String) {
        send("Page.navigate", buildJsonObject { put("url", url) })
    }

    /** The tab must be frontmost for Chrome to load the media an autoplay asks for. */
    override suspend fun activate() {
        runCatching { send("Page.bringToFront", buildJsonObject {}) }
    }

    /** The page's url now, or null when the host cannot tell. */
    suspend fun url(): String? =
        runCatching { evaluateValue("location.href")?.let { Json.parseToJsonElement(it).jsonPrimitive.content } }.getOrNull()

    private suspend fun cookies(): List<JsonObject> =
        send("Network.getCookies", buildJsonObject { put("urls", buildJsonArray { sessionUrls.forEach { add(JsonPrimitive(it)) } }) })["cookies"]
            ?.jsonArray?.map { it.jsonObject }.orEmpty()

    /** The `Cookie` header the browser would send to [url], or null when it holds none. */
    override suspend fun header(url: String): String? {
        val list = send("Network.getCookies", buildJsonObject { put("urls", buildJsonArray { add(JsonPrimitive(url)) }) })["cookies"]
            ?.jsonArray?.map { it.jsonObject }.orEmpty()
        return list.joinToString("; ") { "${it["name"]!!.jsonPrimitive.content}=${it["value"]!!.jsonPrimitive.content}" }.ifEmpty { null }
    }

    /** The names of the cookies the browser holds for [url] (never their values). */
    suspend fun cookieNames(url: String): Set<String> =
        send("Network.getCookies", buildJsonObject { put("urls", buildJsonArray { add(JsonPrimitive(url)) }) })["cookies"]
            ?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.orEmpty().toSet()

    /** Signs out: every cookie of the provider's sites is deleted from the browser. */
    override suspend fun clear() {
        for (c in cookies()) send("Network.deleteCookies", buildJsonObject {
            put("name", c["name"]!!.jsonPrimitive.content)
            put("domain", c["domain"]!!.jsonPrimitive.content)
            put("path", c["path"]?.jsonPrimitive?.content ?: "/")
        })
    }

    fun close() {
        listener.shutdown("the page connection was closed")
        socket.close(1000, null)
    }

    private suspend fun send(method: String, params: JsonObject): JsonObject = listener.send(socket, method, params)

    internal class Listener(private val binding: String) : WebSocketListener() {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        private val pump = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Every page message, none dropped (see `CdpHost.Listener.messages`). */
        val messages: SharedFlow<String> =
            incoming.receiveAsFlow().shareIn(pump, SharingStarted.Eagerly, replay = 0)

        val loads = MutableSharedFlow<String>(extraBufferCapacity = 8)
        private val ids = AtomicInteger(0)

        /**
         * The closed state and the commands waiting for an answer, under one guard (see
         * `CdpHost.Listener`): a command that begins as the host closes is refused instead of
         * waiting out the send timeout, and every waiter registered before shutdown is failed.
         */
        private val guard = Any()
        private val pending = HashMap<Int, CompletableDeferred<JsonObject>>()
        private var closed: IllegalStateException? = null

        val isOpen: Boolean get() = synchronized(guard) { closed == null }

        val opened = CompletableDeferred<Unit>()
        @Volatile private var url = ""

        suspend fun send(ws: WebSocket, method: String, params: JsonObject): JsonObject {
            val id = ids.incrementAndGet()
            val waiter = CompletableDeferred<JsonObject>()
            synchronized(guard) {
                closed?.let { throw IllegalStateException(it.message ?: "the page connection was closed") }
                pending[id] = waiter
            }
            val sent = ws.send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())
            if (!sent) {
                synchronized(guard) { pending.remove(id) }
                throw IllegalStateException("the browser is not accepting commands ($method)")
            }
            return try {
                // Only this call's own timeout is a browser failure; a caller's cancellation stays
                // a cancellation (see `CdpHost.Listener.send`).
                withTimeoutOrNull(SEND_TIMEOUT_MS) { waiter.await() }
                    ?: throw IllegalStateException("the browser did not answer $method within ${SEND_TIMEOUT_MS / 1000} s")
            } finally {
                synchronized(guard) { pending.remove(id) }
            }
        }

        /** Ends [messages] and fails anything still waiting: a closed host answers nothing. */
        fun shutdown(reason: String) {
            val failure = IllegalStateException(reason)
            val waiting = synchronized(guard) {
                if (closed == null) closed = failure
                pending.values.toList().also { pending.clear() }
            }
            opened.completeExceptionally(failure)
            waiting.forEach { it.completeExceptionally(failure) }
            incoming.close()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handle(text) }
        }

        private fun handle(text: String) {
            val msg = json.parseToJsonElement(text).jsonObject
            msg["id"]?.let { id ->
                val waiter = synchronized(guard) { pending.remove(id.jsonPrimitive.int) } ?: return
                msg["error"]?.let { waiter.completeExceptionally(IllegalStateException(it.toString())) }
                    ?: waiter.complete(msg["result"]?.jsonObject ?: JsonObject(emptyMap()))
                return
            }
            val params = msg["params"]?.jsonObject ?: return
            when (msg["method"]?.jsonPrimitive?.content) {
                "Runtime.bindingCalled" -> if (params["name"]?.jsonPrimitive?.content == binding) incoming.trySend(params["payload"]!!.jsonPrimitive.content)
                "Page.frameNavigated" -> params["frame"]?.jsonObject?.takeIf { it["parentId"] == null }?.let { url = it["url"]!!.jsonPrimitive.content }
                "Page.loadEventFired" -> loads.tryEmit(url)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            shutdown("the browser closed the page connection ($code${if (reason.isNotBlank()) ": $reason" else ""})")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            shutdown("the browser closed the page connection ($code${if (reason.isNotBlank()) ": $reason" else ""})")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            shutdown(t.message ?: "the page connection failed")
        }

        private companion object {
            const val SEND_TIMEOUT_MS = 30_000L
        }
    }

    companion object {
        const val BINDING = "__mspHostPost"
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultHttp = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

        /**
         * Attaches to the tab of [origin] in the Chrome listening on [cdpUrl] (opens [startUrl] in
         * a new one when there is none). [sessionUrls] are the sites whose cookies hold the session
         * (all deleted at sign-out).
         */
        suspend fun connect(
            origin: String,
            startUrl: String,
            sessionUrls: List<String>,
            cdpUrl: String = "http://127.0.0.1:9333",
            http: OkHttpClient = defaultHttp,
            binding: String = BINDING,
        ): CdpPageHost {
            val targets = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url("$cdpUrl/json/list").build()).execute().use { it.body.string() } }
            val page = json.parseToJsonElement(targets).jsonArray.map { it.jsonObject }.firstOrNull {
                it["type"]?.jsonPrimitive?.content == "page" && it["url"]!!.jsonPrimitive.content.startsWith(origin)
            } ?: withContext(Dispatchers.IO) {
                json.parseToJsonElement(http.newCall(Request.Builder().url("$cdpUrl/json/new?$startUrl").put(okhttp3.RequestBody.EMPTY).build()).execute().use { it.body.string() }).jsonObject
            }
            val listener = Listener(binding)
            val socket = http.newWebSocket(Request.Builder().url(page["webSocketDebuggerUrl"]!!.jsonPrimitive.content).build(), listener)
            val host = CdpPageHost(socket, listener, sessionUrls)
            try {
                withTimeout(10_000) { listener.opened.await() }
                host.send("Runtime.enable", buildJsonObject {})
                host.send("Page.enable", buildJsonObject {})
                host.send("Network.enable", buildJsonObject {})
                host.send("Runtime.addBinding", buildJsonObject { put("name", binding) })
                return host
            } catch (e: Throwable) {
                host.close()
                throw e
            }
        }
    }
}
