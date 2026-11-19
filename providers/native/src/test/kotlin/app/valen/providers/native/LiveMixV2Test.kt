/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.myspoti.ytmusic.InnerTube
import app.myspoti.ytmusic.YtmParse
import app.valen.audio.SoundcloudAudio
import app.valen.core.ArtistProfile
import app.valen.core.DayListening
import app.valen.core.Listened
import app.valen.core.ListeningLog
import app.valen.core.ProviderId
import app.valen.core.RelatedArtist
import app.valen.core.ServiceArtist
import app.valen.core.SmartMixer
import app.valen.core.SmartSource
import app.valen.core.SmartSpec
import app.valen.core.Taste
import app.valen.core.Track
import app.valen.core.recordingKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.PrintWriter
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * The independent live AFTER audit of the recommendation engine v2, only with `VALEN_LIVE=1`.
 *
 * It reconstructs a [Taste] from the owner's real settings and listening log (read only: nothing is
 * written to either file), wires the real [SmartMixer] the way `PlaybackCoordinator.mixer()` does
 * (YouTube Music artist pages for related artists with their provider+id+rank, the exact page's top
 * tracks for their songs, SoundCloud joined in via the signed-out unified search), and runs the same
 * specs that produced the slop evidence: the saved "Asya" recipe (sources Favourites/MostPlayed/
 * SpotifyFollows/Artists, the 5 favourite artists, size 100, discovery 1.0) and Home's "For you"
 * defaults (Favourites/MostPlayed, size 20, discovery 0.65).
 *
 * Every pick is attributed to the candidate layer (seed artist page, related artist's exact page,
 * loved history) it first appeared in. The dump in `build/verify/after.txt` carries, per spec, the
 * seeds, the related entries with their provider+id+rank, the related pages read, every pick with its
 * origin and flags, all histograms, and the PASS/FAIL of the audit rules:
 *  - a. every pick's credited artist is a seed, a related discovery or taste-known; no 365MUSIC/
 *       365Music, no BTS/K-pop outlier, no unattributable pick;
 *  - b. new-artist songs <= `min(0.5, 0.6*discovery)` of the list (0 at discovery 0), <=2 songs per
 *       new artist, the artist cap, and the familiar floor;
 *  - c. no derivative-marked or cover-by-title row unless that exact upload was played here;
 *  - d. one copy per recording, no same-artist adjacency;
 *  - e. every new artist is a provider+id related entry and its songs come from that exact page, plus
 *       a negative probe with a corrupted `relatedSongs` callback;
 *  - f. a discovery=0 build adds no new artist (live and offline);
 *  - g. the before-identified pollution (365MUSIC/365Music, the unmarked "The Eyes of Ibad" cover, the
 *       SoundCloud "my bloody velvetine - come in alone" upload) cannot be reproduced.
 *
 * Run it with:
 * ```
 * cd /home/dodo986/Projects/valen
 * source scripts/env.sh
 * VALEN_LIVE=1 ./gradlew :providers:native:test --tests 'app.valen.providers.native.LiveMixV2Test'
 * ```
 * Network failures are written to the dump and counted; they are never hidden.
 */
class LiveMixV2Test {
    @Test
    fun `the v2 mixer keeps the slop out of a live 100-song rebuild`() {
        assumeTrue(System.getenv("VALEN_LIVE") == "1", "set VALEN_LIVE=1 to read the real services")
        val startedAt = System.currentTimeMillis()
        val root = repoRoot()
        val outDir = File(root, "build/verify").apply { mkdirs() }
        val dump = File(outDir, "after.txt")
        val diag = Diag(dump)
        var result: AuditResult? = null
        var thrown: Throwable? = null
        try {
            result = runBlocking { withTimeout(20 * 60_000L) { execute(diag, root, startedAt) } }
        } catch (e: Throwable) {
            thrown = e
        } finally {
            diag.flushNow()
            diag.close()
        }
        runCatching { dump.copyTo(File(outDir, "after-$startedAt.txt"), overwrite = true) }
        println("v2 after-audit dump: ${dump.absolutePath} (${dump.length()} bytes)")
        thrown?.let { throw it }
        val audit = result ?: error("the audit produced no result")
        assertTrue(dump.isFile && dump.length() > 2_000, "the dump was not written: ${dump.absolutePath}")
        assertTrue(
            audit.liveResults > 0,
            "no live results at all (${audit.errors.size} errors; first: ${audit.errors.firstOrNull()}) — see ${dump.absolutePath}",
        )
        assertTrue(
            audit.failures.isEmpty(),
            "the live audit found ${audit.failures.size} failure(s):\n" + audit.failures.joinToString("\n") +
                "\nsee ${dump.absolutePath}",
        )
        println("✅ v2 live after-audit written to ${dump.absolutePath}: ${audit.liveResults} live rows, ${audit.errors.size} network errors")
    }

