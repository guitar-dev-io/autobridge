package dev.autobridge.subtitles

import android.content.Context
import androidx.core.content.edit
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Which on-device engine turns a subtitle line into the target language.
 *
 * Both run entirely on the phone - a subtitle track is the dialogue of whatever is being watched,
 * and shipping it to a translation API would put that on someone else's server. They differ in
 * what they cost to get there:
 *
 * ML Kit downloads one compact model per language and is the default because an installed Play
 * Services already has the machinery; Opus-MT is a full Marian encoder-decoder run through ONNX
 * Runtime, which reads better on full sentences and covers pairs ML Kit has no model for, at the
 * price of a per-pair download and real inference time.
 */
enum class TranslationEngine(val label: String, val caption: String) {
    ML_KIT("ML Kit", "Compact per-language models, fastest to set up"),
    OPUS_MT("Opus-MT", "Marian models through ONNX Runtime, better on full sentences")
}

/** A language a subtitle track can be read from or written to, by ISO 639-1 code. */
data class SubtitleLanguage(val code: String, val label: String)

/**
 * The languages offered on the settings screen.
 *
 * Deliberately a curated list rather than everything either engine can do: ML Kit publishes about
 * sixty, Opus-MT several hundred pairs, and a scrolling wall of them is the wrong thing to hand
 * someone in a parked car. These are the ones with both an ML Kit model and a published Opus-MT
 * pair against English, so switching engine never silently loses the language already chosen.
 */
object SubtitleLanguages {
    val all: List<SubtitleLanguage> = listOf(
        SubtitleLanguage("ar", "Arabic"),
        SubtitleLanguage("bg", "Bulgarian"),
        SubtitleLanguage("cs", "Czech"),
        SubtitleLanguage("da", "Danish"),
        SubtitleLanguage("de", "German"),
        SubtitleLanguage("el", "Greek"),
        SubtitleLanguage("en", "English"),
        SubtitleLanguage("es", "Spanish"),
        SubtitleLanguage("et", "Estonian"),
        SubtitleLanguage("fi", "Finnish"),
        SubtitleLanguage("fr", "French"),
        SubtitleLanguage("he", "Hebrew"),
        SubtitleLanguage("hi", "Hindi"),
        SubtitleLanguage("hu", "Hungarian"),
        SubtitleLanguage("id", "Indonesian"),
        SubtitleLanguage("it", "Italian"),
        SubtitleLanguage("ja", "Japanese"),
        SubtitleLanguage("ko", "Korean"),
        SubtitleLanguage("nl", "Dutch"),
        SubtitleLanguage("pl", "Polish"),
        SubtitleLanguage("pt", "Portuguese"),
        SubtitleLanguage("ro", "Romanian"),
        SubtitleLanguage("ru", "Russian"),
        SubtitleLanguage("sv", "Swedish"),
        SubtitleLanguage("th", "Thai"),
        SubtitleLanguage("tr", "Turkish"),
        SubtitleLanguage("uk", "Ukrainian"),
        SubtitleLanguage("vi", "Vietnamese"),
        SubtitleLanguage("zh", "Chinese")
    )

    private val byCode = all.associateBy { it.code }

    fun find(code: String?): SubtitleLanguage? = byCode[code?.lowercase(Locale.ROOT)]

    /** The language name for a code, or the code itself when it is not one of the listed ones. */
    fun label(code: String): String = find(code)?.label ?: code
}

/**
 * Settings for subtitle translation, in the same shape as [dev.autobridge.settings.VideoSettings]:
 * a SharedPreferences `object` every part of the stack can read without a handle to the screen
 * that owns it, because the settings screen, the playback service and the player all need them.
 *
 * Translation is off by default. It is not free - ML Kit wants a model download, Opus-MT wants a
 * bigger one plus inference on every line - and turning it on for someone who was watching in
 * their own language would only make the subtitles worse.
 */
object SubtitleSettings {
    private const val PREFS_NAME = "autobridge_subtitles"

