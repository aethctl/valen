/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID

/**
 * Discord's local RPC, the way its desktop client offers it on Linux: a Unix socket
 * (`$XDG_RUNTIME_DIR/discord-ipc-N`, or inside a Flatpak's or snap's own folder) that takes framed
 * JSON — a little-endian op code and length, then the text. Op 0 is the handshake (the
 * application id), op 1 a command (`SET_ACTIVITY`), 2 a close, 3/4 ping and pong.
 *
 * Nothing leaves the machine from here: Discord's own client sends the activity on.
 */
class DiscordIpc private constructor(private val channel: SocketChannel) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }
    private val writing = Any()

    /** The account Discord is signed in as, from its READY answer. */
    var user: String? = null
        private set

    private fun handshake(clientId: String) {
        send(OP_HANDSHAKE, buildJsonObject { put("v", 1); put("client_id", clientId) })
        val (op, body) = read()
        when (op) {
            OP_FRAME -> {
                val data = body["data"] as? JsonObject
                val u = data?.get("user") as? JsonObject
                user = u?.get("global_name")?.jsonPrimitive?.contentOrNull
                    ?: u?.get("username")?.jsonPrimitive?.contentOrNull
                if (body["evt"]?.jsonPrimitive?.contentOrNull == "ERROR") {
                    throw Refused(data?.get("message")?.jsonPrimitive?.contentOrNull ?: "Discord refused Valen")
                }
            }
            OP_CLOSE -> throw Refused(body["message"]?.jsonPrimitive?.contentOrNull ?: "Discord closed the link")
            else -> throw IOException("Discord answered the handshake with op $op")
        }
    }

    /** Shows [activity] (null clears it) for this process. */
    fun setActivity(activity: JsonElement?, pid: Long = ProcessHandle.current().pid()) {
        send(
            OP_FRAME,
            buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                put("args", buildJsonObject {
                    put("pid", pid)
                    put("activity", activity ?: JsonNull)
                })
                put("nonce", UUID.randomUUID().toString())
            },
        )
    }

    /**
     * The next answer, ping answered on the way. Blocks; a closed socket throws. Answers to
     * commands carry `evt: ERROR` when Discord did not take one.
     */
    fun next(): JsonObject {
        while (true) {
            val (op, body) = read()
            when (op) {
                OP_PING -> send(OP_PONG, body)
                OP_CLOSE -> throw Refused(body["message"]?.jsonPrimitive?.contentOrNull ?: "Discord closed the link")
                else -> return body
            }
        }
    }

    private fun send(op: Int, body: JsonElement) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(8 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(op).putInt(bytes.size).put(bytes).flip()
        synchronized(writing) { while (frame.hasRemaining()) channel.write(frame) }
    }

    private fun read(): Pair<Int, JsonObject> {
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fill(head)
        head.flip()
        val op = head.int
        val length = head.int
        if (length < 0 || length > MAX_FRAME) throw IOException("Discord sent a frame of $length bytes")
        val body = ByteBuffer.allocate(length)
        fill(body)
        val text = String(body.array(), Charsets.UTF_8)
        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        return op to parsed
    }

    private fun fill(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw IOException("Discord closed the link")
        }
    }

    override fun close() {
        runCatching { send(OP_CLOSE, JsonObject(emptyMap())) }
        runCatching { channel.close() }
    }

    /** Discord said no (an unknown application id, most often). Not worth retrying at once. */
    class Refused(message: String) : IOException(message)

    companion object {
        const val OP_HANDSHAKE = 0
        const val OP_FRAME = 1
        const val OP_CLOSE = 2
        const val OP_PING = 3
        const val OP_PONG = 4
        private const val MAX_FRAME = 1 shl 20

        /** Opens the first socket a running Discord answers on, handshaken; null when none is there. */
        fun open(clientId: String, candidates: List<File> = sockets()): DiscordIpc? {
            var refused: Refused? = null
            for (path in candidates) {
                if (!path.exists()) continue
                val channel = runCatching {
                    SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(path.toPath())) }
                }.getOrNull() ?: continue
                val ipc = DiscordIpc(channel)
                try {
                    ipc.handshake(clientId)
                    return ipc
                } catch (e: Refused) {
                    refused = e
                    runCatching { channel.close() }
                } catch (_: IOException) {
                    runCatching { channel.close() }
                }
            }
            refused?.let { throw it }
            return null
        }

        /**
         * Where Discord's sockets live: the runtime folder and the temp folders, plain and inside the
         * Flatpak and snap builds (Discord, Canary, PTB, Vesktop), numbered 0 to 9.
         */
        fun sockets(env: Map<String, String> = System.getenv()): List<File> {
            val bases = listOfNotNull(env["XDG_RUNTIME_DIR"], env["TMPDIR"], env["TMP"], env["TEMP"], "/tmp")
                .filter { it.isNotBlank() }.distinct()
            val inside = listOf(
                "",
                "app/com.discordapp.Discord/",
                "app/com.discordapp.DiscordCanary/",
                "app/com.discordapp.DiscordPTB/",
                "app/dev.vencord.Vesktop/",
                ".flatpak/dev.vencord.Vesktop/xdg-run/",
                "snap.discord/",
                "snap.discord-canary/",
            )
            return bases.flatMap { base -> inside.flatMap { sub -> (0..9).map { File("$base/$sub" + "discord-ipc-$it") } } }
        }
    }
}
