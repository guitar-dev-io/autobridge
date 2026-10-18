package dev.autobridge.media

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.core.content.edit
import dev.autobridge.logging.StructuredLog

/**
 * The sound effects: the driver's settings, stored, and applied through the platform's own effects
 * ([Equalizer], [BassBoost], [Virtualizer], [LoudnessEnhancer], and [DynamicsProcessing] for
 * levelling) to every audio session they reach.
 *
 * Sessions come from owners: the shared player ([MediaPlaybackService], ExoPlayer or VLC) under
 * [OWNER_PLAYER], and — while [AudioEffectsService] runs — other apps' players under
 * `app:<package>`. [EffectScope] picks which of them get effects, or the whole output mix instead
 * ([EffectTargets.sessions]). One object for the process, because every screen that changes the
 * sound lives in it; a setting changed while nothing plays is stored and applied on the next attach.
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
    private const val KEY_VIRTUALIZER = "virtualizer"
    private const val KEY_BOOST = "boost_mb"
    private const val KEY_SCOPE = "scope"
    private const val KEY_NORMALIZE = "normalize"
    /** The old on/off levelling switch; read once as a 6 dB boost when no boost is stored. */
    private const val KEY_LEVEL = "level_volume"
    private const val LEGACY_LEVEL_MB = 600

    /** The shared player's session, whichever engine it runs on. */
    const val OWNER_PLAYER = "player"

    /** Bass boost and virtualizer strength range the platform takes (0..1000 permille). */
    const val BASS_MAX = 1_000
    const val VIRTUALIZER_MAX = 1_000

    /** Volume boost ceiling, in millibels: +15 dB is loud without clipping most music to mush. */
    const val BOOST_MAX_MB = 1_500

    data class Settings(
        val enabled: Boolean,
        val preset: EqPreset,
        val custom: IntArray,
        val bass: Int,
        val virtualizer: Int,
        val boostMb: Int,
        val scope: EffectScope,
        /** Level stations to one loudness: a compressor with make-up gain, then a limiter. */
        val normalize: Boolean = false,
    ) {
        /** The gains on the five reference bands that [preset] means right now. */
        val gains: IntArray get() = if (preset == EqPreset.CUSTOM) custom else preset.gains
    }

    /** One session's effects; any of them may be missing on a phone that lacks it. */
    private class Chain(
        val equalizer: Equalizer?,
        val bassBoost: BassBoost?,
        val virtualizer: Virtualizer?,
        val loudness: LoudnessEnhancer?,
        val leveller: DynamicsProcessing? = null,
    ) {
        fun release() {
            runCatching { leveller?.release() }
            runCatching { equalizer?.release() }
            runCatching { bassBoost?.release() }
            runCatching { virtualizer?.release() }
            runCatching { loudness?.release() }
        }
    }

    private var appContext: Context? = null
    private val owners = mutableMapOf<String, Int>()
    private val chains = mutableMapOf<Int, Chain>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): Settings {
        val prefs = prefs(context)
        val preset = prefs.getString(KEY_PRESET, null)
            ?.let { name -> EqPreset.entries.firstOrNull { it.name == name } } ?: EqPreset.NORMAL
        val boost = if (prefs.contains(KEY_BOOST)) prefs.getInt(KEY_BOOST, 0)
        else if (prefs.getBoolean(KEY_LEVEL, false)) LEGACY_LEVEL_MB else 0
        return Settings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            preset = preset,
            custom = IntArray(EqMath.REFERENCE_HZ.size) { prefs.getInt(KEY_CUSTOM + it, 0) },
            bass = prefs.getInt(KEY_BASS, 0).coerceIn(0, BASS_MAX),
            virtualizer = prefs.getInt(KEY_VIRTUALIZER, 0).coerceIn(0, VIRTUALIZER_MAX),
            boostMb = boost.coerceIn(0, BOOST_MAX_MB),
            scope = prefs.getString(KEY_SCOPE, null)
                ?.let { name -> EffectScope.entries.firstOrNull { it.name == name } } ?: EffectScope.APP,
            normalize = prefs.getBoolean(KEY_NORMALIZE, false),
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

    fun setVirtualizer(context: Context, strength: Int) = update(context) {
        putInt(KEY_VIRTUALIZER, strength.coerceIn(0, VIRTUALIZER_MAX))
        putBoolean(KEY_ENABLED, true)
    }

    fun setBoost(context: Context, millibels: Int) = update(context) {
        putInt(KEY_BOOST, millibels.coerceIn(0, BOOST_MAX_MB))
        putBoolean(KEY_ENABLED, true)
    }

    fun setNormalize(context: Context, on: Boolean) = update(context) {
        putBoolean(KEY_NORMALIZE, on)
        if (on) putBoolean(KEY_ENABLED, true)
    }

    /** Wider than [EffectScope.APP] needs [AudioEffectsService] running; the caller starts or stops it. */
    fun setScope(context: Context, scope: EffectScope) {
        prefs(context).edit { putString(KEY_SCOPE, scope.name) }
        appContext = context.applicationContext
        reconcile()
    }

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
        appContext = context.applicationContext
        applyNow()
    }

    /** The shared player's session; replaces whatever the player had attached before. */
    fun attach(context: Context, audioSessionId: Int) = attach(context, OWNER_PLAYER, audioSessionId)

    fun detach() = detach(OWNER_PLAYER)

    /** [owner] now plays on [audioSessionId]; its previous session, if another, is let go. */
    @Synchronized
    fun attach(context: Context, owner: String, audioSessionId: Int) {
        if (audioSessionId <= 0) return
        appContext = context.applicationContext
        if (owners[owner] == audioSessionId && chains.containsKey(audioSessionId)) return
        owners[owner] = audioSessionId
        reconcile()
    }

    @Synchronized
    fun detach(owner: String) {
        if (owners.remove(owner) != null) reconcile()
    }

    /** Every other app's session goes: [AudioEffectsService] stopped, so nothing announces their closing. */
    @Synchronized
    fun detachExternal() {
        owners.keys.removeAll { it.startsWith(EffectTargets.EXTERNAL_PREFIX) }
        reconcile()
    }

    /** Holds effects on exactly the sessions the scope reaches, then applies the settings to them. */
    @Synchronized
    private fun reconcile() {
        val context = appContext ?: return
        val wanted = EffectTargets.sessions(settings(context).scope, owners)
        chains.keys.filter { it !in wanted }.forEach { session -> chains.remove(session)?.release() }
        wanted.filter { it !in chains }.forEach { session -> chains[session] = open(session) }
        applyNow()
    }

    private fun open(session: Int): Chain {
        fun <T> make(name: String, build: () -> T): T? = runCatching(build)
            .onFailure { StructuredLog.w(TAG, "No $name on session $session: ${it.message}") }.getOrNull()
        StructuredLog.i(TAG, "Effects on session $session")
        return Chain(
            equalizer = make("equalizer") { Equalizer(0, session) },
            bassBoost = make("bass boost") { BassBoost(0, session) },
            virtualizer = make("virtualizer") { Virtualizer(0, session) },
            loudness = make("loudness enhancer") { LoudnessEnhancer(session) },
            leveller = make("dynamics processing") { leveller(session) },
        )
    }

    /**
     * The "same loudness on every station" chain: one full-band compressor that pulls loud stations
     * down and, with its make-up gain, quiet ones up, then a limiter so the gain never clips.
     */
    private fun leveller(session: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            false, 0, true, 1, false, 0, true
        ).build()
        val processing = DynamicsProcessing(0, session, config)
        processing.setMbcBandAllChannelsTo(0, DynamicsProcessing.MbcBand(
            true, 20_000f, LEVEL_ATTACK_MS, LEVEL_RELEASE_MS, LEVEL_RATIO, LEVEL_THRESHOLD_DB,
            LEVEL_KNEE_DB, -90f, 1f, 0f, LEVEL_MAKEUP_DB
        ))
        processing.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(
            true, true, 0, 1f, 60f, 10f, LIMIT_THRESHOLD_DB, 0f
        ))
        processing.enabled = false
        return processing
    }

    private const val LEVEL_ATTACK_MS = 20f
    private const val LEVEL_RELEASE_MS = 300f
    private const val LEVEL_RATIO = 4f
    private const val LEVEL_THRESHOLD_DB = -24f
    private const val LEVEL_KNEE_DB = 6f
    private const val LEVEL_MAKEUP_DB = 8f
    private const val LIMIT_THRESHOLD_DB = -2f

    @Synchronized
    private fun applyNow() {
        val context = appContext ?: return
        val current = settings(context)
        chains.values.forEach { apply(it, current) }
    }

    private fun apply(chain: Chain, current: Settings) {
        chain.equalizer?.let { eq ->
            runCatching {
                val bands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val centres = IntArray(bands) { eq.getCenterFreq(it.toShort()) / 1_000 }
                val levels = EqMath.levelsFor(current.gains, centres, range[0].toInt(), range[1].toInt())
                levels.forEachIndexed { band, level -> eq.setBandLevel(band.toShort(), level.toShort()) }
                eq.enabled = current.enabled
            }.onFailure { StructuredLog.w(TAG, "Equalizer refused the settings: ${it.message}") }
        }
        chain.bassBoost?.let { boost ->
            runCatching {
                if (boost.strengthSupported) boost.setStrength(current.bass.toShort())
                boost.enabled = current.enabled && current.bass > 0
            }.onFailure { StructuredLog.w(TAG, "Bass boost refused the settings: ${it.message}") }
        }
        chain.virtualizer?.let { virt ->
            runCatching {
                if (virt.strengthSupported) virt.setStrength(current.virtualizer.toShort())
                virt.enabled = current.enabled && current.virtualizer > 0
            }.onFailure { StructuredLog.w(TAG, "Virtualizer refused the settings: ${it.message}") }
        }
        chain.loudness?.let { level ->
            runCatching {
                level.setTargetGain(current.boostMb)
                level.enabled = current.enabled && current.boostMb > 0
            }.onFailure { StructuredLog.w(TAG, "Loudness enhancer refused the settings: ${it.message}") }
        }
        chain.leveller?.let { leveller ->
            runCatching { leveller.enabled = current.enabled && current.normalize }
                .onFailure { StructuredLog.w(TAG, "Dynamics processing refused the settings: ${it.message}") }
        }
    }
}
