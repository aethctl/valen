/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.PlaybackCoordinator
import app.valen.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The MPRIS interface against the session bus it is meant for: exported, named, and answerable by
 * `gdbus` the way `playerctl` and the desktop's media widget ask. Skipped where there is no session
 * bus or no `gdbus` (a headless build box), so it never fails a machine that cannot run it.
 */
class MprisServiceTest {

    @Test
    fun `the player answers on the session bus`() {
        assumeTrue(System.getenv("DBUS_SESSION_BUS_ADDRESS") != null, "no session bus")
        assumeTrue(File("/run/user/${userId()}/bus").exists(), "no session bus socket")
        assumeTrue(onPath("gdbus"), "no gdbus")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = MprisService(
            PlaybackCoordinator(emptyList(), scope, SettingsStore(read = { null }, write = {})),
            scope,
            desktopEntry = "valentest",
        )
        assumeTrue(service.start(), service.lastError ?: "could not start")
        try {
            val root = gdbus("GetAll", "org.mpris.MediaPlayer2")
            assertTrue(root.contains("'Valen'"), "identity: $root")
            assertTrue(root.contains("CanControl"), "CanControl: $root")
            val identity = gdbusGet("org.mpris.MediaPlayer2", "Identity")
            assertTrue(identity.contains("'Valen'"), "Get Identity: $identity")

            val player = gdbus("GetAll", "org.mpris.MediaPlayer2.Player")
            assertTrue(player.contains("'Stopped'"), "status: $player")
            assertTrue(player.contains("CanSeek"), "CanSeek: $player")
            assertTrue(player.contains("Volume"), "Volume: $player")
        } finally {
            service.close()
            scope.cancel()
        }
    }

    /** A queue that plays nowhere: enough for the coordinator to have a snapshot and a metadata. */
    @Test
    fun `a song playing reaches MPRIS as Playing with its metadata`() {
        assumeTrue(System.getenv("DBUS_SESSION_BUS_ADDRESS") != null, "no session bus")
        assumeTrue(File("/run/user/${userId()}/bus").exists(), "no session bus socket")
        assumeTrue(onPath("gdbus"), "no gdbus")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = SilentProvider()
        val coordinator = PlaybackCoordinator(listOf(provider), scope, SettingsStore(read = { null }, write = {}))
        val service = MprisService(coordinator, scope, desktopEntry = "valentest")
        assumeTrue(service.start(), service.lastError ?: "could not start")
        try {
            val track = app.valen.core.Track(
                app.valen.core.ProviderId.SPOTIFY, "4uLU6hMCjMI75M1A2tKUQC", "Never Gonna Give You Up",
                "Rick Astley", "Whenever You Need Somebody", durationMs = 213_000,
            )
            kotlinx.coroutines.runBlocking { coordinator.play(track) }
            Thread.sleep(700)

            val player = gdbus("GetAll", "org.mpris.MediaPlayer2.Player")
            assertTrue(player.contains("'Playing'"), "status: $player")
            assertTrue(player.contains("Never Gonna Give You Up"), "title: $player")
            assertTrue(player.contains("Rick Astley"), "artist: $player")
            assertTrue(player.contains("mpris:trackid"), "trackid: $player")
        } finally {
            service.close()
            scope.cancel()
        }
    }

    private class SilentProvider : app.valen.core.MusicProvider {
        override val id = app.valen.core.ProviderId.SPOTIFY
        override val session = kotlinx.coroutines.flow.MutableStateFlow<app.valen.core.SessionState>(
            app.valen.core.SessionState.Connected(),
        )
        override val player = kotlinx.coroutines.flow.MutableStateFlow<app.valen.core.PlayerSnapshot?>(null)
        override val events = kotlinx.coroutines.flow.MutableSharedFlow<app.valen.core.ProviderEvent>()

        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override suspend fun search(query: String, limit: Int) = emptyList<app.valen.core.Track>()
        override suspend fun play(track: app.valen.core.Track, startMs: Long) {
            player.value = app.valen.core.PlayerSnapshot(track, isPlaying = true, positionMs = startMs, durationMs = track.durationMs)
        }
        override suspend fun pause() { player.value = player.value?.copy(isPlaying = false) }
        override suspend fun resume() { player.value = player.value?.copy(isPlaying = true) }
        override suspend fun seek(positionMs: Long) { player.value = player.value?.copy(positionMs = positionMs) }
        override suspend fun setVolume(volume: Float) { player.value = player.value?.copy(volume = volume) }
        override suspend fun close() {}
    }

    private fun gdbus(method: String, interfaceName: String): String = gdbusFor(service = "org.mpris.MediaPlayer2.valentest", method = method, interfaceName = interfaceName)

    /** `Properties.Get`: the spec's single-property read, which `gdbus Get` and some clients use. */
    private fun gdbusGet(interfaceName: String, propertyName: String): String = run(
        listOf(
            "gdbus", "call", "--session",
            "--dest", "org.mpris.MediaPlayer2.valentest",
            "--object-path", MprisService.PATH,
            "--method", "org.freedesktop.DBus.Properties.Get", interfaceName, propertyName,
        ),
    )

    private fun gdbusFor(service: String, method: String, interfaceName: String): String = run(
        listOf(
            "gdbus", "call", "--session",
            "--dest", service,
            "--object-path", MprisService.PATH,
            "--method", "org.freedesktop.DBus.Properties.$method", interfaceName,
        ),
    )

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)
        return out
    }

    private fun userId(): String = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()

    private fun onPath(name: String): Boolean = ProcessBuilder("sh", "-c", "command -v $name").start().waitFor() == 0
}
