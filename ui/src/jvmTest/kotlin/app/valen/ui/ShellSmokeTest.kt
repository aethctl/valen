/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.geometry.Size
import app.valen.core.MusicProvider
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.SettingsStore
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shell, driven the way a person drives it, against services that answer instantly and need
 * no network: every screen at every width, then a walk through search, play, queue, stage and
 * settings. What it guards is the class of failure a screenshot cannot show: a screen that throws
 * when it is navigated to or away from.
 */
@OptIn(ExperimentalTestApi::class)
class ShellSmokeTest {

    private class FakeProvider(override val id: ProviderId) : MusicProvider {
        override val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
        override val player = MutableStateFlow<PlayerSnapshot?>(null)
        override val events = MutableSharedFlow<ProviderEvent>()

        override suspend fun connect() { session.value = SessionState.Connected("tester") }
        override suspend fun disconnect() { session.value = SessionState.SignedOut }
        override suspend fun search(query: String, limit: Int): List<Track> = List(4) { n ->
            Track(
                provider = id,
                id = "$query-$n",
                title = "${id.short} song $n",
                artist = "Artist $n",
                durationMs = 180_000L + n * 1_000L,
            )
        }

        override suspend fun play(track: Track, startMs: Long) {
            player.value = PlayerSnapshot(track = track, isPlaying = true, durationMs = track.durationMs)
        }

        override suspend fun pause() { player.value = player.value?.copy(isPlaying = false) }
        override suspend fun resume() { player.value = player.value?.copy(isPlaying = true) }
        override suspend fun seek(positionMs: Long) { player.value = player.value?.copy(positionMs = positionMs) }
        override suspend fun setVolume(volume: Float) {}
        override suspend fun close() {}
    }

    private fun coordinator(scope: CoroutineScope) = PlaybackCoordinator(
        providers = ProviderId.entries.map { FakeProvider(it) },
        scope = scope,
        settingsStore = SettingsStore(read = { null }, write = {}),
    )

    /** The app has animations that never end (the playing bars, the drifting wash), so time is stepped by hand. */
    private fun SkikoComposeUiTest.settle(ms: Long = 700L) {
        mainClock.advanceTimeBy(ms)
    }

    private fun inApp(width: Int, height: Int, block: SkikoComposeUiTest.(PlaybackCoordinator) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                mainClock.autoAdvance = false
                block(coordinator(scope))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `every screen composes at every width, empty and with a queue`() {
        val sizes = listOf(1600 to 900, 2200 to 1200, 860 to 700, 390 to 844)
        for ((width, height) in sizes) {
            for (playing in listOf(false, true)) {
                for (route in Route.entries) {
                    inApp(width, height) { coordinator ->
                        if (playing) {
                            runBlocking {
                                val songs = coordinator.unifiedSearch("warm up").tracks
                                coordinator.play(songs[1], songs)
                            }
                        }
                        setContent { ValenApp(coordinator, route.name) }
                        settle()
                        settle()
                    }
                }
            }
        }
    }

    @Test
    fun `search, play, queue, stage and settings on a wide window`() = inApp(1600, 900) { coordinator ->
        setContent { ValenApp(coordinator) }
        settle()

        onNodeWithText("Search").performClick()
        settle()
        onNode(hasSetTextAction()).performTextInput("anything")
        settle(1_200L)

        // Three services, four songs each, none shared: one merged list of twelve.
        assertEquals(12, coordinator.unifiedSearchCount("anything"))
        onAllNodesWithText("Spotify song 0")[0].performClick()
        settle()
        assertEquals("Spotify song 0", coordinator.snapshot.value?.track?.title)
        assertEquals(12, coordinator.queue.value.size)

        // The dock arrived with the song; its transport drives the same coordinator.
        onNodeWithContentDescription("Pause").performClick()
        settle()
        assertEquals(false, coordinator.snapshot.value?.isPlaying)
        onNodeWithContentDescription("Next").performClick()
        settle()
        assertEquals(1, coordinator.index.value)

        onNodeWithText("Queue").performClick()
        settle()
        onNodeWithText("Up next").assertExists()

        onNodeWithText("Now Playing").performClick()
        settle()
        onNodeWithContentDescription("Close the player").performClick()
        settle()
        // Closing the stage returns to the place it was opened over.
        onNodeWithText("Up next").assertExists()

        onNodeWithText("Settings").performClick()
        settle()
        onNodeWithText("Audio quality").assertExists()

        onNodeWithText("Home").performClick()
        settle()
        assertTrue(coordinator.queue.value.isNotEmpty())
    }

    @Test
    fun `a phone opens the stage and the queue as sheets and closes them again`() = inApp(390, 844) { coordinator ->
        runBlocking {
            val songs = coordinator.unifiedSearch("warm up").tracks
            coordinator.play(songs[0], songs)
        }
        setContent { ValenApp(coordinator) }
        settle()

        onNodeWithText("Open player").performClick()
        settle()
        onNodeWithContentDescription("Close the player").assertExists()
        // Home is still composed under the sheet and has an "Up next" of its own; the stage's is the later one.
        onAllNodesWithText("Up next").onLast().performClick()
        settle()
        onNodeWithContentDescription("Close the queue").performClick()
        settle()
        onNodeWithContentDescription("Close the player").performClick()
        settle()
        onNodeWithText("Open player").assertExists()
    }

    private fun PlaybackCoordinator.unifiedSearchCount(query: String): Int =
        runBlocking { unifiedSearch(query).hits.size }
}
