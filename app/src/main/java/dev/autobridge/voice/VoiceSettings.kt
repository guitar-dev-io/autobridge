package dev.autobridge.voice

import android.content.Context
import androidx.core.content.edit

/** The language Whisper is told to expect. [AUTO] lets it detect, which is what mixed speech needs. */
enum class VoiceLanguage(val code: String) {
    AUTO("auto"),
    THAI("th"),
    ENGLISH("en");

    companion object {
        fun fromCode(code: String?): VoiceLanguage = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/**
 * Settings &gt; Voice Recognition, persisted in SharedPreferences like every other settings store
 * here (see [dev.autobridge.settings.AppPreferences]).
 *
 * Also where the selected model id lives ([ModelSelection]), so the choice survives a restart.
 */
object VoiceSettings {
    private const val PREFS = "autobridge_voice"

    private const val KEY_ENABLED = "voice_commands_enabled"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_MODEL = "whisper_model"
    private const val KEY_TRANSLATE = "translate"
    private const val KEY_AUTO_EXECUTE = "auto_execute"
    private const val KEY_SILENCE_MS = "silence_timeout_ms"
    private const val KEY_MAX_LISTEN_MS = "max_listen_ms"
    private const val KEY_THREADS = "threads"

    const val DEFAULT_SILENCE_MS = 1_200
    val SILENCE_OPTIONS_MS = listOf(800, 1_200, 1_800, 2_500)

    const val DEFAULT_MAX_LISTEN_MS = 10_000
    val MAX_LISTEN_OPTIONS_MS = listOf(6_000, 10_000, 15_000, 20_000)

    /** 0 means "pick from the CPU count"; see [effectiveThreads]. */
    val THREAD_OPTIONS = listOf(0, 2, 4, 6)

    /** Off means the Home microphone uses the phone's own speech service, as it did before. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, value) }

    fun language(context: Context): VoiceLanguage =
        VoiceLanguage.fromCode(prefs(context).getString(KEY_LANGUAGE, VoiceLanguage.AUTO.code))
    fun setLanguage(context: Context, value: VoiceLanguage) =
        prefs(context).edit { putString(KEY_LANGUAGE, value.code) }

    /** Off by default: a command has to stay in the language it was spoken in to be parsed. */
    fun translate(context: Context): Boolean = prefs(context).getBoolean(KEY_TRANSLATE, false)
    fun setTranslate(context: Context, value: Boolean) = prefs(context).edit { putBoolean(KEY_TRANSLATE, value) }

    /** Run a recognised safe command straight away instead of showing it for confirmation. */
    fun autoExecute(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_EXECUTE, true)
    fun setAutoExecute(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_AUTO_EXECUTE, value) }

    fun silenceTimeoutMs(context: Context): Int = prefs(context).getInt(KEY_SILENCE_MS, DEFAULT_SILENCE_MS)
    fun setSilenceTimeoutMs(context: Context, value: Int) = prefs(context).edit { putInt(KEY_SILENCE_MS, value) }

    fun maxListenMs(context: Context): Int = prefs(context).getInt(KEY_MAX_LISTEN_MS, DEFAULT_MAX_LISTEN_MS)
    fun setMaxListenMs(context: Context, value: Int) = prefs(context).edit { putInt(KEY_MAX_LISTEN_MS, value) }

    fun threads(context: Context): Int = prefs(context).getInt(KEY_THREADS, 0)
    fun setThreads(context: Context, value: Int) = prefs(context).edit { putInt(KEY_THREADS, value) }

    /**
     * The thread count actually used. Auto is half the cores, between 2 and 4: phones are
     * big.LITTLE, and spreading inference onto the little cores makes it slower, not faster.
     */
    fun effectiveThreads(context: Context): Int =
        threads(context).takeIf { it > 0 } ?: autoThreads(Runtime.getRuntime().availableProcessors())

    fun autoThreads(cores: Int): Int = (cores / 2).coerceIn(2, 4)

    /** [ModelSelection] over these preferences, for [WhisperModelStore]. */
    fun selection(context: Context): ModelSelection {
        val app = context.applicationContext
        return object : ModelSelection {
            override var selectedModelId: String?
                get() = prefs(app).getString(KEY_MODEL, null)
                set(value) = prefs(app).edit { if (value == null) remove(KEY_MODEL) else putString(KEY_MODEL, value) }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
