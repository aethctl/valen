/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class Adapter(override val id: ProviderId, override val playsSignedOut: Boolean = true) : MusicProvider {
    override val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val player = MutableStateFlow<PlayerSnapshot?>(null)
    override val events = MutableSharedFlow<ProviderEvent>()
    var played: Track? = null
    override suspend fun connect() {}
    override suspend fun disconnect() {}
    override suspend fun search(query: String, limit: Int): List<Track> = emptyList()
    override suspend fun play(track: Track, startMs: Long) {
        played = track
        player.value = PlayerSnapshot(track = track, isPlaying = true, durationMs = track.durationMs)
    }
    override suspend fun pause() {}
    override suspend fun resume() {}
    override suspend fun seek(positionMs: Long) {}
    override suspend fun setVolume(volume: Float) {}
    override suspend fun close() {}
}

private class Shelf(private val files: Map<String, String>) : OfflineShelf {
    override val state: StateFlow<OfflineState> = MutableStateFlow(OfflineState(kept = files.keys))
    override fun fileFor(track: Track): String? = files[track.key]
    override fun keep(playlist: Playlist) {}
    override suspend fun forget(playlistId: String) {}
    override val folder: String = "/music"
}

/** A downloaded song plays from this device, under its own name, whatever its service can do now. */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineRoutingTest {

    private fun settings() = SettingsStore(read = { null }, write = {})
    private val song = Track(ProviderId.SPOTIFY, "s1", "Get Lucky", "Daft Punk", durationMs = 248_000)
    private val other = Track(ProviderId.SPOTIFY, "s2", "Instant Crush", "Daft Punk", durationMs = 337_000)

    @Test
    fun `a kept spotify song plays through the device while spotify is signed out`() = runTest {
        val spotify = Adapter(ProviderId.SPOTIFY, playsSignedOut = false)
        val device = Adapter(ProviderId.LOCAL)
        val coordinator = PlaybackCoordinator(
            listOf(spotify, device), backgroundScope, settings(),
            offline = Shelf(mapOf(song.key to "/music/Daft Punk/Get Lucky.opus")),
        )
        assertTrue(coordinator.canPlayNow(song))
        assertFalse(coordinator.canPlayNow(other))

        coordinator.play(song, listOf(song, other))
        assertEquals(song, device.played, "the device plays the song under its own track")
        assertNull(spotify.played)
        assertEquals(ProviderId.LOCAL, coordinator.active.value)
        assertEquals(song.key, coordinator.snapshot.value?.track?.key)
    }

    @Test
    fun `without a device adapter a kept song is not routed anywhere new`() = runTest {
        val spotify = Adapter(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings(), offline = Shelf(mapOf(song.key to "/x.opus")))
        assertFalse(coordinator.isKept(song))
        coordinator.play(song)
        assertEquals(song, spotify.played)
    }

    @Test
    fun `this device comes first in the order`() = runTest {
        val coordinator = PlaybackCoordinator(
            listOf(Adapter(ProviderId.YOUTUBE_MUSIC), Adapter(ProviderId.LOCAL), Adapter(ProviderId.SPOTIFY)),
            backgroundScope, settings(),
        )
        assertEquals(ProviderId.LOCAL, coordinator.priorityOrder().first())
        assertEquals(listOf(ProviderId.LOCAL, ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC), coordinator.priorityOrder())
    }
}
