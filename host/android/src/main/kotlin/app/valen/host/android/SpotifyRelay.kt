/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android, `ControlRelay.kt`
 * (the relay's core: the wrapped fetch, the page's identity, the request repeated from the app).
 * See NOTICE.
 */
package app.valen.host.android

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Sends the Web Player's playback-control requests (Spotify Connect commands and transfers, its
 * device and track-playback state) from the app instead of the WebView.
 *
 * Every request a WebView makes carries `X-Requested-With: <package>`, and current WebViews no
 * longer let an app turn that off. Spotify answers a Connect command that carries it with 200 and
 * then never delivers it to the player: the play is "accepted" and nothing happens, the page keeps
 * showing the account's last song. Seen on the emulator and on a phone (2026-11-15): commands from
 * the device to itself answered 200, nothing came down the dealer socket. Orpheon found the same
 * and relays these requests; so does Valen now.
 *
 * The page's `fetch` is wrapped at document start. A matching request goes to the app over the
 * `ValenNet` channel, is repeated with OkHttp using the page's own headers (its token, client
 * token, connection id) and identity (user agent, client hints, languages), and the answer is
 * handed back to the page as the `Response` it awaited.
 */
internal class SpotifyRelay(private val view: WebView, private val fallbackUserAgent: () -> String) {
    private val main = Handler(Looper.getMainLooper())

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** What the page presents (user agent, client hints, languages), read by the page itself. */
    @Volatile private var identity: JSONObject? = null

    /** False when this WebView cannot run a script before the page's own (then nothing is relayed). */
    var installed = false
        private set

