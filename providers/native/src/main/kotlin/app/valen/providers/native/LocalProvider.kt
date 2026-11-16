/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.valen.audio.AudioPlayer
import app.valen.audio.NativePlayback
import app.valen.audio.ResolvedStream
import app.valen.core.AudioQuality
import app.valen.core.EffectSupport
import app.valen.core.LocalPlaylistExtensions
import app.valen.core.MusicProvider
import app.valen.core.PlayerSnapshot
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLink
import app.valen.core.PlaylistRef
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.ReadPlaylist
import app.valen.core.SessionState
import app.valen.core.SoundEffects
import app.valen.core.Track
import app.valen.core.fileTitle
import app.valen.core.isAudioPath
import app.valen.core.parseLocalPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The files on this device as one more source: songs whose id is their path, played by the same
 * native sink as YouTube Music and SoundCloud (so the sound effects apply), and playlists read from
 * playlist files (`m3u`, `m3u8`, `pls`, `xspf`) or whole folders.
 *
 * It also plays every song the [kept] shelf has a file for — a Spotify song downloaded as its
 * YouTube Music copy included — under that song's own [Track], so the queue, the likes and the
 * stage never see the difference.
 *
 * A playlist entry whose file is not here (a list made on another machine) is still listed: it is
 * marked [ReadPlaylist.blocked], so the importer looks for the same recording on the services.
 */
