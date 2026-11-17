/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The equalizer's ten bands, in Hz: an octave apart, the way a mixing desk counts. */
val EQ_BANDS: List<Float> = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

/** How wide each band reaches: Q 1.41 is about one octave, so neighbours meet without a gap. */
const val EQ_Q = 1.41f

/** Where the bass and treble shelves turn. */
const val BASS_HZ = 100f
const val TREBLE_HZ = 8000f

/**
 * Everything Valen does to the sound, as one value: an equalizer, tone, time and space. The same
 * value drives FFmpeg's filters on the desktop and the Web Audio graph in Android's player pages,
 * and the notebook writes it down ([notes]). Out-of-range values are clamped by [normalized].
 */
@Serializable
data class SoundEffects(
    /** Off keeps every setting but plays the song as recorded. */
    val on: Boolean = true,
    /** The preset these settings started from, by [SoundPreset.id]; null when built by hand. */
    val preset: String? = null,
    /** Whether anything was changed after the preset was picked. */
    val edited: Boolean = false,
    /** Gain of each of [EQ_BANDS], in dB, −12 to +12. */
    val bands: List<Float> = FLAT_BANDS,
    /** Gain before everything else, in dB, −12 to +12. */
    val preamp: Float = 0f,
    /** Low shelf at [BASS_HZ] and high shelf at [TREBLE_HZ], in dB, −12 to +12. */
    val bass: Float = 0f,
    val treble: Float = 0f,
    /** Below this many Hz is cut (a high-pass); 0 is off, else 20 to 500. */
    val lowCut: Float = 0f,
    /** Above this many Hz is cut (a low-pass, "muffled"); 0 is off, else 500 to 20000. */
    val highCut: Float = 0f,
    /** How fast the song plays, 0.5× to 2×. */
    val speed: Float = 1f,
    /** Pitch in semitones, −12 to +12, kept apart from speed unless [vinyl]. */
    val pitch: Float = 0f,
    /** Pitch follows speed, as on a turntable: slowed is deeper, sped up is higher. */
    val vinyl: Boolean = false,
    /** How much of the room is heard, 0 to 1. */
    val reverb: Float = 0f,
    /** The room's size, 0 (a small room) to 1 (a cave). */
    val room: Float = 0.5f,
    /** The room's tail, 0 (dark) to 1 (bright). */
    val brightness: Float = 0.5f,
    /** How much echo is heard, 0 to 1. */
    val echo: Float = 0f,
    /** The time between echoes, 60 to 1200 ms. */
    val echoMs: Float = 320f,
    /** How much of each echo comes back again, 0 to 0.85. */
    val echoRepeats: Float = 0.35f,
    /** Stereo width: 0 is mono, 1 as recorded, 2 twice as wide. */
    val width: Float = 1f,
    /** "8D": seconds for the sound to turn once around the head; 0 is off, else 2 to 30. */
    val rotate: Float = 0f,
    /** How much loud and quiet are evened out (a compressor), 0 to 1. */
    val evenOut: Float = 0f,
    /** Linked peak protection; off uses fixed partial EQ gain compensation instead. */
    val peakLimiter: Boolean = true,
    /** Astra-compatible parametric EQ; replaces the graphic tone stage while enabled. */
    val parametricEq: ParametricEq = ParametricEq(),
) {
    /** Within every range, the band list ten long. */
    fun normalized(): SoundEffects = copy(
        bands = List(EQ_BANDS.size) { i -> bands.getOrElse(i) { 0f }.clean().coerceIn(-12f, 12f) },
        preamp = preamp.clean().coerceIn(-12f, 12f),
        bass = bass.clean().coerceIn(-12f, 12f),
        treble = treble.clean().coerceIn(-12f, 12f),
        lowCut = if (lowCut.clean() < 20f) 0f else lowCut.coerceIn(20f, 500f),
        highCut = if (highCut.clean() <= 0f || highCut >= 20000f) 0f else highCut.coerceIn(500f, 20000f),
        speed = speed.cleanOr(1f).coerceIn(0.5f, 2f),
        pitch = pitch.clean().coerceIn(-12f, 12f),
        reverb = reverb.clean().coerceIn(0f, 1f),
        room = room.cleanOr(0.5f).coerceIn(0f, 1f),
        brightness = brightness.cleanOr(0.5f).coerceIn(0f, 1f),
        echo = echo.clean().coerceIn(0f, 1f),
        echoMs = echoMs.cleanOr(320f).coerceIn(60f, 1200f),
        echoRepeats = echoRepeats.clean().coerceIn(0f, 0.85f),
        width = width.cleanOr(1f).coerceIn(0f, 2f),
        rotate = if (rotate.clean() <= 0f) 0f else rotate.coerceIn(2f, 30f),
        evenOut = evenOut.clean().coerceIn(0f, 1f),
        parametricEq = parametricEq.normalized(),
    )

    /** What a player is given: these settings, or nothing at all while they are off. */
    fun effective(): SoundEffects {
        if (!on) return FLAT
        val n = normalized()
        // The parametric EQ replaces the graphic tone stage. Keep the saved graphic
        // settings so disabling it restores them, but never cascade two equalizers.
        return if (n.parametricEq.enabled) n.copy(
            bands = List(EQ_BANDS.size) { 0f }, preamp = 0f, bass = 0f, treble = 0f,
            lowCut = 0f, highCut = 0f,
        ) else n
    }

    /** Nothing here changes the sound. */
    val isFlat: Boolean
        get() = normalized().let { n ->
            n.bands.all { abs(it) < 0.05f } && abs(n.preamp) < 0.05f && abs(n.bass) < 0.05f && abs(n.treble) < 0.05f &&
                n.lowCut == 0f && n.highCut == 0f && abs(n.speed - 1f) < 0.005f && (n.vinyl || abs(n.pitch) < 0.05f) &&
                n.reverb < 0.005f && n.echo < 0.005f && abs(n.width - 1f) < 0.01f && n.rotate == 0f && n.evenOut < 0.005f && !n.parametricEq.active
        }

    /** The pitch heard, in semitones: speed's own on a turntable, the dial's otherwise. */
    val heardPitch: Float get() = if (vinyl) (12.0 * ln(speed.toDouble()) / ln(2.0)).toFloat() else pitch

    /** The pitch heard as a frequency ratio. */
    val pitchRatio: Float get() = 2f.pow(heardPitch / 12f)

    /** The equalizer's shape changes the sound (any band, shelf, cut or preamp). */
    val shapesTone: Boolean
        get() = bands.any { abs(it) >= 0.05f } || abs(preamp) >= 0.05f || abs(bass) >= 0.05f || abs(treble) >= 0.05f ||
            lowCut > 0f || highCut > 0f || parametricEq.normalized().active

    /**
     * What the tone part does at [hz], in dB: every band, both shelves, both cuts and the preamp,
     * from the same filter designs the players use (the RBJ cookbook biquads), so the curve drawn
     * is the curve heard.
     */
    fun responseDb(hz: Float, sampleRate: Float = 48000f): Float {
        val n = effective()
        var db = n.preamp
        n.bands.forEachIndexed { i, g -> if (abs(g) >= 0.05f) db += Biquad.peaking(EQ_BANDS[i], EQ_Q, g, sampleRate).db(hz, sampleRate) }
        if (abs(n.bass) >= 0.05f) db += Biquad.lowShelf(BASS_HZ, n.bass, sampleRate).db(hz, sampleRate)
        if (abs(n.treble) >= 0.05f) db += Biquad.highShelf(TREBLE_HZ, n.treble, sampleRate).db(hz, sampleRate)
        if (n.lowCut > 0f) db += Biquad.highPass(n.lowCut, sampleRate).db(hz, sampleRate)
        if (n.highCut > 0f) db += Biquad.lowPass(n.highCut, sampleRate).db(hz, sampleRate)
        db += n.parametricEq.responseDb(hz, sampleRate)
        return db
    }

    /** Fixed EQ headroom, computed on settings changes, never from the playing signal.
     * The graph remains the requested tone curve; this is a separate output-level correction.
     * A frequency-response estimate reduces overload risk, not a transient peak guarantee.
     */
    fun outputHeadroomDb(sampleRate: Float = 48000f): Float {
        val n = effective()
        if (n.peakLimiter || !n.shapesTone) return 0f
        val upper = sampleRate * .49f
        var peak = 0f
        // Include exact filter centres as well as a dense logarithmic sweep for overlapping bands.
        for (i in 0..1024) {
            val hz = (10.0 * (upper / 10.0).pow(i / 1024.0)).toFloat()
            peak = maxOf(peak, n.responseDb(hz, sampleRate))
        }
        (EQ_BANDS + n.parametricEq.bands.map { it.frequency } + listOf(BASS_HZ, TREBLE_HZ)).forEach {
            if (it < upper) peak = maxOf(peak, n.responseDb(it, sampleRate))
        }
        // Partial compensation keeps some of the boost's impact. This is a fixed
        // level adjustment, not peak protection: hot masters may still clip.
        return if (peak > .05f) -peak * .5f else 0f
    }

    /** The settings as the notebook writes them down, one line per effect that is doing something. */
    fun notes(): List<SoundNote> {
        val n = normalized()
        return buildList {
            if (abs(n.speed - 1f) >= 0.005f) {
                val way = if (n.speed < 1f) "slower" else "faster"
                add(
                    if (n.vinyl) {
                        SoundNote("speed", "${if (n.speed < 1f) "Slowed" else "Sped up"} to ${n.speed.fixed(2)}×", "pitch goes with it")
                    } else {
                        SoundNote("speed", "Speed ${n.speed.fixed(2)}×", way)
                    },
                )
            }
            if (!n.vinyl && abs(n.pitch) >= 0.05f) add(SoundNote("pitch", "Pitch ${n.pitch.signed(1)} semitones", if (n.pitch < 0f) "deeper" else "higher"))
            if (n.reverb >= 0.005f) add(SoundNote("reverb", "Reverb ${n.reverb.percent()}", roomName(n.room) + brightnessWord(n.brightness)))
            if (n.echo >= 0.005f) add(SoundNote("echo", "Echo every ${n.echoMs.roundToInt()} ms", "${n.echo.percent()}, ${repeatsWord(n.echoRepeats)}"))
            if (abs(n.width - 1f) >= 0.01f) {
                add(
                    when {
                        n.width < 0.02f -> SoundNote("width", "Mono", "both sides the same")
                        n.width < 1f -> SoundNote("width", "Narrow ${n.width.percent()}", "closer together")
                        else -> SoundNote("width", "Wide ${n.width.percent()}", "further apart")
                    },
                )
            }
            if (n.rotate > 0f) add(SoundNote("rotate", "8D", "one turn every ${n.rotate.roundToInt()} s"))
            if (abs(n.bass) >= 0.05f) add(SoundNote("bass", "Bass ${n.bass.signed(1)} dB", if (n.bass > 0f) "fuller" else "lighter"))
            if (abs(n.treble) >= 0.05f) add(SoundNote("treble", "Treble ${n.treble.signed(1)} dB", if (n.treble > 0f) "brighter" else "softer"))
            if (n.lowCut > 0f) add(SoundNote("lowCut", "Low cut ${hertz(n.lowCut)}", "less rumble"))
            if (n.highCut > 0f) add(SoundNote("highCut", "Muffled above ${hertz(n.highCut)}", "as if through a wall"))
            if (n.evenOut >= 0.005f) add(SoundNote("evenOut", "Evened out ${n.evenOut.percent()}", "quiet parts up, loud parts down"))
            if (n.bands.any { abs(it) >= 0.05f }) add(SoundNote("eq", "EQ", eqWords(n.bands)))
            if (n.parametricEq.active) add(SoundNote("parametricEq", "Parametric EQ", "${n.parametricEq.bands.count { it.active }} filters"))
            if (abs(n.preamp) >= 0.05f) add(SoundNote("preamp", "Preamp ${n.preamp.signed(1)} dB", if (n.preamp > 0f) "louder in" else "headroom"))
        }
    }

    companion object {
        val FLAT_BANDS: List<Float> = List(EQ_BANDS.size) { 0f }
        val FLAT = SoundEffects()

        /** "Small room", "Studio", "Hall", "Church", "Cave" by size. */
        fun roomName(room: Float): String = when {
            room < 0.2f -> "small room"
            room < 0.4f -> "studio"
            room < 0.6f -> "hall"
            room < 0.8f -> "church"
            else -> "cave"
        }

        /** A band's frequency as it is said: "62 Hz", "2 kHz". */
        fun hertz(hz: Float): String = if (hz >= 1000f) {
            val k = hz / 1000f
            if (abs(k - k.roundToInt()) < 0.05f) "${k.roundToInt()} kHz" else "${k.fixed(1)} kHz"
        } else {
            "${hz.roundToInt()} Hz"
        }
    }
}

