/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * One stream a provider can hand to a player: the address, how long it is, and whatever headers
 * its host insists on. A resolver produces it; nothing else in the app sees a media URL.
 */
data class ResolvedStream(
    val url: String,
    val durationMs: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
)

/** What a player is doing with the stream it holds. */
data class AudioState(
    val hasStream: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val volume: Float = 1f,
)

/**
 * The platform's audio output. A provider resolves a stream and hands it here; the player reports
 * the transport back. There is no page and no browser behind this on the desktop.
 *
 * A player is expected to survive a failed stream: the next [play] starts cleanly.
 */
interface AudioPlayer {
    val state: StateFlow<AudioState>

    /** True when the player can start part-way into the stream. */
    val canSeek: Boolean

    suspend fun play(stream: ResolvedStream, startMs: Long)
    suspend fun pause()
    suspend fun resume()
    suspend fun seek(positionMs: Long)
    suspend fun setVolume(volume: Float)
    suspend fun stop()
    suspend fun release()
}
