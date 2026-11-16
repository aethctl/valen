/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf

class LyricsBookTest {
    private val song = Track(ProviderId.SOUNDCLOUD, "1", "Paper Moons", "The Walls", durationMs = 180_000)
    private val words = Lyrics(listOf(LyricLine(1_000, 2_000, "chalk")), LyricsTiming.Lines, "first")

    private class Source(override val name: String, val answer: suspend (LyricsQuery) -> Lyrics?) : LyricsSource {
        var asked = 0
        override suspend fun find(query: LyricsQuery): Lyrics? {
            asked++
            return answer(query)
        }
    }

    private class MemoryStore : LyricsStore {
        val kept = mutableMapOf<String, LyricsStore.Kept>()
        var now = 0L
        override fun read(key: String) = kept[key]
        override fun write(key: String, lyrics: Lyrics?) { kept[key] = LyricsStore.Kept(lyrics, now) }
    }

    @Test
    fun `the first source with words wins and the rest are not asked`() = runTest {
        val a = Source("a") { null }
        val b = Source("b") { words }
        val c = Source("c") { error("not reached") }
        val book = LyricsBook(listOf(a, b, c))
        assertEquals(LyricsState.Found(words), book.lyricsFor(song))
        assertEquals(0, c.asked)
        // Kept: asked again, nobody is asked.
        book.lyricsFor(song)
        assertEquals(1, b.asked)
    }

    @Test
    fun `a failing source is not a miss and is not remembered`() = runTest {
        val broken = Source("Lyprio") { throw LyricsUnavailable("busy") }
        val store = MemoryStore()
        val book = LyricsBook(listOf(broken), store)
        val state = assertInstanceOf(LyricsState.Failed::class.java, book.lyricsFor(song))
        assertEquals("Lyprio: busy", state.reason)
        assertEquals(0, store.kept.size)
        book.lyricsFor(song)
        assertEquals(2, broken.asked)
    }

    @Test
    fun `a failure before a source that has them still finds them`() = runTest {
        val book = LyricsBook(listOf(Source("a") { throw LyricsUnavailable("down") }, Source("b") { words }))
        assertEquals(LyricsState.Found(words), book.lyricsFor(song))
    }

    @Test
    fun `a miss is kept for days, then asked again`() = runTest {
        val none = Source("a") { null }
        val store = MemoryStore()
        var now = 1_000L
        store.now = now
        val book = LyricsBook(listOf(none), store, clock = { now })
        assertEquals(LyricsState.Missing, book.lyricsFor(song))
        val fresh = LyricsBook(listOf(none), store, clock = { now })
        assertEquals(LyricsState.Missing, fresh.lyricsFor(song))
        assertEquals(1, none.asked)
        now += LyricsBook.MISS_KEPT_MS + 1
        LyricsBook(listOf(none), store, clock = { now }).lyricsFor(song)
        assertEquals(2, none.asked)
    }

    @Test
    fun `two screens asking at once share one lookup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = Source("a") { gate.await(); words }
        val book = LyricsBook(listOf(slow))
        val one = async { book.lyricsFor(song) }
        val two = async { book.lyricsFor(song.copy(provider = ProviderId.YOUTUBE_MUSIC, id = "x")) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        assertEquals(one.await(), two.await())
        assertEquals(1, slow.asked)
    }

    @Test
    fun `a source that was not asked keeps a miss from being remembered`() = runTest {
        val store = MemoryStore()
        val notUp = Source("Spotify") { throw LyricsSkipped("not up") }
        val none = Source("LRCLIB") { null }
        assertEquals(LyricsState.Missing, LyricsBook(listOf(notUp, none), store).lyricsFor(song))
        assertEquals(0, store.kept.size)
    }

    @Test
    fun `a service's own lyrics are a source`() = runTest {
        var asked: LyricsQuery? = null
        val catalog = object : LyricsCatalog {
            override suspend fun serviceLyrics(query: LyricsQuery): Lyrics? { asked = query; return words }
        }
        val spotifySong = Track(ProviderId.SPOTIFY, "4uLU6hMCjMI75M1A2tKUQC", "Paper Moons", "The Walls")
        assertEquals(LyricsState.Found(words), LyricsBook(listOf(ServiceLyrics("Spotify", catalog))).lyricsFor(spotifySong))
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", asked?.spotifyId)
    }

    @Test
    fun `the store answers before any source`() = runTest {
        val store = MemoryStore().apply { write(LyricsBook.keyOf(song), words) }
        val never = Source("a") { error("not asked") }
        assertEquals(LyricsState.Found(words), LyricsBook(listOf(never), store).lyricsFor(song))
    }
}