/** One line of the notebook: which effect ([key], the same while it changes), what, and a few words. */
@Serializable
data class SoundNote(val key: String, val text: String, val words: String)

/** A named starting point: a few words about it, and the settings. */
data class SoundPreset(val id: String, val name: String, val words: String, val effects: SoundEffects)

/**
 * The presets, from the plain to the strange. Equalizer curves are gentle on purpose (no band
 * past ±7 dB) so a preset never clips a loud master; the preamp makes room where they lift.
 */
object SoundPresets {
    private fun eq(vararg db: Float) = db.toList()

    val all: List<SoundPreset> = listOf(
        SoundPreset("flat", "Flat", "as recorded", SoundEffects()),
        SoundPreset("bass", "Bass boost", "low end up", SoundEffects(bands = eq(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), preamp = -3f)),
        SoundPreset("vocal", "Vocal", "voices forward", SoundEffects(bands = eq(-2f, -2f, -1f, 0f, 2f, 3.5f, 3.5f, 2f, 0f, -1f), lowCut = 80f, preamp = -2f)),
        SoundPreset("bright", "Bright", "more air", SoundEffects(bands = eq(0f, 0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f), preamp = -2.5f)),
        SoundPreset("warm", "Warm", "soft top, full low", SoundEffects(bands = eq(3f, 3f, 2f, 1f, 0f, 0f, -1f, -2f, -3f, -4f), preamp = -1.5f)),
        SoundPreset("acoustic", "Acoustic", "wood and strings", SoundEffects(bands = eq(2f, 2f, 1.5f, 1f, 0f, 0.5f, 1.5f, 2f, 2.5f, 1.5f), preamp = -2f)),
        SoundPreset("rock", "Rock", "punch and bite", SoundEffects(bands = eq(4f, 3f, 1f, -1f, -2f, -1f, 1.5f, 3f, 4f, 4f), preamp = -3f)),
        SoundPreset("hiphop", "Hip-hop", "deep kick, clear words", SoundEffects(bands = eq(5f, 4.5f, 2f, 0f, -1f, 0f, 1.5f, 1f, 1.5f, 2f), preamp = -3f)),
        SoundPreset("electronic", "Electronic", "sub and sparkle", SoundEffects(bands = eq(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f), width = 1.2f, preamp = -3f)),
        SoundPreset(
            "slowed", "Slowed + reverb", "slower, deeper, wet",
            SoundEffects(speed = 0.85f, vinyl = true, reverb = 0.38f, room = 0.7f, brightness = 0.35f, bass = 2f, preamp = -1.5f),
        ),
        SoundPreset("nightcore", "Nightcore", "faster and higher", SoundEffects(speed = 1.25f, vinyl = true, treble = 1.5f)),
        SoundPreset("hall", "Concert hall", "a big room around it", SoundEffects(reverb = 0.32f, room = 0.62f, brightness = 0.55f, width = 1.15f)),
        SoundPreset("eightd", "8D", "turns around your head", SoundEffects(rotate = 10f, reverb = 0.18f, room = 0.45f, width = 1.3f)),
        SoundPreset("lofi", "Lo-fi", "dusty tape, narrow", SoundEffects(highCut = 4500f, lowCut = 110f, width = 0.6f, echo = 0.12f, echoMs = 180f, echoRepeats = 0.2f, evenOut = 0.35f, speed = 0.96f, vinyl = true)),
        SoundPreset("underwater", "Underwater", "far away, muffled", SoundEffects(highCut = 700f, reverb = 0.3f, room = 0.55f, brightness = 0.2f, bass = 3f, preamp = -2f)),
        SoundPreset("night", "Late night", "even, soft, quiet", SoundEffects(evenOut = 0.6f, treble = -2f, bass = -1f, preamp = -2f)),
    )

