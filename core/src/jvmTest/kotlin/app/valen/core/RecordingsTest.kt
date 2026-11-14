/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Cross-service identity: the same recording spelled differently by two catalogues must produce
 * one key, and a genuine alternate take must not.
 */
class RecordingsTest {

    private val studio = recordingKey("Shape of You", "Ed Sheeran")

    @Test
    fun `marketing noise in the title does not change the recording`() {
        assertEquals(studio, recordingKey("Shape of You (Official Video)", "Ed Sheeran"))
        assertEquals(studio, recordingKey("Shape of You [Official Audio]", "Ed Sheeran"))
        assertEquals(studio, recordingKey("Shape of You - Remastered 2011", "Ed Sheeran"))
        assertEquals(studio, recordingKey("Shape of You (Official Music Video)", "Ed Sheeran"))
        assertEquals(studio, recordingKey("Shape of You (Lyrics)", "Ed Sheeran"))
    }

    @Test
    fun `a featured artist belongs to the lead artist's recording`() {
        assertEquals(studio, recordingKey("Shape of You (feat. Beyoncé)", "Ed Sheeran"))
        assertEquals(
            recordingKey("One Kiss", "Calvin Harris"),
            recordingKey("One Kiss", "Calvin Harris, Dua Lipa"),
        )
    }

    @Test
    fun `a different take stays a different recording`() {
        assertNotEquals(studio, recordingKey("Shape of You (Live at Wembley)", "Ed Sheeran"))
        assertNotEquals(studio, recordingKey("Shape of You (Foo Remix)", "Ed Sheeran"))
        assertNotEquals(studio, recordingKey("Shape of You (Acoustic)", "Ed Sheeran"))
        assertNotEquals(studio, recordingKey("Shape of You - Live", "Ed Sheeran"))
    }

    @Test
    fun `two services' copies collapse to the first`() {
        val tracks = listOf(
            Track(ProviderId.SPOTIFY, "sp", "One More Time", "Daft Punk"),
            Track(ProviderId.YOUTUBE_MUSIC, "yt", "One More Time (Official Video)", "Daft Punk"),
            Track(ProviderId.SOUNDCLOUD, "sc", "Aerodynamic", "Daft Punk"),
        )
        assertEquals(listOf("sp", "sc"), tracks.distinctRecordings().map { it.id })
    }
}
