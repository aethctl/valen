/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

/**
 * Songs kept on disk, so a song heard before (or looked up ahead) starts at once, with no address
 * to find and no network at all. [NativePlayback] asks it before resolving and tells it about
 * every stream it plays or prepares; how the copy is made is the platform's business: the desktop
 * downloads whole files ([DiskAudioCache]), Android lets ExoPlayer keep what it streams.
 *
 * Keys are a song's provider and id (`youtube_music-dQw4w9WgXcQ`), never an address: addresses
 * expire, the song does not.
 */
interface StreamCache {
    /** A stream for [key] that plays from the disk alone, when the whole song is kept. */
    fun offline(key: String): ResolvedStream?

    /**
     * [stream] was just resolved for [key]. [playing]: it is starting now (the platform may keep
     * it as it plays, or fetch a copy beside it); otherwise it is likely next, to fetch ahead.
     */
    fun keep(key: String, stream: ResolvedStream, playing: Boolean)

    /** Whether the whole song is kept, without counting as a play (nothing is touched). */
    fun has(key: String): Boolean = offline(key) != null

    /** The kept copy of [key] would not play: it is dropped, and the song is resolved again. */
    fun forget(key: String)
}