    fun byId(id: String?): SoundPreset? = all.firstOrNull { it.id == id }
}

/**
 * A second-order filter from the RBJ Audio EQ cookbook: the shapes FFmpeg's `equalizer`, `bass`,
 * `treble`, `highpass`, `lowpass` and Web Audio's `BiquadFilterNode` both draw from, so the curve
 * Valen draws is the one each player applies.
 */
internal class Biquad(val b0: Double, val b1: Double, val b2: Double, val a0: Double, val a1: Double, val a2: Double) {
    /** The gain at [hz], in dB. */
    fun db(hz: Float, sampleRate: Float): Float {
        val w = 2.0 * PI * hz / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -(b1 * s1 + b2 * s2)
        val dr = a0 + a1 * c1 + a2 * c2
        val di = -(a1 * s1 + a2 * s2)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return (20.0 * log10(mag.coerceAtLeast(1e-9))).toFloat()
    }

    companion object {
        private fun omega(hz: Float, sr: Float) = 2.0 * PI * hz.coerceAtMost(sr * 0.49f) / sr

        fun peaking(hz: Float, q: Float, gain: Float, sr: Float): Biquad {
            val a = 10.0.pow(gain / 40.0)
            val w = omega(hz, sr)
            val alpha = sin(w) / (2 * q)
            return Biquad(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
        }

        /** Shelf slope S = 1 (Q 0.707): the shelf Web Audio uses and FFmpeg's bass/treble at w=0.707. */
        fun lowShelf(hz: Float, gain: Float, sr: Float): Biquad {
            val a = 10.0.pow(gain / 40.0)
            val w = omega(hz, sr)
            val alpha = sin(w) / 2 * sqrt(2.0)
            val c = cos(w)
            val sa = 2 * sqrt(a) * alpha
            return Biquad(
                a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa,
            )
        }

        fun highShelf(hz: Float, gain: Float, sr: Float): Biquad {
            val a = 10.0.pow(gain / 40.0)
            val w = omega(hz, sr)
            val alpha = sin(w) / 2 * sqrt(2.0)
            val c = cos(w)
            val sa = 2 * sqrt(a) * alpha
            return Biquad(
                a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa,
            )
        }

        /** Butterworth (Q 0.707). */
        fun highPass(hz: Float, sr: Float): Biquad {
            val w = omega(hz, sr)
            val alpha = sin(w) / (2 * 0.7071)
            val c = cos(w)
            return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun lowPass(hz: Float, sr: Float): Biquad {
            val w = omega(hz, sr)
            val alpha = sin(w) / (2 * 0.7071)
            val c = cos(w)
            return Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
    }
}

// ── Words and numbers, without java.text (core is common code) ──

private fun Float.clean(): Float = if (isNaN() || isInfinite()) 0f else this
private fun Float.cleanOr(fallback: Float): Float = if (isNaN() || isInfinite()) fallback else this

/** [decimals] places, trailing zeros kept: 0.85, 1.20. */
internal fun Float.fixed(decimals: Int): String {
    val scale = 10.0.pow(decimals)
    val n = (abs(this) * scale).roundToInt()
    val whole = (n / scale.toInt()).toString()
    val sign = if (this < 0f && n != 0) "−" else ""
    if (decimals == 0) return sign + whole
    val frac = (n % scale.toInt()).toString().padStart(decimals, '0')
    return "$sign$whole.$frac"
}

/** +4.5, −3, with the typographic minus; a whole number drops its ".0". */
internal fun Float.signed(decimals: Int): String {
    val text = fixed(decimals).removePrefix("−").let { if (it.endsWith(".0")) it.dropLast(2) else it }
    return if (this < 0f) "−$text" else "+$text"
}

private fun Float.percent(): String = "${(this * 100).roundToInt()}%"

private fun brightnessWord(b: Float): String = when {
    b < 0.3f -> ", dark"
    b > 0.7f -> ", bright"
    else -> ""
}

private fun repeatsWord(f: Float): String {
    // How many repeats are heard before they fall under −30 dB.
    val count = if (f < 0.02f) 1 else (ln(0.0316) / ln(f.toDouble())).roundToInt().coerceIn(1, 12)
    return if (count == 1) "one repeat" else "$count repeats"
}

/** "+4 dB at 62 Hz, −3 dB at 2 kHz": the biggest lift and the biggest cut. */
private fun eqWords(bands: List<Float>): String {
    val up = bands.indices.maxByOrNull { bands[it] }?.takeIf { bands[it] >= 0.05f }
    val down = bands.indices.minByOrNull { bands[it] }?.takeIf { bands[it] <= -0.05f }
    return listOfNotNull(
        up?.let { "${bands[it].signed(1)} dB at ${SoundEffects.hertz(EQ_BANDS[it])}" },
        down?.let { "${bands[it].signed(1)} dB at ${SoundEffects.hertz(EQ_BANDS[it])}" },
    ).joinToString(", ")
}
