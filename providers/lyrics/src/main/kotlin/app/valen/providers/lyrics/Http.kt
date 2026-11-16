/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.lyrics

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An answer read whole: its code, the headers asked for, and the body (null when too large). */
internal class Answer(val code: Int, private val headers: okhttp3.Headers, val body: String?) {
    fun header(name: String): String? = headers[name]
}

/** [request] sent, cancelled with the coroutine that waits for it, its body read up to [limit] bytes. */
internal suspend fun OkHttpClient.answer(request: Request, limit: Long = MAX_BODY): Answer =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val read = runCatching {
                    response.use { r ->
                        val source = r.body.source()
                        val body = if (source.request(limit + 1)) null else source.buffer.readUtf8()
                        Answer(r.code, r.headers, body)
                    }
                }
                read.onSuccess { if (cont.isActive) cont.resume(it) }
                    .onFailure { if (cont.isActive) cont.resumeWithException(it) }
            }
        })
    }

/** Lyrics documents are small; anything this large is not one. */
internal const val MAX_BODY = 8L * 1024 * 1024

/** Who is asking, as the lyrics services ask apps to say. No person's details are in it. */
internal const val USER_AGENT = "Valen/1.0 (music player)"
