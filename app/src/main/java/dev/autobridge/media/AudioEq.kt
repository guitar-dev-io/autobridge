package dev.autobridge.media

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.core.content.edit
import dev.autobridge.logging.StructuredLog

/**
 * The player's equalizer: the driver's sound settings, stored, and applied to the shared player's
 * audio session through the platform's own effects ([Equalizer], [BassBoost], [LoudnessEnhancer]).
 *
 * One object for the process because there is one player ([MediaPlaybackService]) and every
 * screen that changes the sound — the phone's EQ sheet, the car's "change sound" button — lives
 * in the same process. A setting changed while nothing plays is stored and applied when the
 * service next attaches a session.
 *
 * Every effect call is guarded: some phones ship without one or other effect, or refuse a session,
 * and a missing equalizer must never stop the audio.
 */
object AudioEq {
    private const val TAG = "AudioEq"
    private const val PREFS = "autobridge_audio_eq"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRESET = "preset"
    private const val KEY_CUSTOM = "custom_"
    private const val KEY_BASS = "bass"
    private const val KEY_LEVEL = "level_volume"

    /** Bass boost strength range the platform takes (0..1000 permille). */
    const val BASS_MAX = 1_000

    /** How much the loudness levelling lifts a quiet station, in millibels. */
    private const val LEVEL_GAIN_MB = 600

    data class Settings(
        val enabled: Boolean,
        val preset: EqPreset,
        val custom: IntArray,
        val bass: Int,
        val levelVolume: Boolean,
    ) {
        /** The gains on the five reference bands that [preset] means right now. */
        val gains: IntArray get() = if (preset == EqPreset.CUSTOM) custom else preset.gains
    }

    private var appContext: Context? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var sessionId: Int = 0

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): Settings {
        val prefs = prefs(context)
        val preset = prefs.getString(KEY_PRESET, null)
            ?.let { name -> EqPreset.entries.firstOrNull { it.name == name } } ?: EqPreset.NORMAL
        return Settings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            preset = preset,
            custom = IntArray(EqMath.REFERENCE_HZ.size) { prefs.getInt(KEY_CUSTOM + it, 0) },
            bass = prefs.getInt(KEY_BASS, 0).coerceIn(0, BASS_MAX),
            levelVolume = prefs.getBoolean(KEY_LEVEL, false),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) = update(context) { putBoolean(KEY_ENABLED, enabled) }

    /** Choosing a preset also turns the equalizer on: picking a sound means wanting to hear it. */
    fun setPreset(context: Context, preset: EqPreset) = update(context) {
        putString(KEY_PRESET, preset.name)
        putBoolean(KEY_ENABLED, true)
    }

    /** A slider moved: the sound becomes the driver's own ([EqPreset.CUSTOM]), starting from what was on. */
    fun setBand(context: Context, band: Int, millibels: Int) {
        val current = settings(context)
        val start = current.gains
        update(context) {
            if (current.preset != EqPreset.CUSTOM) {
                start.forEachIndexed { index, gain -> putInt(KEY_CUSTOM + index, gain) }
            }
            putInt(KEY_CUSTOM + band, millibels.coerceIn(EqMath.MIN_MB, EqMath.MAX_MB))
            putString(KEY_PRESET, EqPreset.CUSTOM.name)
            putBoolean(KEY_ENABLED, true)
        }
    }

    fun resetCustom(context: Context) = update(context) {
        EqMath.REFERENCE_HZ.indices.forEach { putInt(KEY_CUSTOM + it, 0) }
        putString(KEY_PRESET, EqPreset.NORMAL.name)
    }

    fun setBass(context: Context, strength: Int) = update(context) {
        putInt(KEY_BASS, strength.coerceIn(0, BASS_MAX))
        putBoolean(KEY_ENABLED, true)
    }

    fun setLevelVolume(context: Context, on: Boolean) = update(context) { putBoolean(KEY_LEVEL, on) }

    /** The car's one button: the next preset, wrapping. Returns what is on now. */
    fun cyclePreset(context: Context): EqPreset {
        val next = EqPreset.next(activePreset(context))
        setPreset(context, next)
        return next
    }

    /** What the car's button says: the preset on, or Normal while the equalizer is off. */
    fun activePreset(context: Context): EqPreset {
        val current = settings(context)
        return if (current.enabled) current.preset else EqPreset.NORMAL
    }

    private fun update(context: Context, change: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(context).edit { change() }
        applyNow()
    }

    /** Called by the service for its player's audio session; replaces whatever was attached. */
    @Synchronized
    fun attach(context: Context, audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (audioSessionId == sessionId && equalizer != null) return
        detach()
        appContext = context.applicationContext
        sessionId = audioSessionId
        equalizer = runCatching { Equalizer(0, audioSessionId) }
            .onFailure { StructuredLog.w(TAG, "No equalizer on session $audioSessionId: ${it.message}") }.getOrNull()
        bassBoost = runCatching { BassBoost(0, audioSessionId) }
            .onFailure { StructuredLog.w(TAG, "No bass boost on session $audioSessionId: ${it.message}") }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(audioSessionId) }
            .onFailure { StructuredLog.w(TAG, "No loudness enhancer on session $audioSessionId: ${it.message}") }.getOrNull()
        applyNow()
    }

    @Synchronized
    fun detach() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        bassBoost = null
        loudness = null
        sessionId = 0
    }

    @Synchronized
    private fun applyNow() {
        val context = appContext ?: return
        val current = settings(context)
        equalizer?.let { eq ->
            runCatching {
                val bands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val centres = IntArray(bands) { eq.getCenterFreq(it.toShort()) / 1_000 }
                val levels = EqMath.levelsFor(current.gains, centres, range[0].toInt(), range[1].toInt())
                levels.forEachIndexed { band, level -> eq.setBandLevel(band.toShort(), level.toShort()) }
                eq.enabled = current.enabled
            }.onFailure { StructuredLog.w(TAG, "Equalizer refused the settings: ${it.message}") }
        }
        bassBoost?.let { boost ->
            runCatching {
                if (boost.strengthSupported) boost.setStrength(current.bass.toShort())
                boost.enabled = current.enabled && current.bass > 0
            }.onFailure { StructuredLog.w(TAG, "Bass boost refused the settings: ${it.message}") }
        }
        loudness?.let { level ->
            runCatching {
                level.setTargetGain(LEVEL_GAIN_MB)
                level.enabled = current.levelVolume
            }.onFailure { StructuredLog.w(TAG, "Loudness enhancer refused the settings: ${it.message}") }
        }
    }
}
