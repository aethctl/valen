/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.PresenceButton
import app.valen.core.PresenceCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

class DiscordPresenceTest {
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

    @Test
    fun `a settings change or a seek sends the card again, its clock tick does not`() {
        val a = card()
        assertTrue(DiscordPresence.cardChanged(null, a), "the first card is always sent")
        assertTrue(DiscordPresence.cardChanged(a, null), "and clearing it")
        assertTrue(DiscordPresence.cardChanged(a, a.copy(details = "Another song")))
        assertTrue(DiscordPresence.cardChanged(a, a.copy(largeImage = PresenceCard.MARK)))
        assertTrue(DiscordPresence.cardChanged(a, a.copy(buttons = emptyList())))
        assertFalse(DiscordPresence.cardChanged(a, a.copy(startMs = a.startMs!! + 900, endMs = a.endMs!! + 900)), "a second of clock jitter is the same card")
        assertTrue(DiscordPresence.cardChanged(a, a.copy(startMs = a.startMs!! + 4_000, endMs = a.endMs!! + 4_000)), "a seek moves the bar")
        assertTrue(DiscordPresence.cardChanged(a, a.copy(startMs = null, endMs = null)), "the time off is a change")
    }

    private fun card(startMs: Long = 1_000_000, endMs: Long = 1_248_000) = PresenceCard(
        memberText = "Valen",
        details = "Get Lucky",
        state = "Daft Punk",
        detailsUrl = "https://open.spotify.com/track/x",
        largeImage = "https://i.scdn.co/image/abc",
        largeText = "Random Access Memories · from Spotify",
        smallImage = PresenceCard.MARK,
        smallText = "Valen · one queue for every service",
        buttons = listOf(PresenceButton("Play on Spotify", "https://open.spotify.com/track/x")),
        startMs = startMs,
        endMs = endMs,
    )

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
