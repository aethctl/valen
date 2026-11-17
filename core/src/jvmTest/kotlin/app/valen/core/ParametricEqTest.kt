/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ParametricEqTest {
    @Test
    fun `headroom compensates boosts without amplifying cuts or doubling manual attenuation`() {
        val boost = SoundEffects(peakLimiter = false, parametricEq = ParametricEq(true,
            bands = listOf(ParametricBand(frequency = 1000f, gain = 6f))))
        assertEquals(-3f, boost.outputHeadroomDb(), .05f)
        assertEquals(0f, boost.copy(peakLimiter = true).outputHeadroomDb())
        assertEquals(0f, boost.copy(parametricEq = boost.parametricEq.copy(preamp = -8f)).outputHeadroomDb())
        assertEquals(0f, SoundEffects(peakLimiter = false).outputHeadroomDb())
    }
    @Test
    fun `parametric playback replaces saved graphic tone without erasing it`() {
        val eq = ParametricEq(true, bands = listOf(ParametricBand(frequency = 1000f, gain = 3f)))
        val saved = SoundEffects(preamp = -6f, bass = 8f, bands = List(EQ_BANDS.size) { 4f }, parametricEq = eq)
        assertEquals(3f, saved.responseDb(1000f), .01f)
        assertEquals(0f, saved.effective().preamp)
        assertEquals(-6f, saved.preamp)
        assertEquals(-6f, saved.copy(parametricEq = eq.copy(enabled = false)).effective().preamp)
    }
    @Test
    fun `normalization limits all fields and pass filters have no gain`() {
        val eq = ParametricEq(true, Float.NaN, listOf(ParametricBand(EqFilterType.HIGH_PASS, Float.NaN, 10f, Float.POSITIVE_INFINITY)) + List(25) {
            ParametricBand(frequency = 50000f, gain = -50f, q = 0f)
        }).normalized()
        assertEquals(20, eq.bands.size)
        assertEquals(0f, eq.preamp)
        assertEquals(ParametricBand(EqFilterType.HIGH_PASS, 1000f, 0f, 1f), eq.bands.first())
        assertEquals(ParametricBand(frequency = 20000f, gain = -12f, q = .1f), eq.bands.last())
    }

    @Test
    fun `all five filters follow native biquad response and a boost is not a cut elsewhere`() {
        fun response(type: EqFilterType, frequency: Float, gain: Float, q: Float, at: Float) =
            ParametricBand(type, frequency, gain, q).coefficients(48000f).responseDb(at, 48000f)
        assertEquals(6f, response(EqFilterType.PEAKING, 1000f, 6f, 1f, 1000f), .01f)
        assertTrue(response(EqFilterType.PEAKING, 1000f, 6f, 1f, 250f) > 0f)
        assertEquals(3f, response(EqFilterType.LOW_SHELF, 1000f, 6f, 2f, 1000f), .01f)
        assertEquals(3f, response(EqFilterType.HIGH_SHELF, 1000f, 6f, 2f, 1000f), .01f)
        assertEquals(6.0206f, response(EqFilterType.LOW_PASS, 1000f, 0f, 2f, 1000f), .01f)
        assertEquals(6.0206f, response(EqFilterType.HIGH_PASS, 1000f, 0f, 2f, 1000f), .01f)
        assertNotEquals(response(EqFilterType.LOW_SHELF, 1000f, 6f, .707f, 600f), response(EqFilterType.LOW_SHELF, 1000f, 6f, 2f, 600f), .5f)
    }

    @Test
    fun `preset interchange autoeq and persistence retain independent effects`() {
        val imported = ParametricPresets.parseAutoEq("""
            Preamp: -6.2 dB
            Filter 1: ON PK Fc 1000 Hz Gain +4.5 dB Q 1.41
            Filter 2: OFF HS Fc 12000 Hz Gain 5 dB Q 1
            Filter 3: ON LS Fc 31 Hz Gain 3 dB Q 0.71
        """.trimIndent(), "path/headphones.txt")
        assertEquals("headphones", imported.name)
        assertEquals(-6.2f, imported.preamp)
        assertEquals(listOf(EqFilterType.LOW_SHELF, EqFilterType.PEAKING), imported.bands.map { it.type })
        assertEquals(imported, ParametricPresets.parse(ParametricPresets.serialize(imported)))
        val source = SoundEffects(reverb = .4f, bass = 3f, bands = listOf(1f))
        val result = imported.applyTo(source)
        assertEquals(source.reverb, result.reverb)
        assertEquals(source.bands, result.bands)
        assertEquals(source.bass, result.bass)
        val settings = AppSettings(sound = result, parametricEqLibrary = ParametricEqLibrary(mapOf("saved" to imported), mapOf("headphones" to result.parametricEq)))
        assertEquals(settings, Json.decodeFromString<AppSettings>(Json.encodeToString(settings)))
        assertFalse(result.isFlat)
        assertEquals(0f, result.copy(on = false).effective().parametricEq.responseDb(1000f))
        assertEquals(SoundEffects().parametricEq, Json.decodeFromString<SoundEffects>("{}").parametricEq)
    }

    @Test
    fun `malformed preset values fall back and are clamped like Astra`() {
        val p = ParametricPresets.parse("""{"name":" Test ","preamp":50,"bands":[null,{"type":"unknown","frequency":100000,"gain":-15,"Q":0}]}""")
        assertEquals("Test", p.name)
        assertEquals(12f, p.preamp)
        assertEquals(ParametricBand(), p.bands.first())
        assertEquals(ParametricBand(frequency = 20000f, gain = -12f, q = .1f), p.bands.last())
        assertThrows(IllegalArgumentException::class.java) { ParametricPresets.parse("""{"name":" ","bands":[]}""") }
    }
}
