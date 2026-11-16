/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Playlists kept on this device as files: every song of a kept list is downloaded once, at the best
 * quality a service offers, and from then on plays from its file — offline, with no lookup and no
 * service asked. The platform owns the files (the desktop's shelf in `libs/audio`); core only asks
 * which songs are kept and starts or stops a list.
 *
 * A song's file is found by its [Track.key], so a kept song plays from disk whichever service the
 * list names it on. A Spotify song is never fetched from Spotify (its stream is copy-protected and
 * its servers are not asked at all): the shelf keeps the same recording from a service that can be
 * downloaded, matched as strictly as an import ([TrackMatcher]).
 */
interface OfflineShelf {
    val state: StateFlow<OfflineState>

    /** The kept file for [track] (a path), or null when the song is not on this device. */
    fun fileFor(track: Track): String?

    fun has(track: Track): Boolean = fileFor(track) != null

    /**
     * Keeps [playlist] on this device: its songs are downloaded one after another, and songs added
     * to it later follow. Calling it again for a kept list fetches what is still missing.
     */
    fun keep(playlist: Playlist)

    /** Stops keeping the list and deletes the songs no other kept list needs. */
    suspend fun forget(playlistId: String)

    /** Keeps one song on this device by itself, whatever list it is in (or none). */
    fun keepSong(track: Track) {}

    /** Stops keeping a song kept by itself; its file goes unless a kept list still has it. */
    suspend fun forgetSong(track: Track) {}

    /** The songs kept by themselves, whole (the state names them by key only). */
    fun singleTracks(): List<Track> = emptyList()

    /** Where the songs are written, as a person would read the path. */
    val folder: String
}

/** What the shelf is doing, for every list it keeps. */
data class OfflineState(
    val playlists: Map<String, PlaylistDownload> = emptyMap(),
    /** Every song key that has a file on this device (a Spotify song and the copy it was kept as both). */
    val kept: Set<String> = emptySet(),
    /** How many song files are on this device: one per file, however many keys name it. */
    val files: Int = 0,
    /** Songs kept by themselves (the song menu's Download), by key. */
    val singles: Set<String> = emptySet(),
    /** Songs being fetched now, by key, with how far along (0..1). */
    val fetching: Map<String, Float> = emptyMap(),
    /** Songs waiting their turn, by key. */
    val waiting: Set<String> = emptySet(),
    /** Songs no downloadable copy could be kept of this run, by key, with why. */
    val unavailable: Map<String, String> = emptyMap(),
    /**
     * Set while the downloads wait for a service that asked Valen to slow down: the sentence to
     * show, and when they start again (epoch millis).
     */
    val resting: Rest? = null,
) {
    data class Rest(val reason: String, val until: Long)
}

/** One kept list: how many of its songs are on this device and what is being fetched now. */
data class PlaylistDownload(
    val total: Int = 0,
    val done: Int = 0,
    /** Songs no service could offer a downloadable copy of, with why (title — artist → reason). */
    val missing: Map<String, String> = emptyMap(),
    /** The song being fetched now ("Title — Artist"); null when the list is idle. */
    val current: String? = null,
    /** 0..1 of the song being fetched. */
    val currentFraction: Float = 0f,
) {
    val running: Boolean get() = current != null
    val complete: Boolean get() = total > 0 && done + missing.size >= total && current == null
}

/** The services whose songs a shelf downloads; every other song is kept as its copy from one of them. */
val DownloadableServices: List<ProviderId> = listOf(ProviderId.YOUTUBE_MUSIC, ProviderId.SOUNDCLOUD)

/**
 * The copy of [track] a shelf downloads: the song itself on YouTube Music or SoundCloud; for any
 * other service the same recording found there (YouTube Music first: its song results are the
 * labels' own audio), accepted only as strictly as an import accepts a match. Spotify is never
 * asked anything. [search] looks on one service.
 */
suspend fun downloadableCopy(track: Track, search: suspend (ProviderId, String) -> List<Track>): Track? {
    if (track.provider in DownloadableServices) return track
    val query = TrackMatcher.query(track)
    if (query.isBlank()) return null
    for (id in DownloadableServices) {
        val found = runCatching { search(id, query) }.getOrDefault(emptyList())
        TrackMatcher.best(track, found)?.let { return it }
    }
    return null
}