class LocalProvider(
    private val playback: NativePlayback,
    private val tags: LocalTags,
    /** The device songs Valen already knows (the library's), which search looks through. */
    private val known: () -> List<Track>,
    /** Where playlist files are looked for when the import screen asks ("~/Music"). */
    private val musicFolder: File?,
    private val home: String = System.getProperty("user.home"),
) : MusicProvider, PlaylistCatalog {

    override val id: ProviderId = ProviderId.LOCAL
    override val provider: ProviderId get() = id

    private val _session = MutableStateFlow<SessionState>(SessionState.Connected("This device"))
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    override val player: StateFlow<PlayerSnapshot?> get() = playback.snapshot
    override val events: SharedFlow<ProviderEvent> get() = playback.events

    override suspend fun connect() {}
    override suspend fun disconnect() {}

    override suspend fun search(query: String, limit: Int): List<Track> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || limit <= 0) return emptyList()
        return known().asSequence()
            .filter { it.provider == ProviderId.LOCAL }
            .distinctBy { it.key }
            .mapNotNull { t ->
                val title = t.title.lowercase()
                val hay = "$title ${t.artist.lowercase()} ${t.album.orEmpty().lowercase()}"
                if (words.all { it in hay }) t to words.count { it in title } else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
            .toList()
    }

    override suspend fun play(track: Track, startMs: Long) = playback.play(track, startMs)
    override suspend fun pause() = playback.pause()
    override suspend fun resume() = playback.resume()
    override suspend fun stop() = playback.stop()
    override suspend fun prepare(track: Track) {}
    override suspend fun seek(positionMs: Long) = playback.seek(positionMs)
    override suspend fun setVolume(volume: Float) = playback.setVolume(volume)
    override suspend fun setQuality(quality: AudioQuality) {}

    override val effectSupport: EffectSupport
        get() = if (playback.takesEffects) EffectSupport.Full else EffectSupport.None("Needs FFmpeg's ffplay")

    override suspend fun setEffects(effects: SoundEffects) = playback.setEffects(effects)

    override suspend fun close() = playback.release()

    /** Playlist files in the music folder (two levels down), newest first. */
    override suspend fun playlists(): List<PlaylistRef> = withContext(Dispatchers.IO) {
        val root = musicFolder?.takeIf { it.isDirectory } ?: return@withContext emptyList()
        root.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.extension.lowercase() in LocalPlaylistExtensions }
            .sortedByDescending { it.lastModified() }
            .take(MAX_LISTED)
            .map { f ->
                val count = runCatching {
                    parseLocalPlaylist(f.readText(), f.parentFile.absolutePath, f.extension).entries.size
                }.getOrDefault(0)
                PlaylistRef(
                    name = f.nameWithoutExtension,
                    trackCount = count,
                    owner = f.parentFile.toRelativeStringOr(home),
                    link = PlaylistLink(ProviderId.LOCAL, f.absolutePath, f.absolutePath),
                )
            }
            .toList()
    }

    override suspend fun read(link: PlaylistLink): ReadPlaylist = withContext(Dispatchers.IO) {
        val target = File(expand(link.playlistId ?: link.url)).let { runCatching { it.canonicalFile }.getOrDefault(it) }
        if (!target.exists()) throw IOException("${target.path} is not on this device")
        val (name, entries) = when {
            target.isDirectory -> target.name to folderEntries(target)
            target.extension.lowercase() in LocalPlaylistExtensions -> {
                val parsed = parseLocalPlaylist(target.readText(), target.parentFile.absolutePath, target.extension)
                (parsed.name ?: target.nameWithoutExtension) to parsed.entries.map { Entry(File(expand(it.path)), it.title, it.artist, it.durationMs) }
            }
            isAudioPath(target.path) -> fileTitle(target.path) to listOf(Entry(target))
            else -> throw IOException("Choose a playlist file (m3u, pls, xspf) or a folder of songs")
        }
        val blocked = HashSet<String>()
        val tracks = readAll(entries) { track, present -> if (!present) blocked += track.key }
        val missing = blocked.size
        ReadPlaylist(
            name = name,
            tracks = tracks,
            artworkUrl = tracks.firstNotNullOfOrNull { t -> t.artworkUrl?.takeIf { t.key !in blocked } },
            note = when (missing) {
                0 -> null
                1 -> "1 song is not on this device, so Valen looked for it on your services."
                else -> "$missing songs are not on this device, so Valen looked for them on your services."
            },
            link = PlaylistLink(ProviderId.LOCAL, target.absolutePath, target.absolutePath),
            blocked = blocked,
        )
    }

    private class Entry(val file: File, val title: String? = null, val artist: String? = null, val durationMs: Long = 0L)

    private fun folderEntries(folder: File): List<Entry> =
        folder.walkTopDown().maxDepth(8)
            .filter { it.isFile && isAudioPath(it.name) }
            .sortedWith(compareBy(NaturalOrder) { it.relativeTo(folder).path })
            .take(MAX_SONGS)
            .map { Entry(it) }
            .toList()

    /** Every entry as a song, its tags read a few files at a time; [mark] says which files are here. */
    private suspend fun readAll(entries: List<Entry>, mark: (Track, Boolean) -> Unit): List<Track> = coroutineScope {
        val gate = Semaphore(TAG_READERS)
        entries.map { e ->
            async {
                val present = e.file.isFile
                val tag = if (present) gate.withPermit { runCatching { tags.read(e.file) }.getOrNull() } else null
                val named = FfprobeTags.fromName(e.file)
                Track(
                    provider = ProviderId.LOCAL,
                    id = e.file.absolutePath,
                    title = tag?.title ?: e.title ?: named.title ?: fileTitle(e.file.path),
                    artist = tag?.artist ?: e.artist ?: named.artist ?: "",
                    album = tag?.album,
                    artworkUrl = tag?.artworkUrl,
                    durationMs = tag?.durationMs?.takeIf { it > 0 } ?: e.durationMs,
                ) to present
            }
        }.awaitAll().map { (track, present) ->
            mark(track, present)
            track
        }
    }

    private fun expand(path: String): String = if (path.startsWith("~/")) home + path.substring(1) else path

    private fun File.toRelativeStringOr(base: String): String =
        absolutePath.let { if (it.startsWith(base)) "~" + it.removePrefix(base) else it }

    companion object {
        private const val TAG_READERS = 6
        private const val MAX_SONGS = 5_000
        private const val MAX_LISTED = 60

        /**
         * The device's player: a local song resolves to its own path, any other song to the file the
         * shelf keeps for it ([kept]). A file that has gone says so in words.
         */
        fun playback(sink: AudioPlayer, scope: CoroutineScope, kept: (Track) -> String?): NativePlayback =
            NativePlayback(
                player = sink,
                scope = scope,
                idFor = { t -> if (t.provider == ProviderId.LOCAL) t.id else kept(t) ?: t.id },
            ) { path, _ -> localStream(path) }

        fun localStream(path: String): ResolvedStream {
            val file = File(path)
            if (!file.isFile) throw IOException("${file.name} is not on this device any more")
            return ResolvedStream(url = file.absolutePath)
        }

        /** "Track 2" before "Track 10". */
        private val NaturalOrder = Comparator<String> { a, b ->
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]
                val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    (x.toBigIntegerOrNull() ?: 0.toBigInteger()).compareTo(y.toBigIntegerOrNull() ?: 0.toBigInteger())
                } else {
                    x.compareTo(y)
                }
                if (c != 0) return@Comparator c
            }
            ra.size - rb.size
        }
    }
}
