/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.myspoti.ytmusic.InnerTube
import app.valen.core.PlaylistLink
import app.valen.core.ProviderId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** Playlist pages are served in memory; no request reaches YouTube or a real account. */
@Timeout(10)
class YtmusicPlaylistCatalogTest {
    private val json = Json
    private val link = PlaylistLink(ProviderId.YOUTUBE_MUSIC, "PLfixture", "https://music.youtube.com/playlist?list=PLfixture")

    private fun id(n: Int) = "video${n.toString().padStart(6, '0')}"

    private fun song(n: Int): JsonElement = buildJsonObject {
        putJsonObject("musicResponsiveListItemRenderer") {
            putJsonObject("playlistItemData") { put("videoId", id(n)) }
            putJsonArray("flexColumns") {
                add(buildJsonObject {
                    putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
                        putJsonObject("text") { putJsonArray("runs") { add(buildJsonObject { put("text", "Song $n") }) } }
                    }
                })
            }
            if (n == 137) put("musicItemRendererDisplayPolicy", "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT")
        }
    }

    private fun token(value: String): JsonElement = buildJsonObject {
        putJsonObject("continuationItemRenderer") {
            putJsonObject("continuationEndpoint") { putJsonObject("continuationCommand") { put("token", value) } }
        }
    }

    private fun shelf(range: IntRange, next: String? = null): JsonObject = buildJsonObject {
        put("targetId", "playlist-shelf")
        put("contents", JsonArray(range.map(::song)))
        next?.let { putJsonArray("continuations") { add(buildJsonObject { putJsonObject("nextContinuationData") { put("continuation", it) } }) } }
    }

    private fun initial(next: String? = "page-2", hasTarget: Boolean = true): JsonElement = buildJsonObject {
        putJsonObject("header") { putJsonObject("musicDetailHeaderRenderer") { putJsonObject("title") { put("simpleText", "Fixture playlist") } } }
        val main = shelf(1..100, next).let { if (hasTarget) it else JsonObject(it - "targetId") }
        putJsonObject("contents") { put("musicPlaylistShelfRenderer", main) }
        // Recommendations have their own rows and pagination, and must never be imported.
        putJsonObject("recommendations") { put("musicShelfRenderer", shelf(901..905, "recommendations")) }
    }

    private fun appended(range: IntRange, next: String? = null, recommendations: Boolean = false): JsonElement = buildJsonObject {
        putJsonArray("onResponseReceivedActions") {
            add(buildJsonObject {
                putJsonObject("appendContinuationItemsAction") {
                    put("targetId", "playlist-shelf")
                    put("continuationItems", JsonArray(range.map(::song) + listOfNotNull(next?.let(::token))))
                }
            })
            if (recommendations) add(buildJsonObject {
                putJsonObject("appendContinuationItemsAction") {
                    put("targetId", "recommendations")
                    put("continuationItems", JsonArray(listOf(song(999), token("recommendations"))))
                }
            })
        }
    }

    private fun client(pages: Map<String, JsonElement>, asked: MutableList<String>, signedIn: Boolean = false): InnerTube {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val key = (body["continuation"] ?: body["browseId"])!!.jsonPrimitive.content
            asked += key
            if (signedIn) {
                assertEquals("SAPISID=fixture-session", request.header("Cookie"))
                assertNotNull(request.header("Authorization"))
            } else {
                assertNull(request.header("Cookie"))
            }
            val page = pages[key] ?: error("Unexpected page: $key")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(page.toString().toResponseBody()).build()
        }.build()
        return InnerTube(http, cookies = { if (signedIn) "SAPISID=fixture-session" else null })
    }

    @Test
    fun `account import reads 250 songs across append pages in order`() = runBlocking {
        val asked = mutableListOf<String>()
        val account = client(mapOf(
            "VLPLfixture" to initial(),
            "page-2" to appended(101..200, "page-3", recommendations = true),
            "page-3" to appended(201..250),
        ), asked, signedIn = true)
        val catalog = YtmusicPlaylistCatalog(client(emptyMap(), mutableListOf()), account)
        val result = catalog.read(link)
        assertEquals(250, result.tracks.size)
        assertEquals((1..250).map(::id), result.tracks.map { it.id })
        assertEquals(listOf("VLPLfixture", "page-2", "page-3"), asked)
        assertEquals(setOf("${ProviderId.YOUTUBE_MUSIC.name}:${id(137)}"), result.blocked)
        assertEquals("Fixture playlist", result.name)
        assertNull(result.note)
    }

    @Test
    fun `legacy and section continuation pages still read all songs`() = runBlocking {
        val asked = mutableListOf<String>()
        val account = client(mapOf(
            "VLPLfixture" to initial(),
            "page-2" to buildJsonObject { putJsonObject("continuationContents") { put("musicPlaylistShelfContinuation", shelf(101..200, "page-3")) } },
            "page-3" to buildJsonObject {
                putJsonObject("continuationContents") {
                    putJsonObject("sectionListContinuation") {
                        putJsonArray("contents") { add(buildJsonObject { put("musicShelfRenderer", shelf(201..250)) }) }
                        putJsonArray("continuations") { add(buildJsonObject { putJsonObject("nextContinuationData") { put("continuation", "page-4") } }) }
                    }
                }
            },
            "page-4" to buildJsonObject { putJsonObject("continuationContents") { put("musicShelfContinuation", shelf(251..275)) } },
        ), asked)
        val result = YtmusicPlaylistCatalog(account).read(link)
        assertEquals((1..275).map(::id), result.tracks.map { it.id })
        assertEquals(4, asked.size)
    }

    @Test
    fun `a finished playlist never follows the recommendations token`() = runBlocking {
        val asked = mutableListOf<String>()
        val api = client(mapOf("VLPLfixture" to initial(next = null)), asked)
        val result = YtmusicPlaylistCatalog(api).read(link)
        assertEquals((1..100).map(::id), result.tracks.map { it.id })
        assertEquals(listOf("VLPLfixture"), asked)
        assertNull(result.note)
    }

    @Test
    fun `a repeated token fails instead of importing duplicate pages`() {
        val asked = mutableListOf<String>()
        val api = client(mapOf("VLPLfixture" to initial(), "page-2" to appended(101..200, "page-2")), asked)
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { YtmusicPlaylistCatalog(api).read(link) } }
        assertTrue(failure.message.orEmpty().contains("repeated"))
        assertEquals(listOf("VLPLfixture", "page-2"), asked)
    }

    @Test
    fun `an unknown continuation page cannot silently save only the first 100 songs`() {
        val asked = mutableListOf<String>()
        val api = client(mapOf("VLPLfixture" to initial(), "page-2" to buildJsonObject {}), asked)
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { YtmusicPlaylistCatalog(api).read(link) } }
        assertTrue(failure.message.orEmpty().contains("unreadable"))
        assertEquals(listOf("VLPLfixture", "page-2"), asked)
    }

    @Test
    fun `importing an account playlist into the library processes every page`() = runBlocking {
        val asked = mutableListOf<String>()
        val account = client(mapOf(
            "VLPLfixture" to initial(),
            "page-2" to appended(101..200, "page-3"),
            "page-3" to appended(201..250),
        ), asked, signedIn = true)
        val catalog = YtmusicPlaylistCatalog(client(emptyMap(), mutableListOf()), account)
        val library = app.valen.core.PlaylistLibrary(app.valen.core.PlaylistStore(read = { null }, write = {}))
        val manager = app.valen.core.PlaylistManager(library, listOf(catalog),
            search = { _, _, _ -> emptyList() }, playable = { true })
        val ref = app.valen.core.PlaylistRef("Fixture playlist", trackCount = 250, link = link)
        val result = manager.importPlaylist(ref)
        assertTrue(result is app.valen.core.ImportResult.Imported)
        val playlist = (result as app.valen.core.ImportResult.Imported).playlist
        // One deliberately unavailable row stays visible as unmatched rather than disappearing.
        assertEquals(249, playlist.tracks.size)
        assertEquals(1, playlist.unmatched.size)
        assertEquals(playlist, library.get(playlist.id))
        assertEquals(listOf("VLPLfixture", "page-2", "page-3"), asked)
    }

    @Test
    fun `a legacy first page adopts the append target for later pages`() = runBlocking {
        val asked = mutableListOf<String>()
        val api = client(mapOf(
            "VLPLfixture" to initial(hasTarget = false),
            "page-2" to appended(101..200, "page-3"),
            "page-3" to appended(201..250, recommendations = true),
        ), asked)
        assertEquals((1..250).map(::id), YtmusicPlaylistCatalog(api).read(link).tracks.map { it.id })
        assertEquals(3, asked.size)
    }

    @Test
    fun `ambiguous append targets cannot mix recommendations into the playlist`() {
        val api = client(mapOf(
            "VLPLfixture" to initial(hasTarget = false),
            "page-2" to appended(101..200, recommendations = true),
        ), mutableListOf())
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { YtmusicPlaylistCatalog(api).read(link) } }
        assertTrue(failure.message.orEmpty().contains("ambiguous"))
    }
}