    private const val KEY_ENABLED = "translation_enabled"
    private const val KEY_ENGINE = "translation_engine"
    private const val KEY_SOURCE = "source_language"
    private const val KEY_TARGET = "target_language"
    private const val KEY_SHOW_ORIGINAL = "show_original"
    private const val KEY_WIFI_ONLY = "download_on_wifi_only"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Called after any change here, on the thread that made it. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) = putBoolean(context, KEY_ENABLED, value)

    fun engine(context: Context): TranslationEngine =
        SubtitleSettingsCodec.engine(prefs(context).getString(KEY_ENGINE, null))

    fun setEngine(context: Context, value: TranslationEngine) =
        putString(context, KEY_ENGINE, value.name)

    fun sourceLanguage(context: Context): String =
        SubtitleSettingsCodec.language(prefs(context).getString(KEY_SOURCE, null), "en")

    fun setSourceLanguage(context: Context, code: String) = putString(context, KEY_SOURCE, code)

    /**
     * The language lines are translated into, defaulting to the phone's own language the first
     * time it is read - the one answer that is right without being asked for.
     */
    fun targetLanguage(context: Context): String = SubtitleSettingsCodec.language(
        prefs(context).getString(KEY_TARGET, null),
        SubtitleSettingsCodec.deviceLanguage(Locale.getDefault().language)
    )

    fun setTargetLanguage(context: Context, code: String) = putString(context, KEY_TARGET, code)

    fun showOriginal(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOW_ORIGINAL, false)

    fun setShowOriginal(context: Context, value: Boolean) = putBoolean(context, KEY_SHOW_ORIGINAL, value)

    fun downloadOnWifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_ONLY, true)

    fun setDownloadOnWifiOnly(context: Context, value: Boolean) = putBoolean(context, KEY_WIFI_ONLY, value)

    /** Everything the translation stack needs, read in one go. */
    fun current(context: Context): SubtitleTranslationConfig = SubtitleTranslationConfig(
        enabled = enabled(context),
        engine = engine(context),
        sourceLanguage = sourceLanguage(context),
        targetLanguage = targetLanguage(context),
        showOriginal = showOriginal(context)
    )

    private fun putBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit { putBoolean(key, value) }
        changed()
    }

    private fun putString(context: Context, key: String, value: String) {
        prefs(context).edit { putString(key, value) }
        changed()
    }

    private fun changed() = listeners.forEach { it() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * The settings the translation stack runs on, as a value.
 *
 * Passed around rather than re-read: a line is normalized, queued, translated and rendered across
 * several hops, and all of them have to agree about which pair it was for - a target language
 * changed mid-line must not relabel a translation that was already produced for the old one.
 */
data class SubtitleTranslationConfig(
    val enabled: Boolean,
    val engine: TranslationEngine,
    val sourceLanguage: String,
    val targetLanguage: String,
    val showOriginal: Boolean
) {
    /** Translating a language into itself is work with no result; the original line is the answer. */
    val isSamePair: Boolean get() = sourceLanguage.equals(targetLanguage, ignoreCase = true)

    /** True when a cue should actually be sent to an engine. */
    val isActive: Boolean get() = enabled && !isSamePair

    /** The identity of the engine+pair, so a change can invalidate caches and loaded models. */
    val signature: String get() = "${engine.name}:$sourceLanguage>$targetLanguage"
}

/**
 * Null-safe decoding of persisted values, separated from [SubtitleSettings] for the same reason as
 * [dev.autobridge.settings.VideoSettingsCodec]: it is the part with the defaults in it, and it is
 * testable without a [Context].
 */
object SubtitleSettingsCodec {
    fun engine(name: String?): TranslationEngine =
        TranslationEngine.entries.firstOrNull { it.name == name } ?: TranslationEngine.ML_KIT

    /** A stored code is only honoured while it is still one the screen can show. */
    fun language(code: String?, fallback: String): String =
        SubtitleLanguages.find(code)?.code ?: fallback

    /**
     * The phone's language when it is one of the offered ones, English otherwise. A device set to
     * a language no engine here covers would otherwise default to translating into nothing.
     */
    fun deviceLanguage(language: String?): String = SubtitleLanguages.find(language)?.code ?: "en"
}
