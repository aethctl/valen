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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * and the process exiting on its own is the end of the stream. The position is ffplay's own audio
 * clock (its `-stats` line, about thirty a second): anchored where sound really began, and counted
 * from there in the clock's own steps times the playback rate — the clock runs in output time
 * through `atempo`/`rubberband` and in rescaled time through a turntable's `asetrate`, and the step
 * is right for both. The clock standing still is buffering; standing still for long is a stalled
 * stream ([AudioState.stalled]), which the caller opens again. An ffplay without the line falls back
 * to timing from the start of the process.
 *
 * A machine without `ffplay` reports it on the first play instead of failing silently; the message
 * names the package to install.
 */
class FfplayPlayer(
    private val scope: CoroutineScope,
    private val binary: String? = findBinary(),
    /** Where the effects' rooms (impulse responses) are written. */
    private val roomDir: java.nio.file.Path = FfmpegEffects.defaultRoomDir(),
    /** Extra environment for ffplay (tests run it with SDL's silent dummy driver). */
    private val environment: Map<String, String> = emptyMap(),
    /** How long the clock may stand still once sound began before the stream counts as stalled. */
    private val stallAfterMs: Long = 12_000L,
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

    // ffplay's clock, per process: where it was when sound began (and the song position then), where
    // it is now, and when it last moved. Written by the process's reader, read by the tick.
    @Volatile private var clockSeen = false
    @Volatile private var clockAnchor = Double.NaN
    @Volatile private var anchorMs = 0L
    @Volatile private var lastClock = Double.NaN
    @Volatile private var movedAtNanos = 0L
    @Volatile private var openedAtNanos = 0L
    /** Clock readings to let pass before anchoring again (the first after a resume can jump). */
    @Volatile private var skipReadings = 0

    /**
     * When ffplay's audio queue ran dry (0 while sound had been coming), and its clock then. Its
     * clock goes on over the silence it plays while it waits, so the song's time stops there.
     */
    @Volatile private var starvedAtNanos = 0L
    @Volatile private var starvedClock = Double.NaN

    /** The effects as an `-af` graph (null: none), and seconds of song per second heard. */
    @Volatile private var filter: String? = null
    @Volatile private var rate: Double = 1.0
    /** The rate actually running; a newly selected rate takes effect only after the restart. */
    @Volatile private var processRate: Double = 1.0
    private var restart: Job? = null
    private var reader: Job? = null
    private var earlyRetries = 0

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

    /**
     * One operation at a time: the delayed restart of an effects change runs beside the caller's own
     * seeks and pauses, and two restarts at once each started a process — one of them orphaned for
     * good, playing on under the other.
     */
    private val ops = Mutex()

    override suspend fun play(stream: ResolvedStream, startMs: Long) = ops.withLock { playLocked(stream, startMs) }

    private suspend fun playLocked(stream: ResolvedStream, startMs: Long) {
        restart?.cancel()
        stopProcess()
        val bin = binary ?: throw IllegalStateException(MISSING_FFPLAY)
        this.stream = stream
        earlyRetries = 0
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

    override suspend fun pause() = ops.withLock { pauseLocked() }

    private suspend fun pauseLocked() {
        val proc = process ?: return
        if (pausedAtMs != null) return
        pausedAtMs = positionOf(System.nanoTime())
        signal(proc, "STOP")
        _state.value = _state.value.copy(playing = false, buffering = false, positionMs = pausedAtMs ?: 0)
    }

    override suspend fun resume() = ops.withLock { resumeLocked() }

    private suspend fun resumeLocked() {
        val proc = process ?: return
        val at = pausedAtMs ?: return
        pausedAtMs = null
        // The clock restarts from where the pause happened, so the position stays honest: ffplay's
        // own clock is anchored again once it has caught up with the time it was stopped for.
        offsetMs = at
        startedAtNanos = System.nanoTime()
        anchorMs = at
        clockAnchor = Double.NaN
        skipReadings = 2
        movedAtNanos = System.nanoTime()
        signal(proc, "CONT")
        _state.value = _state.value.copy(playing = true)
    }

    override suspend fun seek(positionMs: Long) = ops.withLock {
        restart?.cancel()
        earlyRetries = 0
        seekLocked(positionMs)
    }

    private suspend fun seekLocked(positionMs: Long) {
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
            started = false,
            stalled = false,
        )
        tick()
    }

    override suspend fun setVolume(volume: Float) = ops.withLock {
        val v = volume.coerceIn(0f, 1f)
        val changed = kotlin.math.abs(v - this.volume) > 0.005f
        this.volume = v
        _state.value = _state.value.copy(volume = v)
        // Coalesce a slider drag and a simultaneous effects change into one restart.
        if (changed && stream != null && process != null) scheduleRestart()
    }

    override val takesEffects: Boolean get() = binary != null

    /**
     * New effects. FFmpeg takes its filters at start-up only, like the volume, so a playing stream
     * is restarted where it is with them — once a quick run of changes has settled, never per
     * change. The old process's clock keeps its old rate until the new process starts.
     */
    override suspend fun setEffects(effects: app.valen.core.SoundEffects) {
        val bin = binary ?: return
        val graph = withContext(Dispatchers.IO) {
            FfmpegEffects.graph(effects, FfmpegEffects.filters(bin)) { FfmpegEffects.room(it, roomDir) }
        }
        val nextRate = FfmpegEffects.rate(effects)
        ops.withLock { effectsLocked(graph, nextRate) }
    }

    private fun effectsLocked(graph: String?, nextRate: Double) {
        if (graph == filter && nextRate == rate) return
        filter = graph
        rate = nextRate
        if (stream == null || process == null) return
        scheduleRestart()
    }

    private fun scheduleRestart() {
        restart?.cancel()
        restart = scope.launch {
            delay(RESTART_SETTLE_MS)
            ops.withLock {
                // Once the restart has begun, a later control change must not cancel it halfway
                // through starting or pausing the replacement process.
                restart = null
                if (process != null) seekLocked(positionOf(System.nanoTime()))
            }
        }
    }

    override suspend fun stop() = ops.withLock { stopLocked() }

    private fun stopLocked() {
        restart?.cancel()
        stopProcess()
        stream = null
        offsetMs = 0
        pausedAtMs = null
        _state.value = AudioState(volume = volume)
    }

    override suspend fun release() = ops.withLock {
        ticker?.cancel()
        ticker = null
        stopLocked()
    }

    // ── The process ──

    private fun start(bin: String, stream: ResolvedStream, atMs: Long): Process {
        val command = ArrayList<String>(16)
        command += bin
        command += listOf(
            "-hide_banner", "-loglevel", "error", "-stats", "-nodisp", "-autoexit",
            "-volume", (volume * 100).toInt().coerceIn(0, 100).toString(),
        )
        if (atMs > 0) command += listOf("-ss", String.format(java.util.Locale.ROOT, "%.3f", atMs / 1000.0))
        filter?.let { command += listOf("-af", it) }
        if (stream.headers.isNotEmpty()) {
            command += listOf("-headers", stream.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
        }
        command += stream.url
        synchronized(diagnostics) { diagnostics.setLength(0) }
        resetClock()
        processRate = rate
        anchorMs = atMs
        val proc = ProcessBuilder(command)
            .redirectErrorStream(true)
            .also { it.environment().putAll(environment) }
            .start()
        // Its output is its clock (the status line) and, otherwise, the only account of a stream it
        // refuses: the last lines are kept for the UI.
        reader = scope.launch(Dispatchers.IO) {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val clock = STATUS.find(line)
                    if (clock != null) {
                        reading(proc, clock.groupValues[1].toDoubleOrNull(), QUEUE.find(line)?.groupValues?.get(1)?.toIntOrNull())
                        // An error printed straight after a status line shares its line.
                        val said = TAIL.find(line)?.groupValues?.get(1)?.trim().orEmpty()
                        if (said.isNotEmpty()) synchronized(diagnostics) { diagnostics.append(said).append('\n') }
                        return@forEachLine
                    }
                    if (line.contains("M-A:") || line.contains("A-V:")) return@forEachLine
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
        val processReader = reader
        scope.launch(Dispatchers.IO) {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            processReader?.join()
            ops.withLock { onExit(proc, code) }
        }
    }

    private suspend fun onExit(proc: Process, code: Int) {
        // A process this player stopped itself says nothing about the stream.
        var sounded = false
        var clocked = false
        val current = synchronized(lock) {
            if (stopping === proc) {
                stopping = null
                false
            } else if (process === proc) {
                // The clock is this process's: read it before it is let go.
                sounded = clockSeen && !clockAnchor.isNaN()
                clocked = clockSeen
                process = null
                true
            } else {
                false
            }
        }
        if (!current) return
        if (earlyRetries > 0 && !sounded) {
            val message = "The stream stopped before the song finished. Try playing it again."
            lastError = message
            _state.value = _state.value.copy(playing = false, buffering = false, stalled = false, ended = false, error = message)
            return
        }
        // ffplay exits 0 at the end of a stream — and also when it could not open one at all (a
        // missing file, a refused connection, an expired address). So an exit is an end only when
        // sound came out; an ffplay without the clock line has only its exit code to go by.
        if (code == 0 && (sounded || !clocked)) {
            val at = if (sounded) positionOf(System.nanoTime()) else _state.value.durationMs.takeIf { it > 0 } ?: _state.value.positionMs
            val duration = _state.value.durationMs
            // A connection can end cleanly before the media does. Do not tell the queue to advance
            // in that case: reopen at the last heard position, then report an error if it persists.
            if (stream?.checkEarlyEnd == true && sounded && duration > 0 && duration - at > maxOf(EARLY_END_MARGIN_MS, duration / 100)) {
                if (earlyRetries++ < MAX_EARLY_RETRIES) {
                    seekLocked(at)
                } else {
                    val message = "The stream stopped before the song finished. Try playing it again."
                    lastError = message
                    _state.value = _state.value.copy(playing = false, buffering = false, stalled = false, ended = false, positionMs = at, error = message)
                }
                return
            }
            _state.value = _state.value.copy(
                playing = false,
                buffering = false,
                stalled = false,
                ended = true,
                positionMs = at,
            )
        } else {
            val said = synchronized(diagnostics) {
                diagnostics.toString().trim().lineSequence().lastOrNull { it.isNotBlank() }
            }?.let(::plain)?.takeIf { it.isNotBlank() }
            val message = lastError ?: said ?: if (code == 0) "the stream would not open" else "the stream stopped (ffplay exited $code)"
            lastError = message
            _state.value = _state.value.copy(playing = false, buffering = false, stalled = false, error = message)
        }
    }

    private fun stopProcess() {
        val proc = synchronized(lock) {
            val p = process
            process = null
            stopping = p
            p
        } ?: return
        // A paused process is stopped (SIGSTOP) and would hold a polite TERM until it is continued:
        // it goes at once. A playing one gets the chance to close its stream.
        if (pausedAtMs != null) runCatching { proc.destroyForcibly() } else runCatching { proc.destroy() }
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
                val now = System.nanoTime()
                val playing = _state.value.playing && process != null
                val started = clockSeen && !clockAnchor.isNaN()
                val buffering: Boolean
                val stalled: Boolean
                if (clockSeen) {
                    // The clock standing still while it should move is buffering; for long, a stall.
                    val still = now - movedAtNanos
                    buffering = playing && (!started || still > STILL_NANOS)
                    stalled = playing && (if (started) still > stallAfterMs * 1_000_000L else now - openedAtNanos > OPEN_NANOS)
                } else {
                    buffering = playing && _state.value.buffering && now - startedAtNanos > WARM_UP_NANOS
                    stalled = false
                }
                _state.value = _state.value.copy(
                    positionMs = positionOf(now),
                    buffering = buffering,
                    started = _state.value.started || started,
                    stalled = stalled,
                )
            }
        }
    }

    /** A new process: no clock yet. */
    private fun resetClock() {
        clockSeen = false
        clockAnchor = Double.NaN
        lastClock = Double.NaN
        skipReadings = 0
        starvedAtNanos = 0L
        starvedClock = Double.NaN
        openedAtNanos = System.nanoTime()
        movedAtNanos = openedAtNanos
    }

    /**
     * One reading from [proc]'s status line: its clock (seconds; null for its "nan" before sound) and
     * how much is queued to play ([queueKb]; 0 is a stream that is not arriving).
     */
    private fun reading(proc: Process, clock: Double?, queueKb: Int?) {
        if (process !== proc) return
        clockSeen = true
        if (clock == null || clock.isNaN() || pausedAtMs != null) return
        if (skipReadings > 0) {
            skipReadings--
            return
        }
        val now = System.nanoTime()
        if (clockAnchor.isNaN()) {
            clockAnchor = clock
            lastClock = clock
            movedAtNanos = now
            return
        }
        if (queueKb == 0) {
            if (starvedAtNanos == 0L) {
                starvedAtNanos = now
                starvedClock = clock
            }
            // What was already decoded still plays out for a moment; after that the clock is only
            // counting silence, and the song is not moving.
            if (now - starvedAtNanos > STARVED_NANOS) return
        } else if (queueKb != null) {
            starvedAtNanos = 0L
            starvedClock = Double.NaN
        }
        if (clock > lastClock + 0.0005) {
            lastClock = clock
            movedAtNanos = now
        }
    }

    private fun positionOf(nanos: Long): Long {
        pausedAtMs?.let { return it }
        if (clockSeen) {
            // Before sound, the stream is where it was asked to start.
            if (clockAnchor.isNaN()) return anchorMs
            val position = anchorMs + ((lastClock - clockAnchor) * 1000.0 * processRate).toLong()
            val duration = _state.value.durationMs.takeIf { it > 0 } ?: stream?.durationMs ?: 0
            return if (duration > 0) position.coerceIn(0, duration) else position.coerceAtLeast(0)
        }
        if (startedAtNanos == 0L) return offsetMs
        val played = ((nanos - startedAtNanos) / 1_000_000 * processRate).toLong()
        val position = offsetMs + played
        val duration = _state.value.durationMs.takeIf { it > 0 } ?: stream?.durationMs ?: 0
        return if (duration > 0) position.coerceAtMost(duration) else position
    }

    companion object {
        /** How long effects must stay put before a playing stream is restarted with them. */
        private const val RESTART_SETTLE_MS = 220L
        private const val EARLY_END_MARGIN_MS = 3_000L
        private const val MAX_EARLY_RETRIES = 2

        private const val TICK_MS = 250L
        private const val WARM_UP_NANOS = 700_000_000L

        /** The clock still this long while playing: buffering. */
        private const val STILL_NANOS = 700_000_000L

        /** Silent this long after opening: a stream that never really opened. */
        private const val OPEN_NANOS = 25_000_000_000L

        /** What follows a status line on the same line: an error ffplay printed straight after it. */
        private val TAIL = Regex("sq=\\s*-?\\d+B\\s*(.*)$")

        /**
         * ffplay's words, fit to show: no stream address (a long signed URL says nothing to a
         * person and should not be shown) and no "[tcp @ 0x…]" source tag.
         */
        internal fun plain(said: String): String = said
            .replace(Regex("^\\[[^\\]]*]\\s*"), "")
            .replace(Regex("(?:https?|tcp|tls)://\\S+?:\\s"), "")
            .replace(Regex("(?:https?|tcp|tls)://\\S+"), "the stream")
            .replace(Regex("^/\\S+?:\\s"), "")
            .trim()

        /** How long an empty queue still plays out (what was decoded) before the stream is starved. */
        private const val STARVED_NANOS = 400_000_000L

        /** The audio queue on a status line, "aq=  416KB". */
        private val QUEUE = Regex("aq=\\s*(\\d+)KB")

        /** ffplay's status line, "  12.34 M-A:  0.000 fd=…": its first number is the clock. */
        private val STATUS = Regex("^\\s*(-?\\d+(?:\\.\\d+)?|nan)\\s+(?:M-A|A-V|M-V)?\\s*:")

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
