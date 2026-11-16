/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.LyricLine
import app.valen.core.Lyrics
import app.valen.core.LyricsState
import app.valen.core.LyricsTiming
import app.valen.core.PresenceSettings
import app.valen.core.ProviderId
import app.valen.core.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread

/** Discord's card: what it says, and the socket protocol it is said over. */
class DiscordPresenceTest {

    private val song = Track(
        provider = ProviderId.SPOTIFY,
        id = "2Foc5Q5nqNiosCNqttzHof",
        title = "Get Lucky",
        artist = "Daft Punk, Pharrell Williams",
        album = "Random Access Memories",
        artworkUrl = "https://i.scdn.co/image/abc",
        durationMs = 248_000,
    )

    @Test
    fun `a playing song is a listening card with its bar, cover, mark and button`() {
        val now = 1_000_000L
        val a = DiscordPresence.activity(
            DiscordPresence.PresenceInput(song, playing = true, positionMs = 60_000, durationMs = 248_000, place = 3, of = 41),
            PresenceSettings(enabled = true),
            homepage = null,
            now = now,
        )
        assertEquals(2, a["type"]!!.jsonPrimitive.content.toInt())
        assertEquals("Get Lucky", a["details"]!!.jsonPrimitive.content)
        assertEquals("Daft Punk, Pharrell Williams", a["state"]!!.jsonPrimitive.content)
        val time = a["timestamps"]!!.jsonObject
        assertEquals(now - 60_000, time["start"]!!.jsonPrimitive.long)
        assertEquals(now - 60_000 + 248_000, time["end"]!!.jsonPrimitive.long)
        val assets = a["assets"]!!.jsonObject
        assertEquals("https://i.scdn.co/image/abc", assets["large_image"]!!.jsonPrimitive.content)
        assertEquals("Random Access Memories · from Spotify", assets["large_text"]!!.jsonPrimitive.content)
        assertEquals("valen", assets["small_image"]!!.jsonPrimitive.content)
        assertEquals("Valen · 3 of 41 in the queue", assets["small_text"]!!.jsonPrimitive.content)
        val button = a["buttons"]!!.jsonArray.single().jsonObject
        assertEquals("Play on Spotify", button["label"]!!.jsonPrimitive.content)
        assertEquals("https://open.spotify.com/track/2Foc5Q5nqNiosCNqttzHof", button["url"]!!.jsonPrimitive.content)

        val withPage = DiscordPresence.activity(
            DiscordPresence.PresenceInput(song, playing = true, positionMs = 0, durationMs = 248_000),
            PresenceSettings(enabled = true),
            homepage = DiscordPresence.HOMEPAGE,
            now = now,
        )
        val page = withPage["buttons"]!!.jsonArray.last().jsonObject
        assertEquals("Get Valen", page["label"]!!.jsonPrimitive.content)
        assertEquals("https://github.com/dodo986/valen", page["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `paused, a device file and the switches change what is shown`() {
        val local = Track(ProviderId.LOCAL, "/m/a.flac", "Kerala", "Bonobo", artworkUrl = "file:///c.jpg", durationMs = 240_000)
        val a = DiscordPresence.activity(
            DiscordPresence.PresenceInput(local, playing = false, positionMs = 10_000, durationMs = 240_000),
            PresenceSettings(enabled = true),
            homepage = null,
            now = 0,
        )
        assertNull(a["timestamps"], "no bar while paused")
        assertEquals("Paused · Bonobo", a["state"]!!.jsonPrimitive.content)
        assertEquals("valen", a["assets"]!!.jsonObject["large_image"]!!.jsonPrimitive.content, "a file cover cannot reach Discord")
        assertNull(a["assets"]!!.jsonObject["small_image"], "the mark is never drawn on itself")
        assertNull(a["buttons"])

        val quiet = DiscordPresence.activity(
            DiscordPresence.PresenceInput(song, playing = true, positionMs = 0, durationMs = 248_000, line = "We're up all night to get lucky", kept = true),
            PresenceSettings(enabled = true, cover = false, progress = false, service = false, buttons = false),
            homepage = "https://valen.example",
            now = 0,
        )
        assertNull(quiet["timestamps"])
        assertNull(quiet["buttons"])
        assertEquals("♪ We're up all night to get lucky", quiet["state"]!!.jsonPrimitive.content)
        assertEquals("valen", quiet["assets"]!!.jsonObject["large_image"]!!.jsonPrimitive.content)
        assertEquals("Random Access Memories", quiet["assets"]!!.jsonObject["large_text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the sung line is the last one started, and nothing in a long gap`() {
        val lyrics = LyricsState.Found(
            Lyrics(
                listOf(LyricLine(1_000, 3_000, "one"), LyricLine(3_000, 5_000, "two"), LyricLine(20_000, 22_000, "three")),
                LyricsTiming.Lines,
                "test",
            ),
        )
        assertNull(DiscordPresence.lineAt(lyrics, 500))
        assertEquals("one", DiscordPresence.lineAt(lyrics, 1_200))
        assertEquals("two", DiscordPresence.lineAt(lyrics, 4_000))
        assertNull(DiscordPresence.lineAt(lyrics, 10_000))
        assertNull(DiscordPresence.lineAt(LyricsState.Missing, 1_200))
    }

    @Test
    fun `the socket speaks Discord's frames, handshake first`(@TempDir dir: File) {
        val socket = File(dir, "discord-ipc-0")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { it.bind(UnixDomainSocketAddress.of(socket.toPath())) }
        val got = mutableListOf<Pair<Int, JsonObject>>()
        val peer = thread {
            server.accept().use { c ->
                got += read(c)
                write(c, 1, buildJsonObject {
                    put("cmd", "DISPATCH"); put("evt", "READY")
                    put("data", buildJsonObject { put("user", buildJsonObject { put("username", "dodo") }) })
                })
                got += read(c)
            }
        }
        val ipc = DiscordIpc.open("123456789", listOf(File(dir, "missing"), socket))!!
        assertEquals("dodo", ipc.user)
        ipc.setActivity(buildJsonObject { put("details", "x") }, pid = 42)
        peer.join(5_000)
        ipc.close()
        server.close()

        val (op0, hello) = got[0]
        assertEquals(0, op0)
        assertEquals("123456789", hello["client_id"]!!.jsonPrimitive.content)
        val (op1, command) = got[1]
        assertEquals(1, op1)
        assertEquals("SET_ACTIVITY", command["cmd"]!!.jsonPrimitive.content)
        assertEquals(42, command["args"]!!.jsonObject["pid"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `an application id is the environment's, else Valen's own`() {
        assertEquals("1571814573663584338", DiscordPresence.appId(null))
        assertEquals("1571814573663584338", DiscordPresence.appId(""))
        assertEquals("1571814573663584338", DiscordPresence.appId("   "))
        assertEquals("456", DiscordPresence.appId(" 456 "))
    }

    @Test
    fun `discord's sockets are looked for in the runtime folder and the sandboxed builds`() {
        val all = DiscordIpc.sockets(mapOf("XDG_RUNTIME_DIR" to "/run/user/1000")).map { it.path }
        assertTrue("/run/user/1000/discord-ipc-0" in all)
        assertTrue("/run/user/1000/app/com.discordapp.Discord/discord-ipc-0" in all)
        assertTrue("/run/user/1000/snap.discord/discord-ipc-3" in all)
        assertFalse(all.any { "//" in it })
    }

    private fun read(c: SocketChannel): Pair<Int, JsonObject> {
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (head.hasRemaining()) c.read(head)
        head.flip()
        val op = head.int
        val body = ByteBuffer.allocate(head.int)
        while (body.hasRemaining()) c.read(body)
        return op to Json.parseToJsonElement(String(body.array())).jsonObject
    }

    private fun write(c: SocketChannel, op: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray()
        val b = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(bytes.size).put(bytes)
        b.flip()
        while (b.hasRemaining()) c.write(b)
    }
}
