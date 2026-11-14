/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The desktop's audio output: `ffplay` (from FFmpeg), one process per stream.
 *
 * Why a process rather than a decoder in the JVM: Opus and MP4/AAC have no maintained pure-Java
 * decoder, and a bundled native decoder would add tens of megabytes for the same result. FFmpeg is
 * already the media layer of most Linux desktops and plays both services' streams (MP4/AAC, Opus,
 * MP3 and HLS) without a browser.
 *
 * The transport is driven by the process itself: `-ss` starts part-way in, SIGSTOP/SIGCONT pause,
 * and the process exiting on its own is the end of the stream. Because FFmpeg does not report a
 * position, [positionMs] is measured from the moment playback started; a seek restarts the process
 * at the requested offset.
 *
 * A machine without `ffplay` reports it on the first play instead of failing silently; the message
 * names the package to install.
 */
class FfplayPlayer(
    private val scope: CoroutineScope,
    private val binary: String? = findBinary(),
) : AudioPlayer {

    private val _state = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = _state.asStateFlow()

    override val canSeek: Boolean get() = true

    private val lock = Any()

    private var process: Process? = null
    private var stream: ResolvedStream? = null
    private var volume: Float = 1f

    /** Where the current process started, and when: the position is the two together. */
    private var offsetMs: Long = 0L
    private var startedAtNanos: Long = 0L
    private var pausedAtMs: Long? = null
    private var stopping: Process? = null

    private var ticker: Job? = null
    private var lastError: String? = null

    init {
        // A Valen that is killed leaves no FFmpeg behind playing to an empty room.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                synchronized(lock) { process }?.let { proc -> runCatching { proc.destroyForcibly() } }
            },
        )
    }

    /** FFmpeg's own last words: the useful sentence when a stream will not open. */
    private val diagnostics = StringBuilder()

    override suspend fun play(stream: ResolvedStream, startMs: Long) {
        stopProcess()
        val bin = binary ?: throw IllegalStateException(MISSING_FFPLAY)
        this.stream = stream
        lastError = null
        offsetMs = startMs.coerceAtLeast(0L)
        pausedAtMs = null
        _state.value = AudioState(
            hasStream = true,
            positionMs = offsetMs,
            durationMs = stream.durationMs,
            playing = true,
            buffering = true,
            volume = volume,
        )
        val proc = try {
            start(bin, stream, offsetMs)
        } catch (e: Exception) {
            val message = e.message ?: MISSING_FFPLAY
            lastError = message
            _state.value = _state.value.copy(playing = false, buffering = false, error = message)
            throw IllegalStateException(message, e)
        }
        attach(proc)
        tick()
    }

    override suspend fun pause() {
        val proc = process ?: return
        if (pausedAtMs != null) return
        pausedAtMs = positionOf(System.nanoTime())
        signal(proc, "STOP")
        _state.value = _state.value.copy(playing = false, buffering = false, positionMs = pausedAtMs ?: 0)
    }

    override suspend fun resume() {
        val proc = process ?: return
        val at = pausedAtMs ?: return
        pausedAtMs = null
        // The clock restarts from where the pause happened, so the position stays honest.
        offsetMs = at
        startedAtNanos = System.nanoTime()
        signal(proc, "CONT")
        _state.value = _state.value.copy(playing = true)
    }

    override suspend fun seek(positionMs: Long) {
        val s = stream ?: return
        val bin = binary ?: return
        val at = positionMs.coerceAtLeast(0L).coerceAtMost(if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE)
        val wasPlaying = _state.value.playing
        stopProcess()
        offsetMs = at
        pausedAtMs = null
        val proc = runCatching { start(bin, s, at) }.getOrElse {
            val message = it.message ?: MISSING_FFPLAY
            lastError = message
            _state.value = _state.value.copy(playing = false, buffering = false, error = message)
            return
        }
        attach(proc)
        if (!wasPlaying) {
            pausedAtMs = at
            signal(proc, "STOP")
        }
        _state.value = _state.value.copy(
            positionMs = at,
            playing = wasPlaying,
            buffering = true,
            ended = false,
            error = null,
        )
        tick()
    }

    override suspend fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        val changed = kotlin.math.abs(v - this.volume) > 0.005f
        this.volume = v
        val s = stream
        // FFmpeg takes the level at start-up only; a mid-song change restarts the process there.
        if (changed && s != null && process != null) {
            seek(_state.value.positionMs)
        } else {
            _state.value = _state.value.copy(volume = v)
        }
    }

    override suspend fun stop() {
        stopProcess()
        stream = null
        offsetMs = 0
        pausedAtMs = null
        _state.value = AudioState(volume = volume)
    }

    override suspend fun release() {
        ticker?.cancel()
        ticker = null
        stop()
    }

    // ── The process ──

    private fun start(bin: String, stream: ResolvedStream, atMs: Long): Process {
        val command = ArrayList<String>(16)
        command += bin
        command += listOf(
            "-hide_banner", "-loglevel", "error", "-nodisp", "-autoexit",
            "-volume", (volume * 100).toInt().coerceIn(0, 100).toString(),
        )
        if (atMs > 0) command += listOf("-ss", String.format(java.util.Locale.ROOT, "%.3f", atMs / 1000.0))
        if (stream.headers.isNotEmpty()) {
            command += listOf("-headers", stream.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
        }
        command += stream.url
        synchronized(diagnostics) { diagnostics.setLength(0) }
        val proc = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        // Its output is the only account of a stream it refuses; keep the last lines for the UI.
        scope.launch(Dispatchers.IO) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    synchronized(diagnostics) {
                        diagnostics.append(line).append('\n')
                        if (diagnostics.length > 2000) diagnostics.delete(0, diagnostics.length - 2000)
                    }
                }
            }
        }
        return proc
    }

    private fun attach(proc: Process) {
        synchronized(lock) { process = proc }
        startedAtNanos = System.nanoTime()
        scope.launch(Dispatchers.IO) {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            onExit(proc, code)
        }
    }

    private fun onExit(proc: Process, code: Int) {
        // A process this player stopped itself says nothing about the stream.
        val current = synchronized(lock) {
            if (stopping === proc) {
                stopping = null
                false
            } else if (process === proc) {
                process = null
                true
            } else {
                false
            }
        }
        if (!current) return
        // Played to the end: FFmpeg exits 0 at EOF. Anything else is a stream it could not open.
        if (code == 0) {
            val at = _state.value.durationMs
            _state.value = _state.value.copy(
                playing = false,
                buffering = false,
                ended = true,
                positionMs = if (at > 0) at else _state.value.positionMs,
            )
        } else {
            val said = synchronized(diagnostics) {
                diagnostics.toString().trim().lineSequence().lastOrNull { it.isNotBlank() }
            }
            val message = lastError ?: said ?: "the stream stopped (ffplay exited $code)"
            lastError = message
            _state.value = _state.value.copy(playing = false, buffering = false, error = message)
        }
    }

    private fun stopProcess() {
        val proc = synchronized(lock) {
            val p = process
            process = null
            stopping = p
            p
        } ?: return
        runCatching { proc.destroy() }
        scope.launch(Dispatchers.IO) {
            if (!proc.waitFor(600, TimeUnit.MILLISECONDS)) runCatching { proc.destroyForcibly() }
        }
    }

    /** SIGSTOP/SIGCONT: the process is the transport, so it is paused where it is, not restarted. */
    private suspend fun signal(proc: Process, signal: String) {
        val pid = runCatching { proc.pid() }.getOrNull() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                ProcessBuilder("kill", "-$signal", pid.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(1, TimeUnit.SECONDS)
            }
        }
    }

    /** The clock the UI reads, refreshed while something is playing. */
    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (stream == null) break
                val buffering = _state.value.buffering && System.nanoTime() - startedAtNanos > WARM_UP_NANOS
                _state.value = _state.value.copy(
                    positionMs = positionOf(System.nanoTime()),
                    buffering = if (_state.value.playing) buffering else false,
                )
            }
        }
    }

    private fun positionOf(nanos: Long): Long {
        pausedAtMs?.let { return it }
        if (startedAtNanos == 0L) return offsetMs
        val played = (nanos - startedAtNanos) / 1_000_000
        val position = offsetMs + played
        val duration = _state.value.durationMs.takeIf { it > 0 } ?: stream?.durationMs ?: 0
        return if (duration > 0) position.coerceAtMost(duration) else position
    }

    companion object {
        private const val TICK_MS = 300L
        private const val WARM_UP_NANOS = 700_000_000L

        const val MISSING_FFPLAY =
            "native playback needs ffplay from FFmpeg; install ffmpeg (or set VALEN_FFPLAY_BIN)"

        /** `VALEN_FFPLAY_BIN`, else `ffplay` from PATH. */
        fun findBinary(): String? {
            val override = System.getenv("VALEN_FFPLAY_BIN")?.takeIf { it.isNotBlank() }
            if (override != null && File(override).canExecute()) return override
            val path = System.getenv("PATH").orEmpty()
            for (dir in path.split(File.pathSeparatorChar)) {
                if (dir.isBlank()) continue
                val file = File(dir, "ffplay")
                if (file.isFile && file.canExecute()) return file.absolutePath
            }
            return null
        }
    }
}
