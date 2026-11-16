/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val PARAMETRIC_EQ_MAX_BANDS = 20

@Serializable
enum class EqFilterType {
    @SerialName("peaking") PEAKING,
    @SerialName("lowshelf") LOW_SHELF,
    @SerialName("highshelf") HIGH_SHELF,
    @SerialName("highpass") HIGH_PASS,
    @SerialName("lowpass") LOW_PASS,
}

@Serializable
data class ParametricBand(
    val type: EqFilterType = EqFilterType.PEAKING,
    val frequency: Float = 1000f,
    val gain: Float = 0f,
    @SerialName("Q") val q: Float = 1f,
) {
    fun normalized(): ParametricBand = copy(
        frequency = frequency.finiteOr(1000f).coerceIn(20f, 20000f),
        gain = if (type == EqFilterType.HIGH_PASS || type == EqFilterType.LOW_PASS) 0f else gain.finiteOr(0f).coerceIn(-12f, 12f),
        q = q.finiteOr(1f).coerceIn(0.1f, 18f),
    )

    val active: Boolean get() = type == EqFilterType.HIGH_PASS || type == EqFilterType.LOW_PASS || gain != 0f

    /** Astra's native RBJ coefficients, before normalization by a0. */
    fun coefficients(sampleRate: Float): EqCoefficients {
        require(sampleRate.isFinite() && sampleRate >= 100f)
        val b = normalized()
        val w = 2 * PI * b.frequency.coerceAtMost(sampleRate * 0.49f) / sampleRate
        val c = cos(w)
        val alpha = sin(w) / (2 * b.q)
        val a = 10.0.pow(b.gain / 40.0)
        val sa = 2 * sqrt(a) * alpha
        return when (b.type) {
            EqFilterType.PEAKING -> EqCoefficients(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            EqFilterType.LOW_SHELF -> EqCoefficients(
                a * ((a + 1) - (a - 1) * c + sa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sa),
                (a + 1) + (a - 1) * c + sa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sa,
            )
            EqFilterType.HIGH_SHELF -> EqCoefficients(
                a * ((a + 1) + (a - 1) * c + sa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sa),
                (a + 1) - (a - 1) * c + sa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sa,
            )
            EqFilterType.HIGH_PASS -> EqCoefficients((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            EqFilterType.LOW_PASS -> EqCoefficients((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
    }
}

data class EqCoefficients(val b0: Double, val b1: Double, val b2: Double, val a0: Double, val a1: Double, val a2: Double) {
    fun responseDb(hz: Float, sampleRate: Float): Float = Biquad(b0, b1, b2, a0, a1, a2).db(hz, sampleRate)
}

@Serializable
data class ParametricEq(val enabled: Boolean = false, val preamp: Float = 0f, val bands: List<ParametricBand> = emptyList()) {
    fun normalized() = copy(preamp = preamp.finiteOr(0f).coerceIn(-12f, 12f), bands = bands.take(PARAMETRIC_EQ_MAX_BANDS).map { it.normalized() })
    val active: Boolean get() = enabled && (preamp != 0f || bands.any { it.active })
    fun responseDb(hz: Float, sampleRate: Float = 48000f): Float {
        val eq = normalized()
        return if (!eq.enabled) 0f else eq.preamp + eq.bands.filter { it.active }.sumOf { it.coefficients(sampleRate).responseDb(hz, sampleRate).toDouble() }.toFloat()
    }
}

@Serializable
data class ParametricPreset(val name: String, val preamp: Float = 0f, val bands: List<ParametricBand> = emptyList()) {
    fun applyTo(effects: SoundEffects): SoundEffects = effects.copy(parametricEq = ParametricEq(true, preamp, bands).normalized())
}

/** Saved user presets and output-specific EQ settings, kept separately from the current sound. */
@Serializable
data class ParametricEqLibrary(
    val presets: Map<String, ParametricPreset> = emptyMap(),
    val outputProfiles: Map<String, ParametricEq> = emptyMap(),
)

/** Astra preset JSON and AutoEQ text interchange. */
object ParametricPresets {
    private fun band(type: EqFilterType, hz: Float, gain: Float, q: Float = 1f) = ParametricBand(type, hz, gain, q)
    private val low = EqFilterType.LOW_SHELF
    private val peak = EqFilterType.PEAKING
    private val high = EqFilterType.HIGH_SHELF
    val all = listOf(
        ParametricPreset("Flat", bands = listOf(band(low, 60f, 0f, .707f), band(peak, 250f, 0f), band(peak, 1000f, 0f), band(peak, 4000f, 0f), band(high, 12000f, 0f, .707f))),
        ParametricPreset("Bass Boost", -2f, listOf(band(low, 60f, 6f, .707f), band(peak, 150f, 4f), band(peak, 400f, 1f), band(peak, 1000f, 0f), band(high, 12000f, 0f, .707f))),
        ParametricPreset("Treble Boost", -2f, listOf(band(low, 60f, 0f, .707f), band(peak, 1000f, 0f), band(peak, 4000f, 3f), band(peak, 8000f, 5f), band(high, 12000f, 6f, .707f))),
        ParametricPreset("Vocal", -1f, listOf(band(low, 80f, -2f, .707f), band(peak, 250f, 1f), band(peak, 1500f, 4f, 1.2f), band(peak, 4000f, 3f), band(high, 12000f, 1f, .707f))),
        ParametricPreset("Loudness", -3f, listOf(band(low, 60f, 5f, .707f), band(peak, 400f, 2f), band(peak, 1000f, -1f), band(peak, 4000f, 2f), band(high, 12000f, 5f, .707f))),
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun serialize(preset: ParametricPreset): String {
        val eq = ParametricEq(true, preset.preamp, preset.bands).normalized()
        return buildJsonObject {
            put("version", 1); put("name", preset.name); put("preamp", eq.preamp)
            put("bands", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ParametricBand.serializer()), eq.bands))
        }.toString()
    }

    fun parse(content: String): ParametricPreset {
        val root = json.parseToJsonElement(content) as? JsonObject ?: error("Invalid preset file")
        val name = (root["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val bands = root["bands"] as? JsonArray ?: error("Invalid preset file")
        require(name.isNotEmpty()) { "Invalid preset file" }
        fun number(obj: JsonObject, key: String, fallback: Float): Float = (obj[key] as? JsonPrimitive)?.floatOrNull?.finiteOr(fallback) ?: fallback
        val parsed = bands.take(PARAMETRIC_EQ_MAX_BANDS).map { item ->
            val obj = item as? JsonObject ?: JsonObject(emptyMap())
            val type = when ((obj["type"] as? JsonPrimitive)?.content) {
                "lowshelf" -> low; "highshelf" -> high; "highpass" -> EqFilterType.HIGH_PASS; "lowpass" -> EqFilterType.LOW_PASS; else -> peak
            }
            ParametricBand(type, number(obj, "frequency", 1000f), number(obj, "gain", 0f), number(obj, "Q", 1f)).normalized()
        }
        return ParametricPreset(name, number(root, "preamp", 0f).coerceIn(-12f, 12f), parsed)
    }

    fun parseAutoEq(content: String, filename: String? = null): ParametricPreset {
        val num = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"
        val pre = Regex("^Preamp:\\s*($num)\\s*dB", RegexOption.IGNORE_CASE)
        val filter = Regex("^Filter\\s+\\d+:\\s*(ON|OFF)\\s+(PK|LS|HS)\\s+Fc\\s+($num)\\s*Hz\\s+Gain\\s+($num)\\s*dB\\s+Q\\s+($num)", RegexOption.IGNORE_CASE)
        var preamp = 0f
        val bands = mutableListOf<ParametricBand>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            pre.find(line)?.let { preamp = (it.groupValues[1].toFloatOrNull() ?: 0f).finiteOr(0f).coerceIn(-12f, 12f) }
            filter.find(line)?.let { m ->
                if (m.groupValues[1].equals("ON", true)) {
                    val type = when (m.groupValues[2].uppercase()) { "LS" -> low; "HS" -> high; else -> peak }
                    bands += ParametricBand(type, m.groupValues[3].toFloatOrNull() ?: 1000f, m.groupValues[4].toFloatOrNull() ?: 0f, m.groupValues[5].toFloatOrNull() ?: 1f).normalized()
                }
            }
        }
        val name = filename?.substringAfterLast('/')?.substringAfterLast('\\')?.replace(Regex("\\.[^.]+$"), "") ?: "Imported AutoEQ"
        return ParametricPreset(name, preamp, bands.sortedBy { it.frequency }.take(PARAMETRIC_EQ_MAX_BANDS))
    }
}

private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