    fun install() {
        val listener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!listener || !documentStart) {
            Log.w(TAG, "relay not installed: this WebView lacks message listeners or document-start scripts")
            return
        }
        WebViewCompat.addWebMessageListener(view, CHANNEL, setOf(ORIGIN)) { _, message, _, isMainFrame, _ ->
            if (isMainFrame) message.data?.let(::onMessage)
        }
        WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf(ORIGIN))
        installed = true
        Log.i(TAG, "relay installed")
    }

    private fun onMessage(json: String) {
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        if (m.optString("type") == "identity") {
            identity = m.optJSONObject("value")
            return
        }
        val id = m.optInt("id", -1).takeIf { it >= 0 } ?: return
        val doc = m.optString("doc")
        val url = m.optString("url")
        val method = m.optString("method", "GET").uppercase()
        val body = if (m.isNull("body")) null else m.optString("body")
        if (!RELAYED.containsMatchIn(url) || NOT_RELAYED.containsMatchIn(url)) {
            reply(id, doc, 0, "{}", "")
            return
        }
        val headers = headersFor(m.optJSONObject("headers") ?: JSONObject())
        // Bytes, not a string: OkHttp would add "; charset=utf-8" to the page's content type.
        val payload = if (method == "GET" || method == "HEAD") null else (body ?: "").toByteArray().toRequestBody(null)
        val request = runCatching { Request.Builder().url(url).headers(headers).method(method, payload).build() }.getOrNull()
        if (request == null) {
            reply(id, doc, 0, "{}", "")
            return
        }
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "relay $method ${pathOf(url)} failed: ${e.message}")
                reply(id, doc, 0, "{}", "")
            }

            override fun onResponse(call: Call, response: Response) {
                val (code, heads, text) = runCatching {
                    response.use { r -> Triple(r.code, responseHeaders(r.headers), r.body.string()) }
                }.getOrElse { Triple(0, "{}", "") }
                if (COMMAND.containsMatchIn(url)) Log.d(TAG, "relay $method ${pathOf(url)} -> $code")
                reply(id, doc, code, heads, text)
            }
        })
    }

    /** The page's headers, without the WebView's mark, with its identity; OkHttp handles encoding and length. */
    private fun headersFor(given: JSONObject): Headers {
        val b = Headers.Builder()
        given.keys().forEach { name ->
            val lower = name.lowercase()
            if (lower in DROPPED) return@forEach
            runCatching { b.set(name, given.optString(name)) }
        }
        val page = identity
        val ua = page?.optString("userAgent")?.takeIf { it.isNotEmpty() } ?: fallbackUserAgent()
        b.set("User-Agent", ua)
        page?.optString("acceptLanguage")?.takeIf { it.isNotEmpty() }?.let { b.set("Accept-Language", it) }
        page?.optString("brands")?.takeIf { it.isNotEmpty() }?.let { b.set("sec-ch-ua", it) }
        page?.optString("mobile")?.takeIf { it.isNotEmpty() }?.let { b.set("sec-ch-ua-mobile", it) }
        page?.optString("platform")?.takeIf { it.isNotEmpty() }?.let { b.set("sec-ch-ua-platform", "\"$it\"") }
        // What the page's own cross-origin fetch would say about where it comes from.
        b.set("Origin", ORIGIN)
        b.set("Referer", "$ORIGIN/")
        return b.build()
    }

    /** The answer's headers as the page's `Response` can take them (no encoding or cookies: those are spent). */
    private fun responseHeaders(h: Headers): String {
        val o = JSONObject()
        for ((name, value) in h) {
            val lower = name.lowercase()
            if (lower in SPENT) continue
            o.put(lower, if (o.has(lower)) o.getString(lower) + ", " + value else value)
        }
        return o.toString()
    }

    private fun reply(id: Int, doc: String, status: Int, headers: String, body: String) {
        val script = "window.__valenNetDone && window.__valenNetDone($id, $status, ${JSONObject.quote(headers)}, " +
            "${JSONObject.quote(body)}, ${JSONObject.quote(doc)})"
        main.post { runCatching { view.evaluateJavascript(script, null) } }
    }

    private fun pathOf(url: String) = url.substringBefore('?').substringAfter("/v1/").replace(Regex("[0-9a-f]{16,}"), "…")

    companion object {
        private const val TAG = "ValenSpotify"
        private const val CHANNEL = "ValenNet"
        private const val ORIGIN = "https://open.spotify.com"

        private val RELAYED = Regex("^https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.spotify\\.com/(connect-state|track-playback)/v1/")
        private val NOT_RELAYED = Regex("^https://(accounts|challenge|www)\\.spotify\\.com/")
        private val COMMAND = Regex("/connect-state/v1/(player/command|connect/transfer)/")

        /** Never sent on: the WebView's mark, and what OkHttp sets itself from the request. */
        private val DROPPED = setOf("x-requested-with", "accept-encoding", "content-length", "host", "connection", "cookie", "user-agent")

        /** Never handed back: spent by OkHttp (it decoded the body) or forbidden in a page's Response. */
        private val SPENT = setOf("content-encoding", "content-length", "transfer-encoding", "set-cookie", "set-cookie2", "connection")

        /** Wraps the page's fetch for the relayed requests; installed before the page's own scripts. */
        private val SCRIPT = """
            (() => {
              if (window.__valenNetInstalled || !window.$CHANNEL) return;
              window.__valenNetInstalled = true;
              // The identity this page really presents, for the app to echo on what it relays.
              try {
                const d = navigator.userAgentData || {};
                const langs = (navigator.languages && navigator.languages.length) ? navigator.languages : [navigator.language];
                window.$CHANNEL.postMessage(JSON.stringify({ type: 'identity', value: {
                  userAgent: navigator.userAgent,
                  brands: (d.brands || []).map(b => '"' + b.brand + '";v="' + b.version + '"').join(', '),
                  mobile: d.mobile === true ? '?1' : '?0',
                  platform: d.platform || 'Linux',
                  acceptLanguage: langs.map((l, i) => i === 0 ? l : l + ';q=0.' + Math.max(1, 10 - i)).join(','),
                }}));
              } catch (e) { /* the app keeps its fallback identity */ }
              const relayed = /^https:\/\/([a-z0-9-]+\.)*spotify\.com\/(connect-state|track-playback)\/v1\//;
              const notRelayed = /^https:\/\/(accounts|challenge|www)\.spotify\.com\//;
              const pending = new Map();
              let seq = 0;
              // This document's mark: an answer meant for the page before a reload is not this one's.
              const doc = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
              const original = window.fetch;
              window.__valenNetDone = (id, status, headers, body, from) => {
                if (from !== doc) return;
                const p = pending.get(id);
                if (!p) return;
                pending.delete(id);
                if (!status) return p.reject(new TypeError('Failed to fetch'));
                const noBody = status === 204 || status === 205 || status === 304;
                try { p.resolve(new Response(noBody ? null : body, { status, headers: JSON.parse(headers) })); }
                catch (e) { p.resolve(new Response(noBody ? null : body, { status })); }
              };
              window.fetch = function (input, init) {
                const url = typeof input === 'string' ? input : (input instanceof URL ? input.href : null);
                const body = init && init.body;
                if (!url || !relayed.test(url) || notRelayed.test(url) || (body != null && typeof body !== 'string')) return original.apply(this, arguments);
                const headers = {};
                new Headers((init && init.headers) || {}).forEach((v, k) => { headers[k] = v; });
                const method = ((init && init.method) || 'GET').toUpperCase();
                return new Promise((resolve, reject) => {
                  const id = ++seq;
                  pending.set(id, { resolve, reject });
                  window.$CHANNEL.postMessage(JSON.stringify({ id, doc, url, method, headers, body: body == null ? null : body }));
                });
              };
            })();
        """.trimIndent()
    }
}
