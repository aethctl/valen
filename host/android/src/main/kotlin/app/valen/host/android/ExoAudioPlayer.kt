/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.valen.audio.AudioPlayer
import app.valen.audio.AudioState
import app.valen.audio.ResolvedStream
import app.valen.audio.SoundDsp
import app.valen.core.SoundEffects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Android's [AudioPlayer]: ExoPlayer, audio only, playing the stream a resolver found (YouTube
 * Music's and SoundCloud's, as on the desktop), with Valen's effects inside its audio chain
 * ([SoundDsp] ahead of ExoPlayer's own speed and pitch).
 *
 * - The player lives on the main thread; every call goes there.
 * - It takes the audio focus and pauses for a call or when headphones come out, as a music app
 *   must; that pause is reported like any other, so the app shows it.
 * - [play] returns once the stream is open (or after [OPEN_WAIT_MS]) and throws when it will not
 *   open, so an address that expired is resolved again by the caller.
 * - A stream that keeps buffering for [stallAfterMs] while it should play is reported stalled.
 */
@OptIn(UnstableApi::class)
class ExoAudioPlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    /** Songs kept on disk: what is kept is read from it, what streams is kept in it. */
    private val songs: ExoStreamCache? = null,
    private val stallAfterMs: Long = 12_000L,
) : AudioPlayer {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val dsp = SoundDsp()
    private val processor = DspProcessor(dsp)

    private val _state = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = _state.asStateFlow()
    override val canSeek: Boolean = true
    override val takesEffects: Boolean = true

    // Main thread only.
    private var exo: ExoPlayer? = null
    private var stream: ResolvedStream? = null
    private var error: String? = null
    private var started = false
    private var bufferingSince = 0L
    private var opened: CompletableDeferred<String?>? = null
    private var ticker: Job? = null
    private var volume = 1f
    private var params = PlaybackParameters.DEFAULT

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                android.util.Log.d(TAG, "state ${STATES.getOrElse(player.playbackState) { "?" }} at ${player.currentPosition} ms")
            }
            if (player.playbackState == Player.STATE_READY) { opened?.complete(null); opened = null }
            if (player.isPlaying) started = true
            emit()
        }

        override fun onPlayerError(e: PlaybackException) {
            val text = describe(e)
            android.util.Log.w(TAG, "stream failed: $text")
            error = text
            opened?.complete(text)
            opened = null
            emit()
        }
    }

    private fun player(): ExoPlayer = exo ?: build().also { exo = it }

    private fun build(): ExoPlayer {
        // Audio only: one renderer, whose sink runs Valen's effects first.
        val renderers = RenderersFactory { handler, _, audio, _, _ ->
            val sink = DefaultAudioSink.Builder(app).setAudioProcessors(arrayOf<AudioProcessor>(processor)).build()
            arrayOf(MediaCodecAudioRenderer(app, MediaCodecSelector.DEFAULT, handler, audio, sink))
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Music, not video: sound starts after a second of it, and up to two minutes are kept
        // ahead (a few MB), so a phone passing through a dead spot keeps playing.
        val buffering = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 1_000, 2_500)
            .build()
        return ExoPlayer.Builder(app, renderers)
            .setLoadControl(buffering)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also {
                it.addListener(listener)
                it.volume = volume
                it.playbackParameters = params
            }
    }

    private fun emit() {
        val p = exo ?: return
        val s = stream
        if (s == null) {
            _state.value = AudioState(volume = volume)
            return
        }
        val st = p.playbackState
        val want = p.playWhenReady
        val buffering = error == null && (st == Player.STATE_BUFFERING || st == Player.STATE_IDLE)
        val now = SystemClock.elapsedRealtime()
        if (buffering && want) { if (bufferingSince == 0L) bufferingSince = now } else bufferingSince = 0L
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: s.durationMs
        _state.value = AudioState(
            hasStream = true,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            playing = want && st != Player.STATE_ENDED && error == null,
            buffering = buffering,
            ended = st == Player.STATE_ENDED,
            error = error,
            volume = volume,
            started = started,
            stalled = bufferingSince != 0L && now - bufferingSince > stallAfterMs,
        )
    }

    /** Position and stalls are read while a stream is held (the player has no event for either). */
    private fun tick() {
        if (ticker?.isActive == true) return
        ticker = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && stream != null) {
                emit()
                delay(if (exo?.playWhenReady == true) TICK_MS else IDLE_TICK_MS)
            }
        }
    }

    override suspend fun play(stream: ResolvedStream, startMs: Long) {
        val wait = onMain {
            val p = player()
            opened?.complete(null)
            val ready = CompletableDeferred<String?>()
            opened = ready
            this.stream = stream
            error = null
            started = false
            bufferingSince = 0L
            android.util.Log.d(TAG, "opening ${Uri.parse(stream.url).host} at $startMs ms" + if (isHls(stream.url)) " (HLS)" else "")
            p.setMediaSource(source(stream), startMs.coerceAtLeast(0L))
            p.playWhenReady = true
            p.prepare()
            emit()
            tick()
            ready
        }
        val failure = withTimeoutOrNull(OPEN_WAIT_MS) { wait.await() }
        if (failure != null) throw IllegalStateException(failure)
    }

    private fun source(stream: ResolvedStream) = run {
        val hls = isHls(stream.url)
        val key = stream.cacheKey?.takeIf { songs != null && !hls }
        // Through the song cache when the song may be kept (and to read a kept one); a plain
        // network (or file) source otherwise.
        val data: DataSource.Factory = if (key != null) songs!!.dataSources(stream.headers)
        else DefaultDataSource.Factory(app, OkHttpDataSource.Factory(http).setDefaultRequestProperties(stream.headers))
        val item = MediaItem.Builder().setUri(Uri.parse(stream.url))
        // SoundCloud's segmented streams are HLS playlists; the factory tells the rest by itself.
        if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (key != null) item.setCustomCacheKey(key)
        DefaultMediaSourceFactory(data).createMediaSource(item.build())
    }

    override suspend fun pause() = onMain {
        exo?.playWhenReady = false
        emit()
    }

    override suspend fun resume() = onMain {
        val p = exo ?: return@onMain
        if (stream == null) return@onMain
        // After a failure the player sits idle: it is prepared again where it was.
        if (p.playbackState == Player.STATE_IDLE) { error = null; p.prepare() }
        p.playWhenReady = true
        emit()
        tick()
    }

    override suspend fun seek(positionMs: Long) = onMain {
        val p = exo ?: return@onMain
        if (stream == null) return@onMain
        p.seekTo(positionMs.coerceAtLeast(0L))
        emit()
    }

    override suspend fun setVolume(volume: Float) = onMain {
        this.volume = volume.coerceIn(0f, 1f)
        exo?.volume = this.volume
        emit()
    }

    override suspend fun setEffects(effects: SoundEffects) {
        val e = effects.effective()
        dsp.set(e)
        val next = PlaybackParameters(e.speed.coerceIn(0.25f, 4f), e.pitchRatio.coerceIn(0.25f, 4f))
        onMain {
            params = next
            exo?.playbackParameters = next
        }
    }

    override suspend fun stop() = onMain {
        opened?.complete(null)
        opened = null
        stream = null
        error = null
        started = false
        ticker?.cancel()
        ticker = null
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        _state.value = AudioState(volume = volume)
    }

    override suspend fun release() {
        stop()
        onMain {
            exo?.release()
            exo = null
        }
    }

    private suspend fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CompletableDeferred<T>()
        main.post { runCatching(block).fold(done::complete, done::completeExceptionally) }
        return done.await()
    }

    /**
     * Valen's effects as an ExoPlayer audio processor: 16-bit or float PCM, one or two channels
     * (anything else passes untouched). Flat settings copy the samples as they came.
     */
    private class DspProcessor(private val dsp: SoundDsp) : BaseAudioProcessor() {
        private var work = FloatArray(0)

        override fun onConfigure(input: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (input.encoding != C.ENCODING_PCM_16BIT && input.encoding != C.ENCODING_PCM_FLOAT) {
                throw AudioProcessor.UnhandledAudioFormatException(input)
            }
            if (input.channelCount !in 1..2) {
                android.util.Log.i(TAG, "effects off for this stream: ${input.channelCount} channels")
                return AudioProcessor.AudioFormat.NOT_SET
            }
            dsp.configure(input.sampleRate, input.channelCount)
            android.util.Log.i(TAG, "effects chain on: ${input.sampleRate} Hz, ${input.channelCount} ch, " +
                if (input.encoding == C.ENCODING_PCM_FLOAT) "float" else "16-bit")
            return input
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val bytes = inputBuffer.remaining()
            if (bytes == 0) return
            val out = replaceOutputBuffer(bytes)
            if (!dsp.active) {
                out.put(inputBuffer)
                out.flip()
                return
            }
            val src = inputBuffer.order(ByteOrder.nativeOrder())
            val format = inputAudioFormat
            val channels = format.channelCount
            if (format.encoding == C.ENCODING_PCM_16BIT) {
                val n = bytes / 2
                if (work.size < n) work = FloatArray(n)
                for (i in 0 until n) work[i] = src.getShort() / 32768f
                dsp.process(work, n / channels)
                for (i in 0 until n) out.putShort((work[i] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
            } else {
                val n = bytes / 4
                if (work.size < n) work = FloatArray(n)
                for (i in 0 until n) work[i] = src.getFloat()
                dsp.process(work, n / channels)
                for (i in 0 until n) out.putFloat(work[i])
            }
            // A buffer that ended mid-sample (it never does) is not carried over.
            inputBuffer.position(inputBuffer.limit())
            out.flip()
        }
    }

    companion object {
        /** How long [play] waits for the stream to open before handing over to the state. */
        const val OPEN_WAIT_MS = 10_000L
        private const val TAG = "ValenAudio"
        private val STATES = listOf("?", "idle", "buffering", "ready", "ended")
        private const val TICK_MS = 250L
        private const val IDLE_TICK_MS = 1_000L

        fun isHls(url: String): Boolean {
            val path = url.substringBefore('?')
            return path.endsWith(".m3u8") || path.contains("/playlist") && url.contains("m3u8")
        }

        /** The failure in words: the HTTP status when there is one, else ExoPlayer's own name. */
        fun describe(e: PlaybackException): String {
            val cause = generateSequence(e.cause) { it.cause }.firstOrNull { it.message != null }?.message
            return listOfNotNull(e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' '), cause)
                .joinToString(": ")
        }
    }
}
