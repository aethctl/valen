/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

/** A song of the week: the song itself (to play it again) and how much of the week it took. */
data class RecapSong(val track: Track, val ms: Long, val plays: Int)

/** An artist of the week, with their songs heard that week, the most heard first. */
data class RecapArtist(val name: String, val ms: Long, val plays: Int, val songs: List<RecapSong>) {
    val artworkUrl: String? get() = songs.firstNotNullOfOrNull { it.track.artworkUrl }
}

/**
 * The last seven days of listening, ending today, ready to be told as a story. Everything here is
 * counted by Valen (see [ListeningStats]); nothing comes from a service.
 */
data class WeekRecap(
    /** The first and last day of the week, as local epoch days. */
    val firstDay: Long,
    val lastDay: Long,
    val totalMs: Long,
    val plays: Int,
    /** Time on each of the seven days, oldest first. */
    val days: List<Long>,
    /** The week before this one, when it was counted; null when counting began since. */
    val previousMs: Long?,
    /** The most heard songs, at most [TOP_SONGS]. */
    val songs: List<RecapSong>,
    val songCount: Int,
    /** The most heard artists, at most [TOP_ARTISTS]. */
    val artists: List<RecapArtist>,
    val artistCount: Int,
    /**
     * Songs first heard this week, the most heard first; null when counting began this week
     * (then every song is new, which says nothing).
     */
    val newSongs: List<RecapSong>?,
    val newSongCount: Int,
    val newArtistCount: Int,
    /** Time in each hour of the day (0–23) across the week; null when too little is known. */
    val hours: List<Long>?,
) {
    /** The day (0–6 in [days]) with the most listening. */
    val busiestDay: Int get() = days.indices.maxByOrNull { days[it] } ?: days.lastIndex

    /** The hour with the most listening, when [hours] are known. */
    val peakHour: Int? get() = hours?.let { h -> h.indices.maxByOrNull { h[it] } }

    companion object {
        const val TOP_SONGS = 10
        const val TOP_ARTISTS = 5

        /** Less than this in a week is not a story. */
        const val MIN_MS = 5 * 60_000L

        /** Hours are shown only once this much of the week was counted by the hour. */
        const val MIN_HOURS_MS = 20 * 60_000L
    }
}

/**
 * Whether "Your week" waits on Home by itself on [today] (a local epoch day): at the end of the
 * week, Sunday, and on Monday too so a week ended in sleep is not missed.
 */
fun recapDue(today: Long): Boolean = Math.floorMod(today + 3, 7L).toInt() in RECAP_DAYS

/** Days of the week (Monday 0 … Sunday 6) on which the card shows by itself. */
private val RECAP_DAYS = setOf(6, 0)

/**
 * The seven days ending on [today] (a local epoch day; [offsetMs] is the device's distance from
 * UTC), or null when they hold less than [WeekRecap.MIN_MS].
 *
 * When counting began inside the week, the whole log *is* the week, so the totals are used as
 * they are; otherwise the week is added up from [ListeningLog.daily].
 */
fun ListeningLog.week(today: Long, offsetMs: Long = localOffsetMs(System.currentTimeMillis())): WeekRecap? {
    val first = today - 6
    val weekStartMs = first * DAY_MS - offsetMs
    val wholeLog = since > 0 && since >= weekStartMs
    val range = first..today

    // Each song's time and starts in the week.
    val heard = LinkedHashMap<String, Pair<Long, Int>>()
    if (wholeLog) {
        songs.forEach { (key, song) -> heard[key] = song.ms to song.plays }
    } else {
        for (d in range) {
            val day = daily[d.toString()] ?: continue
            day.songs.forEach { (key, ms) ->
                val (m, p) = heard[key] ?: (0L to 0)
                heard[key] = (m + ms) to (p + (day.plays[key] ?: 0))
            }
        }
    }
    val weekSongs = heard.mapNotNull { (key, mp) ->
        val song = songs[key] ?: return@mapNotNull null
        val track = song.track(key) ?: return@mapNotNull null
        if (mp.first < 1_000) return@mapNotNull null
        RecapSong(track, mp.first, mp.second)
    }.sortedWith(compareByDescending<RecapSong> { it.ms }.thenByDescending { it.plays })
    val totalMs = weekSongs.sumOf { it.ms }
    if (totalMs < WeekRecap.MIN_MS) return null

    val artists = weekSongs.groupBy { it.track.artist }
        .map { (name, list) -> RecapArtist(name, list.sumOf { it.ms }, list.sumOf { it.plays }, list) }
        .sortedByDescending { it.ms }

    // The bars of the week: each day as counted in local days, or as the older UTC days had it
    // (the two overlap but for the zone's hours; the larger is the fuller count).
    val dayBars = range.map { d -> maxOf(daily[d.toString()]?.ms ?: 0L, days[d.toString()] ?: 0L) }

    val previous = if (since > 0 && since <= weekStartMs - 7 * DAY_MS) {
        ((first - 7) until first).sumOf { d -> maxOf(daily[d.toString()]?.ms ?: 0L, days[d.toString()] ?: 0L) }
    } else {
        null
    }

    val newSongs = if (wholeLog) null else weekSongs.filter { songs[it.track.key]?.first?.let { f -> f >= weekStartMs } == true }
    val newArtists = if (wholeLog) 0 else artists.count { artist ->
        songs.values.filter { it.artist == artist.name }.all { it.first >= weekStartMs }
    }

    val hourTotals = LongArray(24)
    for (d in range) daily[d.toString()]?.hours?.forEach { (h, ms) -> if (h in 0..23) hourTotals[h] += ms }
    val hours = hourTotals.takeIf { it.sum() >= WeekRecap.MIN_HOURS_MS }?.toList()

    return WeekRecap(
        firstDay = first,
        lastDay = today,
        totalMs = totalMs,
        plays = weekSongs.sumOf { it.plays },
        days = dayBars,
        previousMs = previous,
        songs = weekSongs.take(WeekRecap.TOP_SONGS),
        songCount = weekSongs.size,
        artists = artists.take(WeekRecap.TOP_ARTISTS),
        artistCount = artists.size,
        newSongs = newSongs?.take(WeekRecap.TOP_SONGS),
        newSongCount = newSongs?.size ?: 0,
        newArtistCount = newArtists,
        hours = hours,
    )
}
