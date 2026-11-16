/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class SoundTest {

    @Test
    fun `the curve drawn is the curve applied`() {
        // A band alone peaks at its own gain at its centre and stays near flat two octaves away.
        val one = SoundEffects(bands = SoundEffects.FLAT_BANDS.toMutableList().also { it[5] = 6f })
        assertEquals(6f, one.responseDb(1000f), 0.05f)
        assertTrue(abs(one.responseDb(250f)) < 0.6f)
        // The shelves reach their gain past their corner, the preamp lifts everything.
        assertEquals(6f, SoundEffects(bass = 6f).responseDb(30f), 0.3f)
        assertEquals(-6f, SoundEffects(treble = -6f).responseDb(16000f), 0.4f)
        assertEquals(3f, SoundEffects(preamp = 3f).responseDb(440f), 0.01f)
        // A cut is −3 dB at its corner and much lower an octave past it.
        assertEquals(-3f, SoundEffects(lowCut = 100f).responseDb(100f), 0.1f)
        assertTrue(SoundEffects(highCut = 1000f).responseDb(4000f) < -20f)
    }

    @Test
    fun `presets stay in range and say what they do`() {
        SoundPresets.all.forEach { preset ->
            val e = preset.effects
            assertEquals(e, e.normalized().copy(preset = e.preset, edited = e.edited), preset.id)
            assertTrue(e.bands.all { abs(it) <= 7f }, preset.id)
        }
        assertTrue(SoundPresets.byId("flat")!!.effects.isFlat)
        assertTrue(SoundPresets.all.drop(1).none { it.effects.isFlat })

        val slowed = SoundPresets.byId("slowed")!!.effects
        assertEquals(listOf("speed", "reverb", "bass", "preamp"), slowed.notes().map { it.key })
        assertEquals("Slowed to 0.85×", slowed.notes().first().text)
        assertEquals(-2.81f, slowed.heardPitch, 0.01f)
    }

    @Test
    fun `off plays as recorded but keeps the settings, and odd values are pulled into range`() {
        val wild = SoundEffects(on = false, speed = 9f, pitch = -40f, bands = listOf(30f), lowCut = 5f, rotate = 1f)
        assertTrue(wild.effective().isFlat)
        val n = wild.normalized()
        assertEquals(2f, n.speed)
        assertEquals(-12f, n.pitch)
        assertEquals(10, n.bands.size)
        assertEquals(12f, n.bands[0])
        assertEquals(0f, n.lowCut)
        assertEquals(2f, n.rotate)
        assertFalse(SoundEffects(pitch = -2f).isFlat)
        assertTrue(SoundEffects(pitch = -2f, vinyl = true).isFlat)
    }

    @Test
    fun `notes are short and plain`() {
        val e = SoundEffects(pitch = -2f, echo = 0.4f, echoMs = 250f, echoRepeats = 0.5f, width = 0f, rotate = 8f, highCut = 2500f,
            bands = listOf(0f, 4f, 0f, 0f, 0f, 0f, -3f, 0f, 0f, 0f))
        val byKey = e.notes().associate { it.key to (it.text + " / " + it.words) }
        assertEquals("Pitch −2 semitones / deeper", byKey["pitch"])
        assertEquals("Echo every 250 ms / 40%, 5 repeats", byKey["echo"])
        assertEquals("Mono / both sides the same", byKey["width"])
        assertEquals("8D / one turn every 8 s", byKey["rotate"])
        assertEquals("Muffled above 2.5 kHz / as if through a wall", byKey["highCut"])
        assertEquals("EQ / +4 dB at 62 Hz, −3 dB at 2 kHz", byKey["eq"])
        assertTrue(SoundEffects().notes().isEmpty())
    }
}
