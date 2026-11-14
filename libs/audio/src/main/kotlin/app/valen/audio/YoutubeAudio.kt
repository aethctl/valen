/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.AudioQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * YouTube Music without a browser: the stream URL of a video id, from the extractor the NewPipe app
 * uses (NewPipeExtractor).
 *
 * Handing YouTube's plumbing to that library is deliberate. A plain InnerTube `player` call returns
 * addresses that only serve their first megabyte until the client's `n` challenge is solved, which
 * is why an `mpv` on such a URL gets a 403; the extractor solves the challenge and the returned
 * address plays and seeks normally (verified with ffplay, mpv and yt-dlp side by side).
 *
 * The extractor works on Android too, so this same class can serve an ExoPlayer sink later.
 */
class YoutubeAudio(
    http: OkHttpClient = defaultHttp(),
    private val language: String = "en",
    private val country: String = "US",
) {

    private val http = http

    /** The stream for [videoId], chosen for [quality]. */
    suspend fun resolve(videoId: String, quality: AudioQuality): ResolvedStream = withContext(Dispatchers.IO) {
        init()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://music.youtube.com/watch?v=$videoId")
        } catch (e: ContentNotAvailableException) {
            throw IllegalStateException("YouTube Music has no stream for this song: ${e.message}")
        } catch (e: AgeRestrictedContentException) {
            throw IllegalStateException("YouTube Music wants the song's age confirmed in a browser")
        } catch (e: PrivateContentException) {
            throw IllegalStateException("this song is private on YouTube Music")
        } catch (e: Exception) {
            throw IllegalStateException(e.message ?: "YouTube Music did not answer", e)
        }
        val streams = info.audioStreams.filter { it.content.isNotBlank() }
        if (streams.isEmpty()) {
            throw IllegalStateException("YouTube Music has no audio stream for this song")
        }
        val chosen = choose(streams, quality)
        ResolvedStream(
            url = chosen.content,
            durationMs = if (info.duration > 0) info.duration * 1000 else 0L,
        )
    }

    /**
     * AUTO keeps to MP4/AAC (the most predictable to seek), HIGH takes the largest bitrate of any
     * format, STANDARD the smallest.
     */
    private fun choose(streams: List<AudioStream>, quality: AudioQuality): AudioStream = when (quality) {
        AudioQuality.HIGH -> streams.maxBy { it.averageBitrate }
        AudioQuality.STANDARD -> streams.minBy { it.averageBitrate }
        AudioQuality.AUTO -> streams.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: streams.maxBy { it.averageBitrate }
    }

    private fun init() {
        synchronized(INIT_LOCK) {
            if (READY) return
            NewPipe.init(OkHttpDownloader(http), Localization(language, country), ContentCountry(country))
            READY = true
        }
    }

    /** NewPipe's HTTP contract over the app's own OkHttp client. */
    private class OkHttpDownloader(private val http: OkHttpClient) : Downloader() {
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request):
            org.schabi.newpipe.extractor.downloader.Response {
            val builder = okhttp3.Request.Builder().url(request.url())
            for ((name, values) in request.headers()) for (value in values) builder.addHeader(name, value)
            val data = request.dataToSend()
            builder.method(request.httpMethod(), data?.toRequestBody())
            http.newCall(builder.build()).execute().use { response ->
                return org.schabi.newpipe.extractor.downloader.Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string().orEmpty(),
                    response.request.url.toString(),
                )
            }
        }
    }

    companion object {
        private val INIT_LOCK = Any()

        @Volatile private var READY = false

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