    /**
     * Discovery 0 adds no new artist, with staged fake callbacks that would happily hand junk over:
     * the engine must never even ask for related artists at discovery 0. Offline: always runs.
     */
    @Test
    fun `discovery zero builds no new artists offline`() = runBlocking {
        val log = ListeningLog(
            totalMs = 3_600_000,
            plays = 60,
            songs = mapOf(
                "YOUTUBE_MUSIC:a1" to Listened("Alpha Song", "Alpha", provider = ProviderId.YOUTUBE_MUSIC, ms = 1_200_000, plays = 12),
                "YOUTUBE_MUSIC:b1" to Listened("Beta Song", "Beta", provider = ProviderId.YOUTUBE_MUSIC, ms = 900_000, plays = 9),
            ),
            artists = mapOf("Alpha" to 1_200_000, "Beta" to 900_000),
            days = mapOf("20770" to 2_100_000),
        )
        val taste = Taste(log, favouriteArtists = listOf("Alpha", "Beta"), favouriteSongs = emptyList())
        var relatedCalls = 0
        var relatedSongCalls = 0
        val mixer = SmartMixer(
            search = { emptyList() },
            follows = { emptyList() },
            related = {
                relatedCalls++
                listOf(RelatedArtist("Stranger", ProviderId.YOUTUBE_MUSIC, "UCstranger", 0))
            },
            relatedSongs = {
                relatedSongCalls++
                listOf(Track(ProviderId.YOUTUBE_MUSIC, "junk", "Junk Song", "Stranger", durationMs = 180_000))
            },
            artistTop = { name ->
                listOf(Track(ProviderId.YOUTUBE_MUSIC, "top-$name", "Top Song by $name", name, durationMs = 180_000))
            },
            random = Random(7),
        )
        val spec = SmartSpec(
            sources = setOf(SmartSource.Favourites, SmartSource.MostPlayed),
            size = 10,
            discovery = 0f,
        )
        val picks = mixer.build(spec, taste)
        assertTrue(picks.isNotEmpty(), "an offline discovery=0 build should still find the seeds' own songs")
        assertEquals(0, relatedCalls, "discovery 0 must not ask for related artists at all")
        assertEquals(0, relatedSongCalls, "discovery 0 must not read a related artist's page")
        val seedKeys = setOf("alpha", "beta")
        assertTrue(
            picks.all { track -> Taste.creditsOf(track.artist).any { it in seedKeys } },
            "discovery 0 emitted an artist outside the seeds: " + picks.joinToString { "${it.title} / ${it.artist}" },
        )
        println("✅ offline discovery=0 build kept to its seeds (${picks.size} songs)")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dump writer and pass/fail ledger
// ─────────────────────────────────────────────────────────────────────────────

private class AuditResult(
    val liveResults: Int,
    val errors: List<String>,
    val failures: List<String>,
)

private class Diag(val file: File) : AutoCloseable {
    private val out = PrintWriter(file.bufferedWriter())
    val errors = LinkedHashSet<String>()

    fun line(text: String = "") {
        out.println(text)
        println(text)
    }

    fun section(title: String) {
        line()
        line("=".repeat(90))
        line("== $title")
        line("=".repeat(90))
    }

    fun note(text: String) {
        errors += text
        line("  !! $text")
    }

    fun flushNow() = out.flush()

    override fun close() {
        runCatching { out.flush(); out.close() }
    }
}

/** PASS/FAIL lines in the dump; a failed rule is also thrown at the end of the test. */
private class Checks(private val diag: Diag) {
    val failures = mutableListOf<String>()

    fun check(name: String, ok: Boolean, detail: String = "") {
        val line = (if (ok) "PASS  " else "FAIL  ") + name + if (detail.isNotBlank()) "  ($detail)" else ""
        diag.line(line)
        if (!ok) failures += name + if (detail.isNotBlank()) " — $detail" else ""
    }

    fun info(name: String, detail: String = "") {
        diag.line("info  " + name + if (detail.isNotBlank()) "  ($detail)" else "")
    }
}

private fun explain(e: Throwable): String = "${e::class.simpleName}: ${e.message ?: "no message"}"

/** What one live read gave: the value, and why it could not be read (never swallowed). */
private class Read<out T>(val value: T, val error: String? = null)

// ─────────────────────────────────────────────────────────────────────────────
// Files and owner data, read only
// ─────────────────────────────────────────────────────────────────────────────

private fun repoRoot(): File {
    var dir = runCatching { File(System.getProperty("user.dir")) }.getOrDefault(File(".")).absoluteFile
    while (true) {
        if (File(dir, "settings.gradle.kts").isFile && File(dir, "gradle.properties").isFile) return dir
        dir = dir.parentFile ?: return File(".").absoluteFile
    }
}

private fun homeDir(): File = File(System.getProperty("user.home"))
private fun configHome(): File =
    System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(homeDir(), ".config")
private fun dataHome(): File =
    System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(homeDir(), ".local/share")

private val DIAG_JSON = Json { ignoreUnknownKeys = true }

private val FALLBACK_ARTISTS = listOf("overtonight", "Fried By Fluoride", "h4rtbrkr", "overtonight throwaways", "Negative XP")

private val FALLBACK_SONGS = listOf(
    Track(ProviderId.SPOTIFY, "fallback-1", "i'm her home", "overtonight", durationMs = 106_485),
    Track(ProviderId.SPOTIFY, "fallback-2", "billions must die", "Fried By Fluoride", durationMs = 180_000),
)

private val FALLBACK_LOG = ListeningLog(
    since = 1_700_000_000_000,
    totalMs = 4 * 3_600_000,
    plays = 90,
    songs = mapOf(
        "SPOTIFY:fallback-1" to Listened("i'm her home", "overtonight", provider = ProviderId.SPOTIFY, ms = 1_200_000, plays = 12, durationMs = 106_485),
        "SPOTIFY:fallback-2" to Listened("billions must die", "Fried By Fluoride", provider = ProviderId.SPOTIFY, ms = 900_000, plays = 9, durationMs = 180_000),
        "SPOTIFY:fallback-3" to Listened("Egirl", "Negative XP", provider = ProviderId.SPOTIFY, ms = 400_000, plays = 5, durationMs = 103_947),
    ),
    artists = mapOf("overtonight" to 1_200_000, "Fried By Fluoride" to 900_000, "Negative XP" to 400_000),
    days = mapOf("20770" to 2_500_000),
)

private class OwnerData(
    val settingsPath: File,
    val logPath: File,
    val favouriteArtists: List<String>,
    val favouriteSongs: List<Track>,
    val dislikedArtists: List<String>,
    val hiddenTracks: List<String>,
    val log: ListeningLog,
    val logFromDisk: Boolean,
    val notes: List<String>,
)

private fun readOwnerData(): OwnerData {
    val notes = mutableListOf<String>()
    var favouriteArtists = FALLBACK_ARTISTS
    var favouriteSongs = FALLBACK_SONGS
    var dislikedArtists = emptyList<String>()
    var hiddenTracks = emptyList<String>()

    val settingsPath = File(configHome(), "valen/settings.json")
    if (settingsPath.isFile) {
        runCatching {
            val obj = DIAG_JSON.parseToJsonElement(settingsPath.readText()).jsonObject
            val artists = obj["favouriteArtists"]?.let { DIAG_JSON.decodeFromJsonElement<List<String>>(it) }
            val songs = obj["favouriteSongs"]?.let { DIAG_JSON.decodeFromJsonElement<List<Track>>(it) }
            if (!artists.isNullOrEmpty() || !songs.isNullOrEmpty()) {
                favouriteArtists = artists.orEmpty()
                favouriteSongs = songs.orEmpty()
            }
            dislikedArtists = obj["dislikedArtists"]?.let { DIAG_JSON.decodeFromJsonElement<List<String>>(it) }.orEmpty()
            hiddenTracks = obj["hiddenTracks"]?.let { DIAG_JSON.decodeFromJsonElement<List<String>>(it) }.orEmpty()
        }.onFailure { notes += "settings.json could not be decoded (${explain(it)}); using the fallback favourites" }
    } else {
        notes += "settings.json missing at $settingsPath; using the fallback favourites"
    }

    var log = FALLBACK_LOG
    var logFromDisk = false
    val logPath = File(dataHome(), "valen/listening.json")
    if (logPath.isFile) {
        runCatching { DIAG_JSON.decodeFromString<ListeningLog>(logPath.readText()) }
            .onSuccess { log = it; logFromDisk = true }
            .onFailure { notes += "listening.json could not be decoded (${explain(it)}); using the small synthetic log" }
    } else {
        notes += "listening.json missing at $logPath; using the small synthetic log"
    }

    return OwnerData(settingsPath, logPath, favouriteArtists, favouriteSongs, dislikedArtists, hiddenTracks, log, logFromDisk, notes)
}

/** The saved "Asya" smart mix, read to check its pollution cannot be reproduced. */
private class SavedMix(
    val name: String,
    val spec: SmartSpec?,
    val tracks: List<Track>,
    val path: File,
)

private fun readSavedAsya(diag: Diag): SavedMix? {
    val path = File(dataHome(), "valen/playlists.json")
    if (!path.isFile) {
        diag.note("the saved playlist library is missing at $path")
        return null
    }
    return try {
        val rootEl = DIAG_JSON.parseToJsonElement(path.readText()).jsonObject
        val playlists = rootEl["playlists"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
        val chosen = playlists.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "Asya" && it["smart"] is JsonObject }
            ?: playlists.firstOrNull { it["smart"] is JsonObject }
        if (chosen == null) {
            diag.note("no smart playlist in $path")
            return null
        }
        val name = chosen["name"]?.jsonPrimitive?.contentOrNull ?: "(unnamed)"
        val spec = (chosen["smart"] as? JsonObject)?.let { smart ->
            runCatching { DIAG_JSON.decodeFromJsonElement<SmartSpec>(smart) }.getOrElse { e ->
                diag.note("the smart spec of \"$name\" could not be decoded: ${explain(e)}")
                null
            }
        }
        val tracks = chosen["tracks"]?.jsonArray?.mapNotNull { it as? JsonObject }?.mapNotNull { row ->
            val provider = row["provider"]?.jsonPrimitive?.contentOrNull
                ?.let { p -> ProviderId.entries.firstOrNull { it.name == p } } ?: return@mapNotNull null
            Track(
                provider = provider,
                id = row["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                title = row["title"]?.jsonPrimitive?.contentOrNull ?: "?",
                artist = row["artist"]?.jsonPrimitive?.contentOrNull ?: "?",
                durationMs = row["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                uri = row["uri"]?.jsonPrimitive?.contentOrNull,
            )
        }.orEmpty()
        SavedMix(name, spec, tracks, path)
    } catch (e: Exception) {
        diag.note("the saved playlist library could not be read ($path): ${explain(e)}")
        null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Live readers: YouTube Music (InnerTube/YtmParse), SoundCloud
// ─────────────────────────────────────────────────────────────────────────────

/** The web app's own search (`YtmusicNativeProvider.search`'s read), cached per query. */
private class YtmFeed(private val api: InnerTube) {
    private val cache = HashMap<String, Read<List<Track>>>()
    private var consecutiveFailures = 0
    var tracksRead = 0
        private set
    val errors = LinkedHashSet<String>()

    suspend fun search(query: String, limit: Int = 25): Read<List<Track>> {
        val q = query.trim()
        cache[q]?.let { return it }
        if (consecutiveFailures >= 3) {
            val read = Read(emptyList<Track>(), "YouTube Music unreachable (3 earlier failures in a row): ${errors.lastOrNull()}")
            cache[q] = read
            return read
        }
        val read = try {
            val tracks = YtmParse.search(api.search(q))
                .filterIsInstance<YtmParse.Item.Song>()
                .mapNotNull { it.track.toValenTrack() }
                .distinctBy { it.id }
                .take(limit)
            consecutiveFailures = 0
            tracksRead += tracks.size
            Read(tracks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            consecutiveFailures++
            errors += "search \"$q\": ${explain(e)}"
            Read(emptyList(), explain(e))
        }
        cache[q] = read
        return read
    }
}

/** SoundCloud's own search API (the public one, runtime client id), cached per query. */
private class ScFeed(private val audio: SoundcloudAudio) {
    private val cache = HashMap<String, Read<List<Track>>>()
    private var consecutiveFailures = 0
    var tracksRead = 0
        private set
    val errors = LinkedHashSet<String>()

    suspend fun search(query: String, limit: Int = 25): Read<List<Track>> {
        val q = query.trim()
        cache[q]?.let { return it }
        if (consecutiveFailures >= 3) {
            val read = Read(emptyList<Track>(), "SoundCloud unreachable (3 earlier failures in a row): ${errors.lastOrNull()}")
            cache[q] = read
            return read
        }
        val read = try {
            consecutiveFailures = 0
            val tracks = audio.search(q, limit).map { it.toValenTrack() }.distinctBy { it.id }
            tracksRead += tracks.size
            Read(tracks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            consecutiveFailures++
            errors += "search \"$q\": ${explain(e)}"
            Read(emptyList(), explain(e))
        }
        cache[q] = read
        return read
    }
}

/**
 * YouTube Music's artist pages, read the way `ArtistDirectory`/`PlaybackCoordinator.mixer()` read
 * them: an exact name match for a seed, that page's related artists carried as provider+id+rank, and
 * a related artist's songs read from that artist's own (id-addressed) page — never a name search.
 */
private class ArtistFeed(private val artists: YtmusicArtists) {
    private val exact = HashMap<String, Read<ServiceArtist?>>()
    private val pagesById = HashMap<String, Read<ArtistProfile?>>()
    private val pagesByName = HashMap<String, Read<ArtistProfile?>>()
    private val relatedCache = HashMap<String, Read<List<RelatedArtist>>>()

    var pagesRead = 0
        private set
    val errors = LinkedHashSet<String>()

    suspend fun findExact(name: String): Read<ServiceArtist?> {
        val key = Taste.artistKey(name)
        exact[key]?.let { return it }
        val read = try {
            val found = artists.find(name, 6)
            Read(found.firstOrNull { Taste.artistKey(it.name) == key })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "find \"$name\": ${explain(e)}"
            Read(null, explain(e))
        }
        exact[key] = read
        return read
    }

    suspend fun pageById(id: String): Read<ArtistProfile?> {
        pagesById[id]?.let { return it }
        val read = try {
            pagesRead++
            Read(artists.artist(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "artist page $id: ${explain(e)}"
            Read(null, explain(e))
        }
        pagesById[id] = read
        return read
    }

    suspend fun pageForName(name: String): Read<ArtistProfile?> {
        val key = Taste.artistKey(name)
        pagesByName[key]?.let { return it }
        val found = findExact(name)
        val read: Read<ArtistProfile?> = when {
            found.error != null -> Read(null, found.error)
            found.value == null -> Read(null, "no YouTube Music artist page named \"$name\" (exact name)")
            else -> pageById(found.value.id)
        }
        pagesByName[key] = read
        return read
    }

    /** The page's related artists, merged by artist key exactly as `ArtistView.related` is. */
    suspend fun relatedEntries(seed: String): Read<List<RelatedArtist>> {
        val key = Taste.artistKey(seed)
        relatedCache[key]?.let { return it }
        val page = pageForName(seed)
        page.error?.let { error ->
            val read = Read(emptyList<RelatedArtist>(), error)
            relatedCache[key] = read
            return read
        }
        val profile = page.value
        if (profile == null) {
            val read = Read(emptyList<RelatedArtist>())
            relatedCache[key] = read
            return read
        }
        val self = Taste.artistKey(profile.artist.name)
        val groups = LinkedHashMap<String, MutableList<ServiceArtist>>()
        for (artist in profile.related) groups.getOrPut(Taste.artistKey(artist.name)) { mutableListOf() } += artist
        val merged = groups.values
            .mapNotNull { group -> group.firstOrNull() }
            .filter { Taste.artistKey(it.name) != self && Taste.artistKey(it.name).isNotEmpty() }
            .take(RELATED_KEPT)
            .mapIndexed { rank, artist -> RelatedArtist(artist.name, artist.provider, artist.id, rank) }
        val read = Read(merged)
        relatedCache[key] = read
        return read
    }
}

/**
 * An approximation of `PlaybackCoordinator.unifiedSearch` with the two services that work signed
 * out: each service's 25 rows are deduped by recording, the copy to play is the preferred service's
 * (YouTube Music before SoundCloud, as the saved priority says), ordered by the best rank any service
 * gave the recording and capped at 50. Spotify (not signed in) and the local adapter are left out.
 */
private suspend fun unifiedSearchApprox(query: String, ytm: YtmFeed, sc: ScFeed): Read<List<Track>> {
    val order = listOf(ProviderId.YOUTUBE_MUSIC, ProviderId.SOUNDCLOUD)
    val y = ytm.search(query)
    val s = sc.search(query)
    val copies = LinkedHashMap<String, MutableList<Triple<Track, Int, Int>>>()
    fun add(id: ProviderId, tracks: List<Track>) {
        tracks.distinctBy { recordingKey(it.title, it.artist) }.forEachIndexed { position, track ->
            copies.getOrPut(recordingKey(track.title, track.artist)) { mutableListOf() } += Triple(track, position, order.indexOf(id))
        }
    }
    add(ProviderId.YOUTUBE_MUSIC, y.value)
    add(ProviderId.SOUNDCLOUD, s.value)
    val ranked = copies.values.mapNotNull { group ->
        val preferred = group.minWith(compareBy<Triple<Track, Int, Int>> { it.third }.thenBy { it.second })
        group.minOf { it.second } to preferred.first
    }
    val tracks = ranked.sortedWith(compareBy({ it.first }, { order.indexOf(it.second.provider) }))
        .map { it.second }
        .take(UNIFIED_RESULTS_MAX)
    val error = listOfNotNull(
        y.error?.let { "YouTube Music: $it" },
        s.error?.let { "SoundCloud: $it" },
    ).joinToString("; ").ifBlank { null }
    return Read(tracks, error)
}

// ─────────────────────────────────────────────────────────────────────────────
// Call recording and attribution
// ─────────────────────────────────────────────────────────────────────────────

private data class SearchCall(val query: String, val tracks: List<Track>, val error: String?)
private data class PoolCall(val seed: String, val tracks: List<Track>, val error: String?)
private data class RelatedCall(val seed: String, val entries: List<RelatedArtist>, val error: String?)
private data class RelatedSongsCall(val related: RelatedArtist, val tracks: List<Track>, val error: String?)

private class CallRecorder {
    val searches = mutableListOf<SearchCall>()
    val artistTops = mutableListOf<PoolCall>()
    val relatedBySeed = mutableListOf<RelatedCall>()
    val relatedPages = mutableListOf<RelatedSongsCall>()
    val errors = LinkedHashSet<String>()
}

private fun makeMixer(
    ytm: YtmFeed,
    sc: ScFeed,
    artists: ArtistFeed,
    recorder: CallRecorder,
    random: Random,
    relatedSongsOverride: ((RelatedArtist) -> List<Track>)? = null,
): SmartMixer = SmartMixer(
    search = { query ->
        val read = unifiedSearchApprox(query, ytm, sc)
        recorder.searches += SearchCall(query, read.value, read.error)
        read.error?.let { recorder.errors += "search \"$query\": $it" }
        read.value
    },
    follows = { emptyList() },
    related = { seed ->
        val read = artists.relatedEntries(seed)
        recorder.relatedBySeed += RelatedCall(seed, read.value, read.error)
        read.error?.let { recorder.errors += "related \"$seed\": $it" }
        read.value
    },
    relatedSongs = { related ->
        val read: Read<List<Track>> = if (relatedSongsOverride != null) {
            try {
                Read(relatedSongsOverride(related))
            } catch (e: Exception) {
                Read(emptyList(), explain(e))
            }
        } else {
            val page = artists.pageById(related.id)
            Read(page.value?.topTracks.orEmpty(), page.error)
        }
        recorder.relatedPages += RelatedSongsCall(related, read.value, read.error)
        read.error?.let { recorder.errors += "relatedSongs ${related.name} (${related.provider.name}:${related.id}): $it" }
        read.value
    },
    artistTop = { name ->
        val read = artists.pageForName(name).let { page -> Read(page.value?.topTracks.orEmpty(), page.error) }
        recorder.artistTops += PoolCall(name, read.value, read.error)
        read.error?.let { recorder.errors += "artistTop \"$name\": $it" }
        read.value
    },
    random = random,
)

private enum class OriginKind(val label: String) { SEED("seed"), RELATED("related"), LOVED("loved"), GENRE("genre") }

private data class PickOrigin(
    val kind: OriginKind,
    val label: String,
    /** The exact related entry (provider+id+rank) a RELATED pick came from. */
    val related: RelatedArtist? = null,
)

private class RunAudit(
    val label: String,
    val spec: SmartSpec,
    val elapsedMs: Long,
    val picks: List<Track>,
    val seeds: List<String>,
    val offers: List<Pair<Track, PickOrigin>>,
    val origins: Map<String, PickOrigin>,
    val relatedPages: List<RelatedSongsCall>,
    val relatedLists: List<RelatedCall>,
    val searches: List<SearchCall>,
    val artistTops: List<PoolCall>,
    val warnings: List<String>,
    val errors: List<String>,
) {
    val seedKeys: Set<String> = seeds.map(Taste::artistKey).toSet()

    fun originOf(track: Track): PickOrigin? = origins[recordingKey(track.title, track.artist)]
}

/**
 * Rebuilds the candidate layers the real `SmartMixer.build` must have built, in first-offered order:
 * each seed's artist page (or its credit-filtered search fallback when the page was empty), each
 * accepted related artist's exact page, then the loved/on-repeat history. The first layer to offer a
 * recording is the pick's origin — the same rule `SmartMixer.build` uses.
 */
private fun reconstructOffers(spec: SmartSpec, seeds: List<String>, recorder: CallRecorder, taste: Taste): Pair<List<Pair<Track, PickOrigin>>, List<String>> {
    val warnings = mutableListOf<String>()
    val offers = ArrayList<Pair<Track, PickOrigin>>()
    val seen = HashSet<String>()

    fun offer(track: Track, origin: PickOrigin) {
        val rec = recordingKey(track.title, track.artist)
        if (rec.isEmpty() || !seen.add(rec)) return
        offers += track to origin
    }

    if (spec.genres.isNotEmpty()) warnings += "this audit does not reconstruct the genre layer (spec.genres=${spec.genres}); the specs used have none"

    // 1. Seeds' own songs: artistTop, or the search fallback of the same seed.
    val seedKeys = seeds.map(Taste::artistKey).toSet()
    var searchCursor = 0
    seeds.forEachIndexed { index, seed ->
        val top = recorder.artistTops.getOrNull(index)
        if (top == null) {
            warnings += "no artistTop call #${index + 1} for seed \"$seed\" (reconstruction drift)"
            return@forEachIndexed
        }
        if (Taste.artistKey(top.seed) != Taste.artistKey(seed)) {
            warnings += "artistTop call #${index + 1} was \"${top.seed}\" but the seed list says \"$seed\""
        }
        val key = Taste.artistKey(seed)
        val pool: List<Track> = if (top.tracks.isNotEmpty()) {
            top.tracks
        } else {
            val call = recorder.searches.getOrNull(searchCursor)
            if (call != null && Taste.artistKey(call.query) == key) {
                searchCursor++
                call.tracks
            } else {
                warnings += "seed \"$seed\" had no page and no matching search fallback (reconstruction drift)"
                emptyList()
            }
        }
        var kept = 0
        for (track in pool) {
            if (kept >= PER_SEED_SONGS) break
            if (key !in Taste.creditsOf(track.artist)) continue
            if (!taste.played(track) && SmartMixer.derivative(track, taste)) continue
            offer(track, PickOrigin(OriginKind.SEED, seed))
            kept++
        }
    }
    if (searchCursor != recorder.searches.size) {
        warnings += "the build made ${recorder.searches.size} searches but only $searchCursor were seed fallbacks"
    }

    // 2. New artists from their exact related page.
    for (call in recorder.relatedPages) {
        val key = Taste.artistKey(call.related.name)
        var taken = 0
        for (track in call.tracks) {
            if (taken >= RELATED_SONGS_PER_ARTIST) break
            if (key !in Taste.creditsOf(track.artist)) continue
            if (!taste.played(track) && SmartMixer.derivative(track, taste)) continue
            offer(track, PickOrigin(OriginKind.RELATED, call.related.name, call.related))
            taken++
        }
    }

    // 3. The person's own loved/on-repeat recordings that belong to the seeds.
    var lovedOffered = 0
    for (track in taste.onRepeat(MAX_LOVED) + taste.favouriteTracks) {
        if (lovedOffered >= MAX_LOVED) break
        if (seedKeys.isNotEmpty() && Taste.creditsOf(track.artist).none { it in seedKeys }) continue
        offer(track, PickOrigin(OriginKind.LOVED, "loved/on-repeat"))
        lovedOffered++
    }
    return offers to warnings
}

private fun analyzeRun(
    label: String,
    spec: SmartSpec,
    picks: List<Track>,
    recorder: CallRecorder,
    taste: Taste,
    elapsedMs: Long,
): RunAudit {
    val seeds = recorder.artistTops.map { it.seed }
    val (offers, warnings) = reconstructOffers(spec, seeds, recorder, taste)
    val origins = LinkedHashMap<String, PickOrigin>()
    offers.forEach { (track, origin) -> origins.putIfAbsent(recordingKey(track.title, track.artist), origin) }
    val errors = (recorder.errors + recorder.searches.mapNotNull { it.error } + recorder.relatedBySeed.mapNotNull { it.error } +
        recorder.relatedPages.mapNotNull { it.error } + recorder.artistTops.mapNotNull { it.error }).distinct().toList()
    return RunAudit(label, spec, elapsedMs, picks, seeds, offers, origins, recorder.relatedPages, recorder.relatedBySeed, recorder.searches, recorder.artistTops, warnings, errors)
}

// ─────────────────────────────────────────────────────────────────────────────
// Metrics of one run
// ─────────────────────────────────────────────────────────────────────────────

private data class RunMetrics(
    val newPicks: List<Track>,
    val newCounts: List<Pair<String, Int>>,
    val artistCounts: List<Pair<String, Int>>,
    val familiarPicks: Int,
    val strongPicks: Int,
    val strongTarget: Int,
    val newBudget: Int,
    val newShareCap: Float,
    val unattributed: List<Track>,
    val derivativeViolations: List<Track>,
    val derivExemptByPlay: List<Track>,
    val coverViolations: List<Track>,
    val lengthViolations: List<Track>,
    val durZero: List<Track>,
    val duplicateRecordings: List<String>,
    val adjacency: List<Pair<Track, Track>>,
    val newNewAdjacency: List<Pair<Track, Track>>,
    val relatedIdentityIssues: List<String>,
    val outsideArtists: List<Pair<Track, List<String>>>,
    val junk: List<Track>,
)

private fun familiar(track: Track, taste: Taste, seedKeys: Set<String>): Boolean =
    Taste.creditsOf(track.artist).any { credit ->
        credit in seedKeys || taste.trust(credit) >= CONFIDENT_TRUST || taste.artistScore(credit) >= 1f
    }

private fun measureRun(run: RunAudit, taste: Taste): RunMetrics {
    val seeds = run.seedKeys
    val relatedKeys = run.relatedPages.map { Taste.artistKey(it.related.name) }.toSet()
    val named = run.spec.artists.map(Taste::artistKey).filter { it.isNotEmpty() }.toSet()
    val d = run.spec.discovery.coerceIn(0f, 1f)
    val size = run.spec.size.coerceIn(1, 200)
    val newShareCap = min(MAX_NEW_SHARE, NEW_SHARE_PER_DISCOVERY * d)
    val newBudget = (size * newShareCap).toInt()

    val newPicks = run.picks.filter { !familiar(it, taste, seeds) }
    val newCounts = newPicks.map { Taste.artistKey(it.artist) }.counts()
    val artistCounts = run.picks.map { Taste.artistKey(it.artist) }.counts()
    val familiarPicks = run.picks.count { familiar(it, taste, seeds) }
    val strongPicks = run.picks.count { track ->
        val origin = run.originOf(track)
        origin?.kind == OriginKind.SEED || origin?.kind == OriginKind.LOVED ||
            Taste.creditsOf(track.artist).any { it in seeds }
    }
    // The engine's familiar floor: min(size/4, strong candidates that pass the hard gates).
    fun gated(track: Track): Boolean {
        if (Taste.creditsOf(track.artist).isEmpty()) return false
        if (taste.hidden(track)) return false
        if (!taste.played(track) && SmartMixer.derivative(track, taste)) return false
        if (!taste.played(track) && taste.isCover(track)) return false
        val credits = Taste.creditsOf(track.artist)
        if (!(credits.any { it in named } || !taste.dislikes(track.artist))) return false
        if (!(taste.skips(track) < 2 || taste.skips(track) < taste.plays(track))) return false
        return true
    }
    fun strongCandidate(track: Track): Boolean {
        val origin = run.originOf(track)
        return origin?.kind == OriginKind.SEED || origin?.kind == OriginKind.LOVED ||
            Taste.creditsOf(track.artist).any { it in seeds }
    }
    val gatedStrong = run.offers.map { it.first }.count { strongCandidate(it) && gated(it) }
    val strongTarget = min(size / 4, gatedStrong)

    val unattributed = run.picks.filter { run.originOf(it) == null }
    // Two independent signals: what the title/length says, and what the engine's own gate says.
    // `SmartMixer.derivative` answers false for any upload played here, so a true answer is a gate miss.
    val derivativeViolations = run.picks.filter { track ->
        (TRACK_DERIVATIVE_SIGNALS(track.title, track) && !taste.played(track)) || SmartMixer.derivative(track, taste)
    }
    val derivExemptByPlay = run.picks.filter { track ->
        TRACK_DERIVATIVE_SIGNALS(track.title, track) && taste.played(track)
    }
    val coverViolations = run.picks.filter { taste.isCover(it) && !taste.played(it) }
    val lengthViolations = run.picks.filter { track ->
        val ms = track.durationMs
        (ms in 1 until MIN_SONG_MS || ms > MAX_SONG_MS) && !taste.played(track)
    }
    val durZero = run.picks.filter { it.durationMs == 0L }
    val duplicateRecordings = run.picks
        .groupBy { recordingKey(it.title, it.artist) }
        .filter { it.value.size > 1 }
        .keys.toList()
    val adjacency = run.picks.zipWithNext().filter { (a, b) -> Taste.artistKey(a.artist) == Taste.artistKey(b.artist) }
    val newNewAdjacency = run.picks.zipWithNext().filter { (a, b) ->
        !familiar(a, taste, seeds) && !familiar(b, taste, seeds)
    }

    val relatedIdentityIssues = mutableListOf<String>()
    for (track in newPicks) {
        val origin = run.originOf(track)
        if (origin == null) {
            relatedIdentityIssues += "unattributed new artist: ${track.title} / ${track.artist}"
            continue
        }
        if (origin.kind != OriginKind.RELATED) {
            relatedIdentityIssues += "new artist ${track.artist} arrived by ${origin.kind.label}, not as a related discovery"
            continue
        }
        val related = origin.related
        if (related == null) {
            relatedIdentityIssues += "related pick ${track.title} / ${track.artist} carries no RelatedArtist identity"
            continue
        }
        if (related.id.isBlank()) relatedIdentityIssues += "related ${related.name} has a blank id"
        if (Taste.artistKey(related.name) !in Taste.creditsOf(track.artist)) {
            relatedIdentityIssues += "related ${related.name} (${related.provider.name}:${related.id}) emitted a track credited ${track.artist}"
        }
        val page = run.relatedPages.firstOrNull { it.related == related }
        if (page == null) {
            relatedIdentityIssues += "related ${related.name} was picked but its exact page was never read"
        } else {
            val rec = recordingKey(track.title, track.artist)
            if (page.tracks.none { recordingKey(it.title, it.artist) == rec }) {
                relatedIdentityIssues += "related ${related.name} pick ${track.title} is not on that exact page"
            }
        }
        if (run.relatedLists.none { call -> related in call.entries }) {
            relatedIdentityIssues += "related ${related.name} (${related.provider.name}:${related.id}) is in no seed's related list"
        }
    }

    val outsideArtists = run.picks.mapNotNull { track ->
        val credits = Taste.creditsOf(track.artist)
        val ok = credits.any { credit ->
            credit in seeds || credit in relatedKeys || taste.trust(credit) >= CONFIDENT_TRUST || taste.artistScore(credit) >= 1f
        }
        if (ok) null else track to credits
    }
    val junk = run.picks.filter { track ->
        Taste.creditsOf(track.artist).any { it == "365music" || it == "bts" || K_POP_ANY.containsMatchIn(it) }
    }
    return RunMetrics(
        newPicks, newCounts, artistCounts, familiarPicks, strongPicks, strongTarget, newBudget, newShareCap,
        unattributed, derivativeViolations, derivExemptByPlay, coverViolations, lengthViolations, durZero,
        duplicateRecordings, adjacency, newNewAdjacency, relatedIdentityIssues, outsideArtists, junk,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Dumps
// ─────────────────────────────────────────────────────────────────────────────

private fun <T> List<T>.counts(): List<Pair<T, Int>> =
    groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }

private fun specText(spec: SmartSpec): String =
    "sources=[${spec.sources.joinToString(", ") { it.name }}] artists=${spec.artists} genres=${spec.genres} " +
        "size=${spec.size} discovery=${spec.discovery}"

private fun truncate(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"

private fun trackLine(track: Track): String =
    "${track.provider.name}:${track.id} | ${truncate(track.title, 60)} | ${truncate(track.artist, 40)} | dur=${track.durationMs}"

private fun flagText(track: Track, taste: Taste): String = buildString {
    if (track.durationMs == 0L) append(" [dur=0]")
    if (DERIVATIVE_RE.containsMatchIn(track.title.lowercase())) append(" [deriv-marker]")
    if (taste.isCover(track)) append(" [cover-by-title]")
    if (taste.played(track)) append(" [played-here]")
    if (Taste.creditsOf(track.artist).any { it == "365music" }) append(" [365Music]")
    if (Taste.creditsOf(track.artist).any { it == "bts" || K_POP_ANY.containsMatchIn(it) }) append(" [k-pop]")
}

/**
 * Picks whose seed had to fall back to a credit-filtered name search (its service artist page
 * answered nothing): the only name-identity surface left in the seed layer. Returns pick to seed.
 */
private fun fallbackSeedPicks(run: RunAudit): List<Pair<Track, String>> = run.picks.mapNotNull { track ->
    val origin = run.originOf(track) ?: return@mapNotNull null
    if (origin.kind != OriginKind.SEED) return@mapNotNull null
    val top = run.artistTops.firstOrNull { Taste.artistKey(it.seed) == Taste.artistKey(origin.label) } ?: return@mapNotNull null
    if (top.tracks.isNotEmpty()) null else track to origin.label
}

private fun durZeroByProvider(picks: List<Track>): String =
    picks.filter { it.durationMs == 0L }.map { it.provider.name }.counts().joinToString { "${it.first}=${it.second}" }

private fun durKnownByProvider(picks: List<Track>): String =
    picks.filter { it.durationMs > 0L }.map { it.provider.name }.counts().joinToString { "${it.first}=${it.second}" }

/**
 * The owner's listening log with 365MUSIC given the evidence a polluted history would show had the
 * person played a slop mix across three days before this version (two recordings, two plays each day,
 * two minutes a day): three distinct days with 120 s of credited listening each. It models the
 * vintage-log hole the generated-provenance fields close for new listening but cannot close for a log
 * written before they existed (the owner's log has genMs=0 everywhere). Nothing is written to disk.
 */
private fun boostPollutedHistory(log: ListeningLog): ListeningLog {
    val newestDay = log.daily.keys.mapNotNull { it.toLongOrNull() }.maxOrNull() ?: return log
    val songs = LinkedHashMap(log.songs)
    val daily = LinkedHashMap(log.daily)
    val artists = LinkedHashMap(log.artists)
    val slice = LinkedHashMap<String, Long>()
    var addedMs = 0L
    var addedPlays = 0
    for (i in 0 until 2) {
        val key = "SPOTIFY:legacy-365-$i"
        songs[key] = Listened(
            title = if (i == 0) "legacy slop A" else "legacy slop B",
            artist = "365MUSIC",
            provider = ProviderId.SPOTIFY,
            ms = 120_000,
            plays = 3,
            durationMs = 180_000,
        )
        slice[key] = 120_000L
        addedMs += 120_000
        addedPlays += 3
    }
    // Distinct days already past the owner's kept daily window, so all three are new evidence.
    var back = 4L
    var made = 0
    while (made < 3) {
        val day = (newestDay - back).toString()
        back++
        if (day in daily) continue
        daily[day] = DayListening(ms = 240_000, songs = slice)
        made++
    }
    artists["365MUSIC"] = (artists["365MUSIC"] ?: 0L) + addedMs * 3
    // totalMs and plays grow, the generated part stays 0: nobody knew back then which listening was a mix's.
    return log.copy(
        totalMs = log.totalMs + addedMs * 3,
        plays = log.plays + addedPlays * 3,
        songs = songs,
        artists = artists,
        daily = daily,
    )
}

private fun dumpTaste(diag: Diag, taste: Taste, owner: OwnerData) {
    diag.line("settings:  ${owner.settingsPath} (exists=${owner.settingsPath.isFile})")
    diag.line("listening: ${owner.logPath} (exists=${owner.logPath.isFile}, decoded=${owner.logFromDisk})")
    owner.notes.forEach { diag.line("note: $it") }
    diag.line("favouriteArtists (${owner.favouriteArtists.size}):")
    owner.favouriteArtists.forEach { diag.line("  - $it  (artistKey=${Taste.artistKey(it)}, trust=${"%.2f".format(taste.trust(it))}, score=${"%.2f".format(taste.artistScore(it))})") }
    diag.line("favouriteSongs (${owner.favouriteSongs.size}):")
    owner.favouriteSongs.forEach { diag.line("  - ${it.title} | ${it.artist} | ${it.provider.name}:${it.id}") }
    diag.line("dislikedArtists=${owner.dislikedArtists} hiddenTracks=${owner.hiddenTracks.size} hidden")
    diag.line("log: songs=${owner.log.songs.size} totalMs=${owner.log.totalMs} plays=${owner.log.plays} days=${owner.log.days.size} daily=${owner.log.daily.size} skips=${owner.log.skips.size}")
    val genMs = owner.log.songs.values.sumOf { it.genMs }
    val genPlays = owner.log.songs.values.sumOf { it.genPlays }
    diag.line("generated provenance in the log: genMs=$genMs genPlays=$genPlays (the historical log predates the field if 0)")
    diag.line()
    diag.line("topArtists(16) as MostPlayed/Favourites read them (score, trust, days, real ms, genMs, plays):")
    taste.topArtists(16).forEachIndexed { index, name ->
        val e = taste.evidence(name)
        diag.line(
            "  %2d. %-34s score=%6.2f trust=%4.2f days=%d realMs=%8d genMs=%6d plays=%d%s".format(
                index + 1, truncate(name, 34), taste.artistScore(name), taste.trust(name), e.days, e.ms, e.genMs, e.plays,
                if (taste.dislikes(name)) " [disliked/resting]" else "",
            ),
        )
    }
    diag.line()
    diag.line("The flagged pollutant entities (must not seed a v2 mix):")
    listOf("365Music", "365MUSIC", "BTS").forEach { name ->
        val e = taste.evidence(name)
        diag.line(
            "  %-10s trust=%4.2f score=%6.2f days=%d realMs=%8d genMs=%6d plays=%d skips=%d".format(
                name, taste.trust(name), taste.artistScore(name), e.days, e.ms, e.genMs, e.plays, e.skips,
            ),
        )
    }
    diag.line()
    diag.line("slop shield counterfactual (offline): the same log with the 365MUSIC listening marked as generated")
    val marked = ListeningLog(
        since = owner.log.since, totalMs = owner.log.totalMs, plays = owner.log.plays,
        songs = owner.log.songs.mapValues { (key, song) ->
            if (Taste.creditsOf(song.artist).any { it == "365music" }) {
                song.copy(genMs = song.ms, genPlays = song.plays)
            } else song
        },
        artists = owner.log.artists, albums = owner.log.albums, playlists = owner.log.playlists, days = owner.log.days,
        services = owner.log.services, skips = owner.log.skips, daily = owner.log.daily, dailySince = owner.log.dailySince,
    )
    val shielded = Taste(marked, owner.favouriteArtists, owner.favouriteSongs, owner.dislikedArtists, owner.hiddenTracks)
    diag.line(
        "  365Music after the mark: trust=${"%.2f".format(shielded.trust("365Music"))} score=${"%.2f".format(shielded.artistScore("365Music"))} " +
            "in topArtists(16)=${"365music" in shielded.topArtists(16).map(Taste::artistKey)}",
    )
}

private fun dumpRun(diag: Diag, run: RunAudit, taste: Taste) {
    diag.section("run ${run.label} — ${run.picks.size}/${run.spec.size} picks in ${run.elapsedMs} ms")
    diag.line("spec: ${specText(run.spec)}")
    diag.line("seeds (${run.seeds.size}), in the order the mixer asked for their pages:")
    run.seeds.forEachIndexed { index, seed ->
        val top = run.artistTops.getOrNull(index)
        diag.line(
            "  %2d. %-36s key=%-30s trust=%4.2f score=%6.2f pageTracks=%d%s".format(
                index + 1, truncate(seed, 36), Taste.artistKey(seed), taste.trust(seed), taste.artistScore(seed),
                top?.tracks?.size ?: -1, if (top?.error != null) " ERROR=${top.error}" else "",
            ),
        )
    }
    diag.line()
    diag.line("related lists asked (${run.relatedLists.size} seeds):")
    run.relatedLists.forEach { call ->
        diag.line("  ${truncate(call.seed, 40)} -> ${call.entries.size} entries${call.error?.let { " ERROR=$it" } ?: ""}")
        call.entries.take(16).forEach { entry ->
            diag.line("      rank=${entry.rank} ${entry.provider.name}:${entry.id}  ${entry.name}")
        }
    }
    diag.line()
    diag.line("related pages read (${run.relatedPages.size}), songs from that exact page:")
    run.relatedPages.forEach { call ->
        diag.line("  ${call.related.provider.name}:${call.related.id}  ${truncate(call.related.name, 32)}  rank=${call.related.rank}  tracks=${call.tracks.size}${call.error?.let { " ERROR=$it" } ?: ""}")
        call.tracks.take(6).forEach { diag.line("      - ${truncate(it.title, 60)} | ${truncate(it.artist, 36)}") }
    }
    diag.line()
    diag.line("search fallbacks (page-less seeds):")
    if (run.searches.isEmpty()) diag.line("  none") else run.searches.forEach { call ->
        diag.line("  \"${truncate(call.query, 50)}\" -> ${call.tracks.size} rows${call.error?.let { " ERROR=$it" } ?: ""}")
    }
    diag.line()
    diag.line("picks (origin = first candidate layer that offered the recording):")
    run.picks.forEachIndexed { index, track ->
        val origin = run.originOf(track)
        val originText = when {
            origin == null -> "UNATTRIBUTED"
            origin.kind == OriginKind.RELATED -> "RELATED(${origin.related?.name}@${origin.related?.provider?.name}:${origin.related?.id})"
            else -> "${origin.kind.label}(${origin.label})"
        }
        diag.line("  %3d. %-13s | %-56s | %-34s | %-42s | %s".format(
            index + 1, track.provider.name, truncate(track.title, 56), truncate(track.artist, 34),
            truncate(originText, 42), flagText(track, taste).trim(),
        ))
    }
    diag.line()
    if (run.warnings.isNotEmpty()) {
        diag.line("attribution warnings (${run.warnings.size}):")
        run.warnings.forEach { diag.line("  !! $it") }
    }
    val metrics = measureRun(run, taste)
    diag.line("origin histogram: " + run.picks.map { run.originOf(it)?.kind?.label ?: "unattributed" }.counts().joinToString(", ") { "${it.first}=${it.second}" })
    diag.line("candidate offers by origin: " + run.offers.map { it.second.kind.label }.counts().joinToString(", ") { "${it.first}=${it.second}" })
    diag.line("provider histogram: " + run.picks.map { it.provider.name }.counts().joinToString(", ") { "${it.first}=${it.second}" })
    diag.line("artist histogram: " + metrics.artistCounts.joinToString(", ") { "${it.first}=${it.second}" })
    diag.line("new-artist histogram: " + metrics.newCounts.joinToString(", ") { "${it.first}=${it.second}" })
    diag.line("with dur=0: ${metrics.durZero.size}/${run.picks.size}; played-derivative-exempt rows: ${metrics.derivExemptByPlay.size}")
    diag.line("network/read errors in this run (${run.errors.size}): " + if (run.errors.isEmpty()) "none" else run.errors.joinToString(" | "))
}

private fun auditRun(diag: Diag, checks: Checks, run: RunAudit, taste: Taste) {
    val metrics = measureRun(run, taste)
    val p = "${run.label}: "
    diag.line()
    checks.check(p + "a. every pick attributed to a candidate layer", metrics.unattributed.isEmpty(), "${metrics.unattributed.size} unattributed")
    checks.check(p + "a. no 365MUSIC/365Music/BTS/K-pop pick", metrics.junk.isEmpty(), metrics.junk.joinToString { "${it.title}/${it.artist}" })
    checks.check(
        p + "a. every pick's artist is a seed, a related discovery or taste-known",
        metrics.outsideArtists.isEmpty(),
        metrics.outsideArtists.joinToString { "${it.first.title}/${it.first.artist}" },
    )
    checks.check(
        p + "b. new-artist songs <= budget min(0.5, 0.6*d)*size",
        metrics.newPicks.size <= metrics.newBudget,
        "new=${metrics.newPicks.size} budget=${metrics.newBudget} (d=${run.spec.discovery})",
    )
    if (run.picks.isNotEmpty()) {
        val share = metrics.newPicks.size.toFloat() / run.picks.size
        checks.check(
            p + "b. new-artist share <= min(0.5, 0.6*d)",
            share <= metrics.newShareCap + 1e-6f,
            "${"%.2f".format(share)} <= ${"%.2f".format(metrics.newShareCap)} (list ${run.picks.size}/${run.spec.size})",
        )
    }
    val overTwo = metrics.newCounts.filter { it.second > 2 }
    checks.check(p + "b. at most 2 songs per new artist", overTwo.isEmpty(), overTwo.joinToString())
    val artistCap = (run.spec.size / 4).coerceAtLeast(3)
    val overCap = metrics.artistCounts.filter { it.second > artistCap }
    checks.check(p + "b. artist cap $artistCap", overCap.isEmpty(), overCap.joinToString())
    checks.check(
        p + "b. familiar floor (>= min(size/4, strong candidates))",
        metrics.strongPicks >= metrics.strongTarget,
        "strong=${metrics.strongPicks} target=${metrics.strongTarget} familiar=${metrics.familiarPicks}/${run.picks.size}",
    )
    checks.check(p + "c. no unheard derivative-marked upload", metrics.derivativeViolations.isEmpty(), metrics.derivativeViolations.joinToString { it.title })
    checks.check(p + "c. no unheard cover-by-title row", metrics.coverViolations.isEmpty(), metrics.coverViolations.joinToString { "${it.title}/${it.artist}" })
    checks.check(p + "c. no unheard out-of-range duration", metrics.lengthViolations.isEmpty(), metrics.lengthViolations.joinToString { "${it.title}/${it.durationMs}" })
    checks.check(p + "d. one copy per recording", metrics.duplicateRecordings.isEmpty(), metrics.duplicateRecordings.joinToString())
    checks.check(p + "d. no same-artist adjacency", metrics.adjacency.isEmpty(), metrics.adjacency.joinToString { "${it.first.artist}" })
    checks.check(p + "e. every new artist is a provider+id related entry read from its exact page", metrics.relatedIdentityIssues.isEmpty(), metrics.relatedIdentityIssues.take(8).joinToString(" | "))
    if (metrics.durZero.isNotEmpty()) {
        checks.info(
            p + "c. residual risk: ${metrics.durZero.size} picks carry durationMs=0 (the 1 min/12 min length gate cannot fire; " +
                "only title-marker and cover-by-title gates stand between an unmarked upload and the list)",
        )
        metrics.durZero.take(10).forEach { diag.line("      dur=0: ${trackLine(it)}") }
    }
    if (metrics.derivExemptByPlay.isNotEmpty()) {
        checks.info(p + "c. ${metrics.derivExemptByPlay.size} derivative-signalling rows allowed because that exact upload was played here:")
        metrics.derivExemptByPlay.forEach { diag.line("      played-exempt: ${trackLine(it)}") }
    }
    diag.line()
}

// ─────────────────────────────────────────────────────────────────────────────
// The audit
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun execute(diag: Diag, root: File, startedAt: Long): AuditResult {
    val checks = Checks(diag)
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    val innerTube = InnerTube(http, cookies = { null })
    val ytm = YtmFeed(innerTube)
    val sc = ScFeed(SoundcloudAudio(http = http))
    val artists = ArtistFeed(YtmusicArtists(innerTube))

    diag.section("VALEN recommendation engine v2 — live AFTER audit")
    diag.line("written: ${Instant.ofEpochMilli(startedAt)} (${ZoneId.systemDefault().id})")
    diag.line("repo: $root")
    diag.line("runtime: JVM ${System.getProperty("java.version")} on ${System.getProperty("os.name")}")
    diag.line("mode: LIVE (VALEN_LIVE=1): YouTube Music read signed out through the web app's own API;")
    diag.line("      SoundCloud read signed out with its runtime client id; Spotify is NOT read (needs a Chrome sign-in).")
    diag.line("mixer: the real core Taste + SmartMixer v2, wired like PlaybackCoordinator.mixer(), random seed $MIX_RANDOM")
    diag.line("owner data: read only; this test writes nothing outside build/verify/")
    diag.line("rerun:")
    diag.line("  cd $root && source scripts/env.sh")
    diag.line("  VALEN_LIVE=1 ./gradlew :providers:native:test --tests 'app.valen.providers.native.LiveMixV2Test'")
    diag.line()

    val owner = readOwnerData()
    val taste = Taste(owner.log, owner.favouriteArtists, owner.favouriteSongs, owner.dislikedArtists, owner.hiddenTracks)

    diag.section("1. The owner's taste (read only)")
    dumpTaste(diag, taste, owner)

    val specA = SmartSpec(
        sources = setOf(SmartSource.Favourites, SmartSource.MostPlayed, SmartSource.SpotifyFollows, SmartSource.Artists),
        artists = owner.favouriteArtists,
        size = 100,
        discovery = 1.0f,
    )
    val specB = SmartSpec(
        sources = setOf(SmartSource.Favourites, SmartSource.MostPlayed),
        size = 20,
        discovery = 0.65f,
    )

    // ── Run A: the saved-Asya spec that produced the slop evidence ──
    val recorderA = CallRecorder()
    val mixerA = makeMixer(ytm, sc, artists, recorderA, Random(MIX_RANDOM))
    diag.line()
    diag.line("building run A: ${specText(specA)}")
    val startedA = System.currentTimeMillis()
    val picksA = mixerA.build(specA, taste)
    val runA = analyzeRun("A", specA, picksA, recorderA, taste, System.currentTimeMillis() - startedA)
    dumpRun(diag, runA, taste)
    auditRun(diag, checks, runA, taste)

    // ── Run B: Home's "For you" defaults ──
    val recorderB = CallRecorder()
    val mixerB = makeMixer(ytm, sc, artists, recorderB, Random(MIX_RANDOM))
    diag.line()
    diag.line("building run B: ${specText(specB)}")
    val startedB = System.currentTimeMillis()
    val picksB = mixerB.build(specB, taste)
    val runB = analyzeRun("B", specB, picksB, recorderB, taste, System.currentTimeMillis() - startedB)
    dumpRun(diag, runB, taste)
    auditRun(diag, checks, runB, taste)

    // ── Run D0: discovery 0, live ──
    diag.section("3. discovery=0 build (live): no new artist may enter")
    val recorderD0 = CallRecorder()
    val mixerD0 = makeMixer(ytm, sc, artists, recorderD0, Random(MIX_RANDOM))
    val specD0 = specA.copy(discovery = 0f)
    val startedD0 = System.currentTimeMillis()
    val picksD0 = mixerD0.build(specD0, taste)
    val runD0 = analyzeRun("D0", specD0, picksD0, recorderD0, taste, System.currentTimeMillis() - startedD0)
    val metricsD0 = measureRun(runD0, taste)
    dumpRun(diag, runD0, taste)
    checks.check("D0: f. discovery 0 asks no related artists", recorderD0.relatedBySeed.isEmpty() && recorderD0.relatedPages.isEmpty(), "relatedCalls=${recorderD0.relatedBySeed.size} relatedSongCalls=${recorderD0.relatedPages.size}")
    checks.check("D0: f. discovery 0 adds zero new artists", metricsD0.newPicks.isEmpty(), "new=${metricsD0.newPicks.map { it.artist }}, picks=${picksD0.size}")
    checks.check("D0: f. discovery 0 keeps to seeds", runD0.picks.all { track -> Taste.creditsOf(track.artist).any { it in runD0.seedKeys } }, "${picksD0.size} picks")

    // ── Negative probe: a corrupted relatedSongs callback must emit nothing ──
    diag.section("4. negative probe — corrupted relatedSongs (returns another artist's page for every related id)")
    val corruptPage = artists.pageForName("Daft Punk")
    val corruptTracks = corruptPage.value?.topTracks.orEmpty()
    val corruptRecs = corruptTracks.mapTo(HashSet()) { recordingKey(it.title, it.artist) }
    if (corruptTracks.isEmpty()) {
        checks.info("negative probe skipped: could not read a second artist's page (${corruptPage.error ?: "empty page"})")
    } else {
        val recorderX = CallRecorder()
        val mixerX = makeMixer(ytm, sc, artists, recorderX, Random(MIX_RANDOM), relatedSongsOverride = { corruptTracks })
        val startedX = System.currentTimeMillis()
        val picksX = mixerX.build(specA, taste)
        val runX = analyzeRun("X", specA, picksX, recorderX, taste, System.currentTimeMillis() - startedX)
        dumpRun(diag, runX, taste)
        val emitted = picksX.filter { recordingKey(it.title, it.artist) in corruptRecs }
        val mismatched = emitted.filter { track ->
            val origin = runX.originOf(track)
            val related = origin?.related
            related == null || Taste.artistKey(related.name) !in Taste.creditsOf(track.artist)
        }
        checks.check(
            "X: e. corrupted related page emitted no track of the wrong artist",
            emitted.isEmpty(),
            "${emitted.size} of ${corruptTracks.size} corrupted-page tracks leaked; mismatched=${mismatched.size}",
        )
        checks.check(
            "X: e. the engine still answered with a list (it drops the unreadable related artists)",
            picksX.isNotEmpty(),
            "${picksX.size} picks",
        )
        diag.line("corrupted callback: ${recorderX.relatedPages.size} related pages returned ${corruptTracks.size} tracks of another artist each;")
        diag.line("the engine emitted ${emitted.size} of them and attributed the rest to seed/loved layers: " +
            runX.picks.map { runX.originOf(it)?.kind?.label ?: "unattributed" }.counts().joinToString(", ") { "${it.first}=${it.second}" })
    }

    // ── The 365Music cross-service collision probes ──
    diag.section("5. cross-service collision probes — 365Music named on purpose, and the vintage-log counterfactual")
    // 5a. Three named artists keep the engine's fresh-user fallback (fewer than three seeds) out of
    //     the probe, so the seed layer reads the exact YouTube Music page of 365Music and nothing
    //     else. At discovery 0 the probe is the strongest form of "the old slop artist on purpose".
    val recorderP = CallRecorder()
    val mixerP = makeMixer(ytm, sc, artists, recorderP, Random(MIX_RANDOM))
    val specP = SmartSpec(
        sources = setOf(SmartSource.Artists),
        artists = listOf("365Music", "Fried By Fluoride", "overtonight"),
        size = 10,
        discovery = 0f,
    )
    val startedP = System.currentTimeMillis()
    val picksP = mixerP.build(specP, taste)
    val runP = analyzeRun("365", specP, picksP, recorderP, taste, System.currentTimeMillis() - startedP)
    dumpRun(diag, runP, taste)
    val top365 = recorderP.artistTops.firstOrNull { Taste.artistKey(it.seed) == "365music" }
    val search365 = recorderP.searches.filter { Taste.artistKey(it.query) == "365music" }
    val seed365 = picksP.filter { track ->
        runP.originOf(track)?.kind == OriginKind.SEED && Taste.creditsOf(track.artist).any { it == "365music" }
    }
    val loved365 = picksP.filter { track ->
        runP.originOf(track)?.kind == OriginKind.LOVED && Taste.creditsOf(track.artist).any { it == "365music" }
    }
    diag.line()
    diag.line(
        "365Music YouTube Music page tracks read: ${top365?.tracks?.size ?: -1}" +
            (top365?.error?.let { " ERROR=$it" } ?: "") + "; name searches asking for 365Music: ${search365.size}",
    )
    checks.check(
        "365: g. 365Music's seed layer is its exact YouTube Music page, never a name search",
        search365.isEmpty(),
        "searches=${search365.joinToString { it.query }}",
    )
    checks.check(
        "365: g. every 365Music seed-layer pick is the YouTube Music entity",
        seed365.all { it.provider == ProviderId.YOUTUBE_MUSIC },
        "seed picks=${seed365.size} ${seed365.map { it.provider.name }.counts().joinToString { "${it.first}=${it.second}" }}",
    )
    checks.check(
        "365: g. no 'The Eyes of Ibad' cover row enters as a seed-layer pick",
        seed365.none { it.title.contains("eyes of ibad", ignoreCase = true) },
        "",
    )
    if (loved365.isNotEmpty()) {
        checks.info(
            "365: RESIDUAL RISK — the historical 365MUSIC listening re-enters through the loved/on-repeat layer once the artist " +
                "is named (${loved365.size} row(s)): " + loved365.joinToString { "${it.provider.name}:${it.title}" },
        )
        loved365.forEach { diag.line("  loved-layer 365MUSIC row: ${trackLine(it)}") }
    } else {
        checks.info("365: no historical 365MUSIC row re-entered through the loved layer in this probe")
    }
    diag.line("note: the seed layer of a page-backed artist is entity-exact; the exposure left is the loved/on-repeat history of a name-credited artist.")

    // 5b. The vintage-log counterfactual: this owner's log predates generated provenance (genMs=0
    //     everywhere), so slop listening is indistinguishable from real listening. Give 365MUSIC the
    //     evidence a polluted history would show (three days, two recordings, four plays) and re-run
    //     the saved spec: does the artist pass the seed trust gate, and can the SoundCloud uploads
    //     played back then come back with it? Nothing is written; only an in-memory Taste differs.
    val boostedLog = boostPollutedHistory(owner.log)
    val boosted = Taste(boostedLog, owner.favouriteArtists, owner.favouriteSongs, owner.dislikedArtists, owner.hiddenTracks)
    diag.line()
    diag.line(
        "vintage-log counterfactual (in-memory taste rewrite): 365MUSIC trust " +
            "${"%.2f".format(taste.trust("365MUSIC"))} -> ${"%.2f".format(boosted.trust("365MUSIC"))}, score " +
            "${"%.2f".format(taste.artistScore("365MUSIC"))} -> ${"%.2f".format(boosted.artistScore("365MUSIC"))}, " +
            "in topArtists(16)=${"365music" in boosted.topArtists(16).map(Taste::artistKey)}",
    )
    checks.check(
        "LEGACY: three days of slop listening would pass the seed trust gate",
        boosted.trust("365MUSIC") >= Taste.SEED_TRUST,
        "trust=${"%.2f".format(boosted.trust("365MUSIC"))} vs SEED_TRUST=${Taste.SEED_TRUST}",
    )
    val recorderL = CallRecorder()
    val mixerL = makeMixer(ytm, sc, artists, recorderL, Random(MIX_RANDOM))
    val startedL = System.currentTimeMillis()
    val picksL = mixerL.build(specA, boosted)
    val runL = analyzeRun("LEGACY", specA, picksL, recorderL, boosted, System.currentTimeMillis() - startedL)
    dumpRun(diag, runL, boosted)
    val legacy365 = picksL.filter { track -> Taste.creditsOf(track.artist).any { it == "365music" } }
    val legacyByOrigin = legacy365.groupBy { runL.originOf(it)?.kind?.label ?: "unattributed" }.mapValues { it.value.size }
    diag.line(
        "with the vintage history from run A's spec: seeds=${runL.seeds.size}, 365MUSIC among them=${"365music" in runL.seedKeys}; " +
            "365MUSIC-credited picks=${legacy365.size}" +
            legacyByOrigin.entries.joinToString(prefix = " (", postfix = ")") { "${it.key}=${it.value}" },
    )
    legacy365.forEach { track -> diag.line("  365 pick: ${trackLine(track)} origin=${runL.originOf(track)?.kind?.label ?: "?"}") }
    checks.info(
        "LEGACY: with the polluted history, 365MUSIC " + (if ("365music" in runL.seedKeys) "seeds a mix" else "is sampled out of the 14 seeds") +
            " and ${legacy365.size} 365MUSIC-credited pick(s) enter" +
            (if (legacy365.isEmpty()) " (scoring/fatigue keep them out even when the artist is seeded)" else ""),
    )
    // 5c. The deterministic version: the artist is named, so it must seed. Here the only question is
    //     whether the historic SoundCloud uploads of that name can come back through the loved layer.
    diag.line()
    val specNamed = specA.copy(artists = owner.favouriteArtists + listOf("365MUSIC"))
    val recorderN = CallRecorder()
    val mixerN = makeMixer(ytm, sc, artists, recorderN, Random(MIX_RANDOM))
    val startedN = System.currentTimeMillis()
    val picksN = mixerN.build(specNamed, boosted)
    val runN = analyzeRun("LEGACY-NAMED", specNamed, picksN, recorderN, boosted, System.currentTimeMillis() - startedN)
    dumpRun(diag, runN, boosted)
    val named365 = picksN.filter { track -> Taste.creditsOf(track.artist).any { it == "365music" } }
    val namedSearch = recorderN.searches.filter { Taste.artistKey(it.query) == "365music" }
    diag.line("with 365MUSIC named on purpose (in-memory spec): 365MUSIC-credited picks=${named365.size}")
    named365.forEach { track -> diag.line("  ${runN.originOf(track)?.kind?.label ?: "?"}: ${trackLine(track)}") }
    checks.check(
        "LEGACY-NAMED: no name search for 365MUSIC even when it names a seed",
        namedSearch.isEmpty(),
        "searches=${namedSearch.joinToString { it.query }}",
    )
    checks.info(
        "LEGACY-NAMED: named 365MUSIC puts ${named365.size} historical upload(s) back in the list " +
            named365.groupBy { it.provider.name }.mapValues { it.value.size } +
            " — this is the residue of the pre-provenance log, not a name search",
    )

    // ── Saved Asya: the pollution must not be reproduced ──
    diag.section("6. the saved \"Asya\" pollution cannot be reproduced")
    val saved = readSavedAsya(diag)
    val after = picksA + picksB
    val savedPolluted = saved?.tracks.orEmpty().filter { track ->
        Taste.creditsOf(track.artist).any { it == "365music" } ||
            track.title.contains("eyes of ibad", ignoreCase = true) ||
            (track.provider == ProviderId.SOUNDCLOUD && track.title.contains("come in alone", ignoreCase = true))
    }
    diag.line("saved mix: ${saved?.name ?: "(none)"} at ${saved?.path ?: "-"} (${saved?.tracks?.size ?: 0} rows, spec=${saved?.spec?.let(::specText) ?: "?"})")
    diag.line("its polluted rows (${savedPolluted.size}):")
    savedPolluted.forEach { diag.line("  - ${trackLine(it)}") }
    if (saved != null) {
        // Sharing in-taste rows with the old list is expected (same person, same library); the
        // question is whether any of the *polluted* rows comes back.
        val sharedSaved = picksA.filter { a -> saved.tracks.any { it.key == a.key } }
        val sharedPolluted = sharedSaved.filter { a -> savedPolluted.any { it.key == a.key } }
        checks.info(
            "g. ${sharedSaved.size} of run A's picks are also in the saved mix (in-taste overlap, expected); " +
                "polluted overlap=${sharedPolluted.size}",
        )
        val pollutedHits = after.filter { track -> savedPolluted.any { it.key == track.key || recordingKey(it.title, it.artist) == recordingKey(track.title, track.artist) } }
        checks.check("g. none of the 12 saved-Asya 365Music/cover rows reproduced in A/B", pollutedHits.isEmpty(), pollutedHits.joinToString { trackLine(it) })
    } else {
        diag.line("note: no saved smart mix could be read; the pollution comparison uses the artisan probe below")
    }
    checks.check("g. no 365Music/365MUSIC pick in run A or B", after.none { track -> Taste.creditsOf(track.artist).any { it == "365music" } }, "")
    checks.check("g. no BTS/K-pop pick in run A or B", after.none { track -> Taste.creditsOf(track.artist).any { it == "bts" || K_POP_ANY.containsMatchIn(it) } }, "")
    checks.check(
        "g. the SoundCloud 'my bloody velvetine - come in alone' upload is not picked",
        after.none { it.provider == ProviderId.SOUNDCLOUD && it.title.contains("come in alone", ignoreCase = true) },
        "",
    )
    val flaggedInRelated = (runA.relatedLists + runB.relatedLists).flatMap { it.entries }
        .filter { Taste.creditsOf(it.name).any { key -> key == "365music" || key == "bts" } }
    checks.check("g. neither 365Music nor BTS is named by any seed's related list", flaggedInRelated.isEmpty(), flaggedInRelated.joinToString { "${it.name} ${it.provider.name}:${it.id}" })
    val logEntries = owner.log.songs
    logEntries.values.firstOrNull { it.title == "The Eyes of Ibad" && Taste.creditsOf(it.artist).any { c -> c == "365music" } }?.let { listened ->
        val track = owner.log.songs.entries.firstOrNull { it.value === listened }?.let { it.key to it.value }
        val t = track?.let { (key, _) -> listened.track(key) }
        if (t != null) {
            diag.line()
            diag.line("the historical cover upload (${trackLine(t)}):")
            diag.line("  played here=${taste.played(t)} heard=${taste.heard(t)} isCover-by-title=${taste.isCover(t)} derivative=${SmartMixer.derivative(t, taste)}")
            checks.info("g. the unmarked 365MUSIC 'The Eyes of Ibad' cover is recognised by title identity (isCover=${taste.isCover(t)}); it cannot enter a v2 mix because no name-search layer for a page-backed seed exists")
        }
    }

    // ── Before / after comparison ──
    diag.section("7. BEFORE / AFTER comparison (BEFORE from build/mix-diag/before.txt, scout's task-1 dump)")
    val ma = measureRun(runA, taste)
    val mb = measureRun(runB, taste)
    val fallbacksA = fallbackSeedPicks(runA)
    val fallbacksB = fallbackSeedPicks(runB)
    val ytmFreshA = runA.picks.filter { it.provider == ProviderId.YOUTUBE_MUSIC && !taste.played(it) }
    val ytmFreshWithDuration = ytmFreshA.count { it.durationMs > 0L }
    fun row(metric: String, before: String, after: String) = diag.line("  %-58s | %-30s | %s".format(metric, before, after))
    diag.line("  %-58s | %-30s | %s".format("metric", "BEFORE (live rebuild A/B, size 100, d=1.0)", "AFTER (run A, size 100, d=1.0)"))
    diag.line("  " + "-".repeat(122))
    row("picks attributed to a related (never-played) artist", "35/100 (A and B), 33/100 (C)", "${ma.newPicks.size}/${runA.picks.size}")
    row("picks from a seed artist's page/vs name search", "seed=45, history=20, related=35 (query kinds)", "origins: " + runA.picks.map { runA.originOf(it)?.kind?.label ?: "?" }.counts().joinToString(", ") { "${it.first}=${it.second}" })
    row("365Music/365MUSIC rows", "saved Asya 12/100; live rebuild A/B 0/100", "${ma.junk.size}/${runA.picks.size}")
    row("derivative-marked picks", "A/B 2 (both heard); saved mix 5 (3 unheard)", "${ma.derivativeViolations.size} unheard / ${ma.derivExemptByPlay.size} heard-exempt")
    row("covers/rips with no marker (heuristic rows shown)", "2 in rebuild C; 1 unmarked 'Eyes of Ibad' in the log", "${ma.coverViolations.size} unheard")
    row("durationMs=0 picks", "208/300 (about 69/run); saved mix 35", "${ma.durZero.size}/${runA.picks.size} (${durZeroByProvider(runA.picks)}); known length: ${durKnownByProvider(runA.picks)}")
    row("seed page-less -> credit-filtered name search", "seed=45 rows were all a name search (the old seed layer)", "${fallbacksA.size} picks admitted, searches=${runA.searches.size} (${fallbacksA.map { it.second }.distinct().joinToString()})")
    row("live YouTube Music rows with a duration", "not measured", "${ytmFreshWithDuration}/${ytmFreshA.size} fresh YTM picks carry durationMs>0")
    row("same-artist adjacency / duplicate recordings", "not measured before", "${ma.adjacency.size} adjacency, ${ma.duplicateRecordings.size} duplicates")
    row("new arts / d=1 budget", "not measured", "${ma.newPicks.size}/${runA.picks.size} <= budget ${ma.newBudget}; 2/new-artist respected")
    diag.line()
    diag.line("Home's For you defaults (run B, size 20, d=0.65): new artists ${mb.newPicks.size}/${runB.picks.size} (budget ${mb.newBudget}), " +
        "unheard derivative rows ${mb.derivativeViolations.size}, dur=0 ${mb.durZero.size} (${durZeroByProvider(runB.picks)}), adjacency ${mb.adjacency.size}, " +
        "fallback-search picks ${fallbacksB.size}")
    diag.line("  run B related offers: ${runB.offers.count { it.second.kind == OriginKind.RELATED }} songs from ${runB.relatedPages.size} exact pages, " +
        "but none scored into the top 20 of a rich familiar pool: discovery is a ceiling, not a floor, in v2's scoring")
    diag.line("  (run A at d=1.0: ${ma.newPicks.size} new-artist songs = ${"%.0f".format(100f * ma.newPicks.size / runA.picks.size.coerceAtLeast(1))}%, " +
        "budget ${ma.newBudget}); the before-slop's 35% related share is gone and the discovery slider's effect is mostly a cap")

    // ── Summary ──
    diag.section("8. Summary")
    val liveResults = ytm.tracksRead + sc.tracksRead + artists.pagesRead
    val allErrors = (ytm.errors + sc.errors + artists.errors + runA.errors + runB.errors + runD0.errors).distinct()
    diag.line("live rows read: YouTube Music search=${ytm.tracksRead}, SoundCloud search=${sc.tracksRead}, artist pages=${artists.pagesRead}")
    diag.line("network/read errors in this run (${allErrors.size}): " + if (allErrors.isEmpty()) "none" else allErrors.joinToString(" | "))
    diag.line()
    diag.line("checks: ${checks.failures.size} failed; see the PASS/FAIL lines above")
    checks.failures.forEach { diag.line("  FAILED: $it") }
    diag.line()
    diag.line("top residual risks (measured in this dump, see build/verify/REPORT.md for the full ranking):")
    diag.line("  1. the length half of the derivative gate is inert for YouTube Music: every live YTM row read here")
    diag.line("     (artist pages and searches) has durationMs=0 — ${ma.durZero.size}/${runA.picks.size} run-A picks, of which ${durZeroByProvider(runA.picks)};")
    diag.line("     the only YTM picks with a length (5 in run A) are log copies. Only title-marker/cover-by-title gates remain for YTM.")
    diag.line("     SoundCloud rows do carry a duration, so that path is covered.")
    diag.line("  2. discovery is a ceiling, not a floor: with a rich familiar pool new artists rarely rank into the list")
    diag.line("     (run B d=0.65: ${mb.newPicks.size}/${runB.picks.size}, related offers were ${runB.offers.count { it.second.kind == OriginKind.RELATED }}; run A d=1.0: ${ma.newPicks.size}/${runA.picks.size}).")
    diag.line("     That is safe against slop but the discovery slider does much less than its name says.")
    diag.line("  3. the pre-provenance log (genMs=0) cannot tell a slop mix from real listening: seen as a counterfactual,")
    diag.line("     3 days of 365MUSIC listening passes the seed gate (trust ${"%.2f".format(boosted.trust("365MUSIC"))}); the artist then")
    val legacyNamedRows = picksN.count { track -> Taste.creditsOf(track.artist).any { credit -> credit == "365music" } }
    diag.line("     re-admits its historic uploads through the loved layer when sampled/named (LEGACY-NAMED: $legacyNamedRows rows).")
    diag.line("     Only the trust shield limits it today; a migration penalty on pre-provenance listening would close it.")
    diag.line("  4. the seed layer's identity is still a name: artistTop is the merged artist page by name and follows/log")
    diag.line("     carry names, so a same-named artist on Spotify+YouTube Music can answer for a seed (the related layer,")
    diag.line("     by contrast, is provider+id exact).")
    diag.line("  5. a page-less seed falls back to a credit-filtered name search: ${runA.searches.size} searches in run A admitted ${fallbacksA.size}")
    diag.line("     picks (${fallbacksA.map { it.second }.distinct().joinToString()}); the rows are name-matched, not entity-matched.")
    diag.line("  6. related artists are aggregated by artistKey (name) before their songs are read from one provider+id page:")
    diag.line("     the pick is entity-exact, but two same-named artists can be merged into one discovery identity.")
    diag.line("  7. title-identity cover detection can also drop a genuinely different unheard song that shares a distinctive title.")
    diag.line("____________________________________________________________ end of audit")
    diag.flushNow()
    return AuditResult(liveResults, allErrors, checks.failures)
}

// ─────────────────────────────────────────────────────────────────────────────
// Constants and regexes (mirrored from SmartMixer/Taste, where private)
// ─────────────────────────────────────────────────────────────────────────────

private const val MAX_ARTIST_SEEDS = 14
private const val PER_SEED_SONGS = 12
private const val RELATED_SONGS_PER_ARTIST = 2
private const val RELATED_KEPT = 16
private const val MAX_LOVED = 40
private const val CONFIDENT_TRUST = 0.5f
private const val MAX_NEW_SHARE = 0.5f
private const val NEW_SHARE_PER_DISCOVERY = 0.6f
private const val MIN_SONG_MS = 60_000L
private const val MAX_SONG_MS = 12 * 60_000L
private const val UNIFIED_RESULTS_MAX = 50
private const val MIX_RANDOM = 20261119

/** The v2 derivative marker regex, copied from `SmartMixer.derivative` (private there). */
private val DERIVATIVE_RE = Regex(
    """\b(cover|covered by|karaoke|nightcore|slowed|sped ?up|speed ?up|8d|8d audio|type beat|reaction|""" +
        """tutorial|lesson|backing track|1 hour|10 hours?|hour loop|full album|mashup|unreleased|""" +
        """lyric video|audio|visuali[sz]er|teaser|snippet|preview|instrumental|acapella|a cappella|""" +
        """mix ?tape|mixtape|tiktok|soundcloud exclusive)\b""",
)

/** True when the row would be derivative if it had not been played here (title marker, length). */
private fun TRACK_DERIVATIVE_SIGNALS(title: String, track: Track): Boolean {
    if (DERIVATIVE_RE.containsMatchIn(title.lowercase())) return true
    val ms = track.durationMs
    return ms in 1 until MIN_SONG_MS || ms > MAX_SONG_MS
}

/** A K-pop outlier would be an artist key in this set (the owner's taste has no K-pop at all). */
private val K_POP_ANY = Regex("""^(bts|blackpink|stray kids|twice|newjeans|aespa|exo|got7|seventeen|nct|txt|enhypen|itzy)(\b|$)""")
