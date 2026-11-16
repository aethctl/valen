/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps "Offline" in the library: every song that plays with no network — downloaded (the shelf),
 * or kept whole by its service's cache — newest listening first. It is there only while it has a
 * song, and Settings can turn it off (it then leaves the library).
 *
 * Nothing is polled. It is worked out again, a moment after things settle, when the downloads
 * change, when the library changes, when a song starts (the one before it has usually been kept
 * whole by then), or when the setting does. The songs it can know are the ones Valen has met: the
 * library's lists, the queue, the downloads, and everything listened to.
 */
class OfflineList(
    private val coordinator: PlaybackCoordinator,
    private val library: PlaylistLibrary,
    private val scope: CoroutineScope,
    /** Where the songs are worked out: off the main thread (a file check per known song). */
    private val work: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        val shelf = coordinator.offline?.state?.map { it.kept.size to it.files } ?: kotlinx.coroutines.flow.flowOf(0 to 0)
        val lists = library.playlists.map { all -> all.filterNot { it.isOffline }.map { it.id to it.tracks.size } }.distinctUntilChanged()
        val song = coordinator.nowPlaying.map { it?.track?.key }.distinctUntilChanged()
        val on = coordinator.settings.map { it.offlineList }.distinctUntilChanged()
        scope.launch {
            combine(on, shelf, lists, song) { enabled, _, _, _ -> enabled }
                .debounce(SETTLE_MS)
                .collect { enabled ->
                    val songs = if (enabled) withContext(work) { songs() } else emptyList()
                    runCatching { library.setOffline(songs) }
                }
        }
    }

    /** Every known song that plays offline, most listened first, then as the library has them. */
    fun songs(): List<Track> {
        val known = LinkedHashMap<String, Track>()
        val log = coordinator.stats.log.value
        log.songs.entries.sortedByDescending { it.value.ms }.forEach { (key, heard) ->
            fromLog(key, heard)?.let { known.putIfAbsent(key, it) }
        }
        coordinator.offline?.singleTracks()?.forEach { known.putIfAbsent(it.key, it) }
        library.playlists.value.filterNot { it.isOffline }.forEach { list -> list.tracks.forEach { known.putIfAbsent(it.key, it) } }
        coordinator.queue.value.forEach { known.putIfAbsent(it.key, it) }
        // A song met in a list carries more (its length, its link) than the log's note of it.
        val fuller = HashMap<String, Track>()
        library.playlists.value.forEach { list -> list.tracks.forEach { fuller.putIfAbsent(it.key, it) } }
        coordinator.queue.value.forEach { fuller.putIfAbsent(it.key, it) }
        return known.values.asSequence()
            .map { fuller[it.key] ?: it }
            .filter { coordinator.playsOffline(it) }
            .take(MAX_SONGS)
            .toList()
    }

    private fun fromLog(key: String, heard: Listened): Track? {
        val id = key.substringAfter(':', "").takeIf { it.isNotBlank() } ?: return null
        return Track(
            provider = heard.provider,
            id = id,
            title = heard.title,
            artist = heard.artist,
            album = heard.album,
            artworkUrl = heard.artworkUrl,
        )
    }

    companion object {
        /** Long enough for a burst of changes (a download finishing its list) to land as one. */
        const val SETTLE_MS = 1_500L
        const val MAX_SONGS = 1_000
    }
}
