/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun song(id: String, artist: String, title: String = id) = Track(
    provider = ProviderId.YOUTUBE_MUSIC,
    id = id,
    title = title,
    artist = artist,
    durationMs = 200_000,
    uri = "uri:$id",
)

private class LentProvider : MusicProvider {
    override val id = ProviderId.YOUTUBE_MUSIC
    override val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val player = MutableStateFlow<PlayerSnapshot?>(null)
    override val events = MutableSharedFlow<ProviderEvent>()
    var played: Track? = null
    var startedAt = 0L

    override suspend fun connect() {}
    override suspend fun disconnect() {}
    override suspend fun search(query: String, limit: Int): List<Track> = emptyList()
    override suspend fun play(track: Track, startMs: Long) {
        played = track
        startedAt = startMs
        player.value = PlayerSnapshot(track = track, isPlaying = true, positionMs = startMs, durationMs = track.durationMs)
    }
    override suspend fun pause() { player.value = player.value?.copy(isPlaying = false) }
    override suspend fun resume() { player.value = player.value?.copy(isPlaying = true) }
    override suspend fun seek(positionMs: Long) { player.value = player.value?.copy(positionMs = positionMs) }
    override suspend fun setVolume(volume: Float) {}
    override suspend fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecapTest {
    /** A zone two hours ahead of UTC, so local days and UTC days differ. */
    private val offset = 2 * HOUR_MS
    private val day = 20_400L

    /** Epoch ms of [hour] local time on local day [d]. */
    private fun at(d: Long, hour: Int) = d * DAY_MS + hour * HOUR_MS - offset

    private fun stats(clock: () -> Long) = ListeningStats(ListeningStore.None, clock = clock, publishEveryMs = 0, offset = { offset })

    @Test
    fun `a day is kept in local time, by song, start and hour`() {
        var now = at(day, 23)
        val stats = stats { now }
        val a = song("a", "Aya")
        stats.count(a, 60_000, null)
        now = at(day + 1, 0) + 5_000
        stats.count(a, 30_000, null)
        stats.flush()
        val log = stats.log.value
        val first = log.daily[day.toString()]!!
        assertEquals(60_000L, first.ms)
        assertEquals(1, first.plays["YOUTUBE_MUSIC:a"])
        assertEquals(60_000L, first.hours[23])
        val second = log.daily[(day + 1).toString()]!!
        assertEquals(30_000L, second.hours[0])
        // The same song going on past midnight is not a second start.
        assertNull(second.plays["YOUTUBE_MUSIC:a"])
        // Enough to play it again later.
        assertEquals(a, log.songs["YOUTUBE_MUSIC:a"]!!.track("YOUTUBE_MUSIC:a"))
        assertTrue(log.songs["YOUTUBE_MUSIC:a"]!!.first > 0)
    }

    @Test
    fun `days older than the kept window are let go`() {
        var now = at(day, 12)
        val stats = stats { now }
        stats.count(song("a", "Aya"), 60_000, null)
        now = at(day + DAILY_KEEP_DAYS, 12)
        stats.count(song("b", "Bo"), 60_000, null)
        stats.flush()
        assertEquals(setOf((day + DAILY_KEEP_DAYS).toString()), stats.log.value.daily.keys)
        // The totals keep everything.
        assertEquals(2, stats.log.value.songs.size)
    }

    @Test
    fun `a week counted from its first day uses the whole log`() {
        var now = at(day, 10)
        val stats = stats { now }
        val a = song("a", "Aya")
        val b = song("b", "Bo")
        repeat(3) {
            stats.count(a, 300_000, null)
            stats.count(b, 120_000, null)
        }
        now = at(day + 2, 21)
        stats.count(a, 300_000, null)
        stats.flush()

        val week = stats.log.value.week(today = day + 3, offsetMs = offset)
        assertNotNull(week)
        week!!
        assertEquals(day - 3, week.firstDay)
        assertEquals(listOf("a", "b"), week.songs.map { it.track.id })
        assertEquals(1_200_000L, week.songs[0].ms)
        assertEquals(4, week.songs[0].plays)
        assertEquals("Aya", week.artists.first().name)
        assertEquals(2, week.artistCount)
        // Counting began this week: every song is new, which is no news.
        assertNull(week.newSongs)
        assertNull(week.previousMs)
        // Ten in the morning has the most (all of it is known by the hour here).
        assertEquals(10, week.peakHour)
    }

    @Test
    fun `an older log tells only the last seven days`() {
        var now = at(day, 12)
        val stats = stats { now }
        val old = song("old", "Orla")
        stats.count(old, 3_600_000, null)
        now = at(day + 20, 9)
        val fresh = song("new", "Nell")
        stats.count(fresh, 900_000, null)
        stats.count(old, 400_000, null)
        stats.flush()

        val week = stats.log.value.week(today = day + 21, offsetMs = offset)!!
        // The hour of "old" counted three weeks ago is not this week's.
        assertEquals(listOf("new", "old"), week.songs.map { it.track.id })
        assertEquals(1_300_000L, week.totalMs)
        assertEquals(listOf("new"), week.newSongs?.map { it.track.id })
        assertEquals(1, week.newArtistCount)
        assertEquals(0L, week.previousMs)
        assertFalse(week.hours == null)
        assertEquals(9, week.peakHour)
    }

    @Test
    fun `the card waits by itself on sunday and monday only`() {
        // 2026-11-15 was a Sunday: epoch day 20772.
        val sunday = 20772L
        assertTrue(recapDue(sunday))
        assertTrue(recapDue(sunday + 1))
        assertFalse((2L..6L).any { recapDue(sunday + it) })
        assertTrue(recapDue(sunday + 7))
    }

    @Test
    fun `a quiet week is no story`() {
        var now = at(day, 12)
        val stats = stats { now }
        stats.count(song("a", "Aya"), 60_000, null)
        stats.flush()
        assertNull(stats.log.value.week(today = day, offsetMs = offset))
    }

    @Test
    fun `a lent player is given back as it was and counts nothing`() = runTest {
        val provider = LentProvider()
        val stats = ListeningStats(ListeningStore.None)
        var saved: String? = null
        val store = SettingsStore(read = { saved }, write = { saved = it })
        val coordinator = PlaybackCoordinator(listOf(provider), backgroundScope, store, stats = stats)
        val a = song("a", "Aya")
        val b = song("b", "Bo")
        coordinator.play(b, listOf(a, b))
        provider.seek(42_000)
        // The tick reads the moving position from the service.
        testScheduler.advanceTimeBy(300)
        coordinator.saveSession()
        val session = coordinator.settings.value.session

        coordinator.lend()
        val snippet = song("hook", "Hana")
        coordinator.playLent(snippet, 61_000)
        assertEquals(snippet, provider.played)
        assertEquals(61_000L, provider.startedAt)
        assertEquals(listOf(snippet), coordinator.queue.value)
        // Nothing of the borrowed song reaches the session or the listening.
        assertEquals(session, coordinator.settings.value.session)
        testScheduler.advanceTimeBy(3_000)
        stats.flush()
        assertNull(stats.log.value.songs["YOUTUBE_MUSIC:hook"])

        coordinator.giveBack()
        assertFalse(coordinator.isLent)
        assertEquals(listOf(a, b), coordinator.queue.value)
        assertEquals(1, coordinator.index.value)
        // It was playing, so it plays again from where it was.
        assertEquals(b, provider.played)
        assertTrue(provider.startedAt in 41_000L..43_000L)
    }

    @Test
    fun `a paused song is given back paused at its place`() = runTest {
        val provider = LentProvider()
        val coordinator = PlaybackCoordinator(listOf(provider), backgroundScope, SettingsStore(read = { null }, write = {}))
        val a = song("a", "Aya")
        coordinator.play(a, listOf(a))
        provider.seek(30_000)
        testScheduler.advanceTimeBy(300)
        coordinator.toggle()
        testScheduler.advanceTimeBy(300)

        coordinator.lend()
        coordinator.playLent(song("hook", "Hana"), 10_000)
        coordinator.giveBack()

        val shown = coordinator.snapshot.value!!
        assertEquals(a, shown.track)
        assertFalse(shown.isPlaying)
        assertEquals(30_000L, shown.positionMs)
        assertNull(coordinator.active.value)
        // Play picks it up where it was.
        coordinator.toggle()
        assertEquals(a, provider.played)
        assertEquals(30_000L, provider.startedAt)
    }
}
