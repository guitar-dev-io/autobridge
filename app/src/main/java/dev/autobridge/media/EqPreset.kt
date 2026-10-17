package dev.autobridge.media

import androidx.annotation.StringRes
import dev.autobridge.R
import kotlin.math.abs
import kotlin.math.ln

/**
 * The equalizer's sound presets, as gains (millibels) on five reference bands. A device's own
 * equalizer has its own band count and centres, so [EqMath.levelsFor] maps these onto whatever
 * the phone offers. [CUSTOM] carries no gains of its own: it reads the driver's sliders.
 */
enum class EqPreset(@StringRes val labelRes: Int, val gains: IntArray) {
    NORMAL(R.string.eq_preset_normal, intArrayOf(0, 0, 0, 0, 0)),
    BASS(R.string.eq_preset_bass, intArrayOf(600, 400, 0, 0, 100)),
    VOICE(R.string.eq_preset_voice, intArrayOf(-200, -100, 300, 400, 100)),
    POP(R.string.eq_preset_pop, intArrayOf(-100, 200, 400, 200, -100)),
    LUK_THUNG(R.string.eq_preset_luk_thung, intArrayOf(300, 100, 200, 300, 100)),
    ROCK(R.string.eq_preset_rock, intArrayOf(500, 300, -100, 300, 500)),
    CUSTOM(R.string.eq_preset_custom, intArrayOf(0, 0, 0, 0, 0));

    companion object {
        /** The presets offered as one-tap chips (and cycled on the car): every one but [CUSTOM]. */
        val chips: List<EqPreset> get() = entries.filter { it != CUSTOM }

        /** The chip after [current], wrapping, for the car's single "change sound" button. */
        fun next(current: EqPreset): EqPreset {
            val list = chips
            val at = list.indexOf(current)
            return list[(at + 1).mod(list.size)]
        }
    }
}

/** Pure band arithmetic, kept off android.media.audiofx so it runs as a JVM test. */
object EqMath {
    /** The five reference band centres, in Hz, that [EqPreset.gains] and the sliders are on. */
    val REFERENCE_HZ = intArrayOf(60, 230, 910, 3_600, 14_000)

    /** The slider range, in millibels. */
    const val MIN_MB = -1_200
    const val MAX_MB = 1_200

    /**
     * Each device band's level: the reference band nearest it on a log scale supplies the gain,
     * clamped to what the device allows. [deviceCentresHz] are the device's own band centres.
     */
    fun levelsFor(gains: IntArray, deviceCentresHz: IntArray, minMb: Int, maxMb: Int): IntArray =
        IntArray(deviceCentresHz.size) { band ->
            val centre = deviceCentresHz[band].coerceAtLeast(1)
            val nearest = REFERENCE_HZ.indices.minByOrNull { abs(ln(centre.toDouble()) - ln(REFERENCE_HZ[it].toDouble())) } ?: 0
            gains.getOrElse(nearest) { 0 }.coerceIn(minMb, maxMb)
        }
}
