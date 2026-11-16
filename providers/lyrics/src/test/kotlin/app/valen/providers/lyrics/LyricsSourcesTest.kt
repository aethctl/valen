/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.lyrics

import app.valen.core.Lyrics
import app.valen.core.LyricLine
import app.valen.core.LyricsQuery
import app.valen.core.LyricsTiming
import app.valen.core.LyricsUnavailable
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** A local server stands in for Lyprio and LRCLIB; the words are made up. */
class LyricsSourcesTest {
    private val key = ByteArray(32) { it.toByte() }
    private val keyText = Base64.getEncoder().encodeToString(key)
    private val query = LyricsQuery("Paper Moons", "The Walls", durationMs = 181_000)

    private class Hit(val path: String, val headers: Map<String, String>, val body: String)
    private class Reply(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

    private val hits = CopyOnWriteArrayList<Hit>()
    private var server: HttpServer? = null

    private fun serve(answer: (Hit) -> Reply): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            val hit = Hit(
                ex.requestURI.toString(),
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                ex.requestBody.readAllBytes().decodeToString(),
            )
            hits += hit
            val reply = answer(hit)
            reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = reply.body.toByteArray()
            ex.sendResponseHeaders(reply.code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun lyprio(base: String) = LyprioLyrics(
        OkHttpClient(),
        LyprioEndpoint.of(base, keyText, loopbackHttp = true)!!,
        nowSeconds = { 1_700_000_000L },
        sleep = {},
        jitter = { 1L },
    )

    private val wordsJson = """{"version":2,"song":{"title":"Paper Moons","artist":"The Walls"},"lines":[{"startMs":1000,"endMs":2000,"words":[{"text":"chalk ","startMs":1000,"endMs":1400},{"text":"lines","startMs":1400,"endMs":1900}]}]}"""

    private fun result(lyrics: String, format: String = "mlx", sub: String = "MUSIXMATCH", sync: String = "word") =
        """{"subprovider":"$sub","format":"$format","syncType":"$sync","lyrics":${quote(lyrics)}}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun `requests are signed with the key over the stamp and the body`() {
        val stamp = "1700000000"
        val body = """{"title":"a","artist":"b"}"""
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
        val expected = mac.doFinal("$stamp\n$body".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, LyprioLyrics.signature(key, stamp, body))
        assertEquals(64, expected.length)
    }

    @Test
    fun `a pending search is asked again until its words are ready`() = runBlocking {
        var calls = 0
        val base = serve { hit ->
            calls++
            if (calls == 1) {
                Reply(202, """{"status":"pending","complete":false,"retryAfterMs":10}""", mapOf("X-Lyprio-Protocol" to "2"))
            } else {
                Reply(200, """{"status":"ready","complete":true,"results":[${result(wordsJson)},${result("[00:01.00]chalk lines", "lrc", "LRCLIB", "line")}]}""", mapOf("X-Lyprio-Protocol" to "2"))
            }
        }
        val lyrics = lyprio(base).find(query)!!
        assertEquals(LyricsTiming.Words, lyrics.timing)
        assertEquals("Lyprio · Musixmatch", lyrics.source)
        assertEquals(listOf("chalk", "lines"), lyrics.lines[0].words.map { it.text })
        val first = hits.first()
        assertEquals("/v2/lyrics/search", first.path)
        assertEquals("1700000000", first.headers["x-lyprio-timestamp"])
        assertEquals(LyprioLyrics.signature(key, "1700000000", first.body), first.headers["x-lyprio-signature"])
        assertTrue(first.body.contains("\"duration\":181"))
        assertTrue(!first.body.contains("allSources"))
    }

    @Test
    fun `a miss is asked once more with every source, then is a miss`() = runBlocking {
        val base = serve { Reply(404, """{"status":"not_found","complete":true,"results":[]}""", mapOf("X-Lyprio-Protocol" to "2")) }
        assertNull(lyprio(base).find(query))
        assertEquals(2, hits.size)
        assertTrue(hits[1].body.contains("\"allSources\":true"))
    }

    @Test
    fun `a server without protocol 2 is asked through protocol 1`() = runBlocking {
        val base = serve { hit ->
            if (hit.path.startsWith("/v2")) Reply(404, "") else Reply(200, """{"results":[${result(wordsJson)}]}""")
        }
        val lyrics = lyprio(base).find(query)
        assertNotNull(lyrics)
        assertEquals("client", hits.last().headers["x-lyprio-romanization"])
        assertEquals("/v1/lyrics/search", hits.last().path)
    }

    @Test
    fun `every source failing is not a miss`() {
        val base = serve { Reply(503, """{"status":"unavailable","complete":true,"results":[]}""", mapOf("X-Lyprio-Protocol" to "2")) }
        assertThrows<LyricsUnavailable> { runBlocking { lyprio(base).find(query) } }
    }

    @Test
    fun `a result naming another artist is not this song's`() = runBlocking {
        val other = wordsJson.replace("The Walls", "Someone Else")
        val base = serve { Reply(200, """{"status":"ready","complete":true,"results":[${result(other)}]}""", mapOf("X-Lyprio-Protocol" to "2")) }
        assertNull(lyprio(base).find(query))
    }

    @Test
    fun `only an https endpoint with a 32 byte key is taken`() {
        assertNull(LyprioEndpoint.of("http://lyrics.example", keyText))
        assertNull(LyprioEndpoint.of("https://lyrics.example", "c2hvcnQ="))
        assertNull(LyprioEndpoint.of("https://user:pw@lyrics.example", keyText))
        assertNotNull(LyprioEndpoint.of("https://lyrics.example/", keyText))
        assertNotNull(LyricsSetup.lyprio(env = mapOf("LYPRIO_BASE_URL" to "https://lyrics.example", "LYPRIO_HMAC_KEY" to keyText)))
        assertNull(LyricsSetup.lyprio(env = emptyMap()))
        assertTrue(LyricsSetup.lyprioMisconfigured(env = mapOf("VALEN_LYPRIO_URL" to "nope", "VALEN_LYPRIO_KEY" to keyText)))
    }

    @Test
    fun `the setup file is read when the environment says nothing`(@TempDir dir: Path) {
        val file = dir.resolve("lyprio.json")
        Files.writeString(file, """{"url":"https://lyrics.example","key":"$keyText"}""")
        assertEquals("lyrics.example", LyricsSetup.lyprio(env = emptyMap(), configFile = file)?.baseUrl?.host)
    }

    @Test
    fun `LRCLIB is asked for the exact song, then by search`() = runBlocking {
        val base = serve { hit ->
            when {
                hit.path.startsWith("/api/get") -> Reply(404, """{"code":404}""")
                else -> Reply(200, """[{"id":1,"artistName":"Another Band","duration":181,"syncedLyrics":"[00:01.00]wrong"},
                    {"id":2,"artistName":"The Walls","duration":182.4,"plainLyrics":"chalk","syncedLyrics":"[00:01.00]chalk\n[00:03.00]lines"}]""")
            }
        }
        val lyrics = LrclibLyrics(OkHttpClient(), "$base/api/".toHttpUrl()).find(query)!!
        assertEquals("LRCLIB", lyrics.source)
        assertEquals(LyricsTiming.Lines, lyrics.timing)
        assertEquals(listOf("chalk", "lines"), lyrics.lines.map { it.text })
        assertTrue(hits[0].path.contains("track_name=Paper%20Moons") && hits[0].path.contains("duration=181"))
        assertEquals(USER_AGENT, hits[0].headers["user-agent"])
    }

    @Test
    fun `an instrumental is known to have no words`() = runBlocking {
        val base = serve { Reply(200, """{"id":3,"artistName":"The Walls","duration":181,"instrumental":true}""") }
        val lyrics = LrclibLyrics(OkHttpClient(), "$base/api/".toHttpUrl()).find(query)
        assertTrue(lyrics!!.instrumental)
    }

    @Test
    fun `lyrics and misses are kept in the folder`(@TempDir dir: Path) {
        val store = DiskLyricsStore(dir)
        val words = Lyrics(listOf(LyricLine(1_000, 2_000, "chalk")), LyricsTiming.Lines, "LRCLIB")
        store.write("paper moons|the walls", words)
        store.write("silent|nobody", null)
        assertEquals(words, store.read("paper moons|the walls")?.lyrics)
        assertNotNull(store.read("silent|nobody"))
        assertNull(store.read("silent|nobody")!!.lyrics)
        assertNull(DiskLyricsStore(dir).read("never|seen"))
    }
}
