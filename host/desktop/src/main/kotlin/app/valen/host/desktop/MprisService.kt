/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.PlaybackCoordinator
import app.valen.core.PlayerSnapshot
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.messages.constants.MessageTypes
import org.freedesktop.dbus.types.Variant

/**
 * `org.mpris.MediaPlayer2.valen` on the session bus: the GNOME/KDE media widget, `playerctl`, the
 * lock screen and the volume keys all drive the same [PlaybackCoordinator] the window does.
 *
 * The coordinator owns the queue and the one snapshot, so this service is a view: the watcher
 * mirrors `PlaybackStatus`, `Metadata` and `Volume` as `PropertiesChanged`, `Position` is computed
 * at read time (MPRIS expects the instant of the query, not of the last poll), and the methods call
 * back into the coordinator on the app's scope.
 */
class MprisService(
    private val coordinator: PlaybackCoordinator,
    private val scope: CoroutineScope,
    private val identity: String = "Valen",
    private val desktopEntry: String = "valen",
) : AutoCloseable {

    private var connection: DBusConnection? = null
    private var watcher: Job? = null
    private val player = Player()

    /** Position at the last snapshot, in microseconds, and when it was taken. */
    @Volatile private var positionUs: Long = 0
    @Volatile private var positionAt: Long = 0
    @Volatile private var playing: Boolean = false

    /** The reason the last [start] failed, when it did (no session bus, name taken). */
    @Volatile var lastError: String? = null
        private set

    val busName: String = "$NAME.$desktopEntry"

    /** Exports the player and asks for [busName]; false when there is no session bus to talk to. */
    fun start(): Boolean {
        if (connection != null) return true
        val conn = runCatching {
            DBusConnectionBuilder.forSessionBus().withShared(false).build()
        }.onFailure { lastError = "No session bus: ${it.message}" }.getOrNull() ?: return false
        return runCatching {
            conn.exportObject(PATH, player)
            conn.requestBusName(busName)
            connection = conn
            watch()
            refreshPosition()
            true
        }.onFailure {
            lastError = "Could not take $busName: ${it.message}"
            runCatching { conn.close() }
        }.getOrDefault(false)
    }

    override fun close() {
        if (System.getenv("VALEN_MPRIS_LOG") != null) System.err.println("valen: MPRIS closing (${Throwable().stackTrace.take(4).joinToString(" < ") { it.toString() }})")
        watcher?.cancel()
        watcher = null
        val conn = connection ?: return
        connection = null
        runCatching { conn.releaseBusName(busName) }
        runCatching { conn.unExportObject(PATH) }
        runCatching { conn.close() }
    }

    private fun watch() {
        watcher = scope.launch {
            var lastStatus: String? = null
            var lastMetadata: String? = null
            var lastVolume: Double? = null
            var lastCaps: String? = null
            combine(coordinator.snapshot, coordinator.queue, coordinator.index) { snap, queue, index ->
                Triple(snap, queue, index)
            }.collect { (snap, queue, index) ->
                refreshPosition(snap)

                val status = statusOf(snap, queue)
                if (status != lastStatus) {
                    lastStatus = status
                    emitChanged(mapOf("PlaybackStatus" to text(status)))
                }

                val metadata = metadataOf(snap?.track)
                val metadataKey = metadata.toString()
                if (metadataKey != lastMetadata) {
                    lastMetadata = metadataKey
                    emitChanged(mapOf("Metadata" to variant(metadata, "a{sv}")))
                }

                val volume = (snap?.volume ?: coordinator.settings.value.volume).toDouble()
                if (volume != lastVolume) {
                    lastVolume = volume
                    emitChanged(mapOf("Volume" to number(volume)))
                }

                val caps = "${snap?.canSeek}|${snap?.canSkip}|$index|${queue.size}"
                if (caps != lastCaps) {
                    lastCaps = caps
                    emitChanged(
                        mapOf(
                            "CanSeek" to bool(snap?.canSeek ?: true),
                            "CanGoNext" to bool((snap?.canSkip ?: true) && index < queue.lastIndex),
                            "CanGoPrevious" to bool(queue.isNotEmpty()),
                        ),
                    )
                }
            }
        }
    }

    private fun refreshPosition(snap: PlayerSnapshot? = coordinator.snapshot.value) {
        positionUs = (snap?.positionMs ?: 0L) * 1000
        positionAt = System.currentTimeMillis()
        playing = snap?.isPlaying == true && snap.isBuffering.not()
    }

    /** The position MPRIS asks for: where the song is now, not where it was at the last poll. */
    private fun livePositionUs(): Long {
        val stored = positionUs
        if (!playing) return stored
        val elapsed = (System.currentTimeMillis() - positionAt).coerceAtLeast(0) * 1000
        val duration = coordinator.snapshot.value?.durationMs?.takeIf { it > 0 }?.times(1000)
        val position = stored + elapsed
        return if (duration != null) position.coerceAtMost(duration) else position
    }

    private fun statusOf(snap: PlayerSnapshot?, queue: List<Track>): String = when {
        snap?.track == null && queue.isEmpty() -> STOPPED
        snap?.isPlaying == true -> PLAYING
        else -> PAUSED
    }

    private fun metadataOf(track: Track?): Map<String, Variant<*>> {
        if (track == null) return emptyMap()
        val out = LinkedHashMap<String, Variant<*>>()
        out["mpris:trackid"] = variant(trackPath(track.key), "o")
        if (track.durationMs > 0) out["mpris:length"] = variant(track.durationMs * 1000, "x")
        out["xesam:title"] = text(track.title)
        out["xesam:artist"] = variant(listOf(track.artist), "as")
        track.album?.let { out["xesam:album"] = text(it) }
        track.artworkUrl?.takeIf { it.startsWith("http") }?.let { out["mpris:artUrl"] = text(it) }
        return out
    }

    private fun trackPath(key: String): DBusPath {
        val safe = buildString {
            append("/app/valen/track/")
            key.forEach { append(if (it.isLetterOrDigit()) it else '_') }
        }
        return DBusPath(safe)
    }

    private fun emitChanged(changed: Map<String, Variant<*>>) {
        val conn = connection ?: return
        runCatching { conn.sendMessage(Properties.PropertiesChanged(PATH, PLAYER, changed, emptyList())) }
    }

    private fun emitSeeked() {
        val conn = connection ?: return
        runCatching { conn.sendMessage(MprisPlayer.Seeked(PATH, livePositionUs())) }
    }

    /** Text, numbers and paths with the signature MPRIS names them by. */
    private fun text(value: String): Variant<*> = variant(value, "s")
    private fun bool(value: Boolean): Variant<*> = variant(value, "b")
    private fun number(value: Double): Variant<*> = variant(value, "d")
    private fun variant(value: Any, signature: String): Variant<*> = Variant(value, signature)

    /** One object exports both MPRIS interfaces and `org.freedesktop.DBus.Properties`. */
    inner class Player : MprisRoot, MprisPlayer, Properties {

        override fun getObjectPath(): String = PATH

        // org.mpris.MediaPlayer2
        override fun Raise() { /* a desktop app without windows of its own to raise */ }
        override fun Quit() { /* MPRIS CanQuit is false: closing belongs to the window */ }

        // org.mpris.MediaPlayer2.Player
        override fun Next() { scope.launch { coordinator.next() } }
        override fun Previous() { scope.launch { coordinator.previous() } }
        override fun Pause() { scope.launch { if (coordinator.snapshot.value?.isPlaying == true) coordinator.toggle() } }
        override fun Play() { scope.launch { if (coordinator.snapshot.value?.isPlaying != true) coordinator.toggle() } }
        override fun PlayPause() { scope.launch { coordinator.toggle() } }
        override fun Stop() {
            scope.launch {
                if (coordinator.snapshot.value?.isPlaying == true) coordinator.toggle()
                coordinator.seek(0)
            }
        }

        override fun Seek(offsetUs: Long) {
            scope.launch {
                val target = (livePositionUs() + offsetUs).coerceAtLeast(0)
                coordinator.seek(target / 1000)
                emitSeeked()
            }
        }

        override fun SetPosition(trackId: DBusPath, positionUs: Long) {
            val current = coordinator.snapshot.value?.track
            // A track id that is not the song playing now is ignored, as the spec asks.
            if (current != null && trackPath(current.key) != trackId) return
            scope.launch {
                coordinator.seek(positionUs.coerceAtLeast(0) / 1000)
                emitSeeked()
            }
        }

        override fun OpenUri(uri: String) { /* no uri's are accepted: the queue is the app's */ }

        // org.freedesktop.DBus.Properties
        //
        // The library's own Properties interface, not a lookalike: dbus-java only steps aside for
        // properties when the exported object implements this one (otherwise it answers from its
        // annotation-based property table, which MPRIS's dynamic names are not part of).
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            (GetAll(interfaceName)[propertyName] ?: throw IllegalArgumentException("no property $propertyName")) as A

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            ROOT -> linkedMapOf(
                "Identity" to text(identity),
                "DesktopEntry" to text(desktopEntry),
                "CanQuit" to bool(false),
                "CanRaise" to bool(false),
                "CanControl" to bool(true),
                "HasTrackList" to bool(false),
                "SupportedUriSchemes" to variant(emptyList<String>(), "as"),
                "SupportedMimeTypes" to variant(listOf("audio/mpeg", "audio/ogg", "audio/mp4"), "as"),
            )
            PLAYER -> {
                val snap = coordinator.snapshot.value
                linkedMapOf(
                    "PlaybackStatus" to text(statusOf(snap, coordinator.queue.value)),
                    "Metadata" to variant(metadataOf(snap?.track), "a{sv}"),
                    "Position" to variant(livePositionUs(), "x"),
                    "Volume" to number((snap?.volume ?: coordinator.settings.value.volume).toDouble()),
                    "Rate" to number(1.0),
                    "MinimumRate" to number(1.0),
                    "MaximumRate" to number(1.0),
                    "LoopStatus" to text("None"),
                    "Shuffle" to bool(false),
                    "CanSeek" to bool(snap?.canSeek ?: true),
                    "CanGoNext" to bool((snap?.canSkip ?: true) && coordinator.index.value < coordinator.queue.value.lastIndex),
                    "CanGoPrevious" to bool(coordinator.queue.value.isNotEmpty()),
                    "CanPlay" to bool(true),
                    "CanPause" to bool(true),
                    "CanControl" to bool(true),
                )
            }
            else -> emptyMap()
        }

        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
            if (interfaceName != PLAYER) return
            val written = (value as? Variant<*>)?.value ?: value
            when (propertyName) {
                "Volume" -> (written as? Number)?.let { scope.launch { coordinator.setVolume(it.toFloat().coerceIn(0f, 1f)) } }
            }
        }
    }

    companion object {
        const val NAME = "org.mpris.MediaPlayer2"
        const val PATH = "/org/mpris/MediaPlayer2"
        const val ROOT = "org.mpris.MediaPlayer2"
        const val PLAYER = "org.mpris.MediaPlayer2.Player"
        const val PROPS = "org.freedesktop.DBus.Properties"

        private const val PLAYING = "Playing"
        private const val PAUSED = "Paused"
        private const val STOPPED = "Stopped"
    }
}

/** The root interface: two methods, both deliberately quiet. */
@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

/** The player: transport, seek, and the spec's `Seeked` signal. */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun Play()
    fun PlayPause()
    fun Stop()
    fun Seek(offsetUs: Long)
    fun SetPosition(trackId: DBusPath, positionUs: Long)
    fun OpenUri(uri: String)

    /**
     * The spec's `Seeked`: the position jumped, so a client may stop interpolating from the last
     * one. Declared here, in the interface that carries its name and signature: dbus-java names a
     * signal by its class and takes its interface from the class it is declared in, and a signal
     * assembled by hand arrives as a malformed message (the daemon drops the whole connection).
     */
    class Seeked(path: String, positionUs: Long) : DBusSignal(path, positionUs)
}
