package dev.autobridge.subtitles

import android.content.Context

/**
 * Builds the engine a [SubtitleTranslationConfig] asks for, on this device.
 *
 * The one place that knows both the engine enum and the concrete classes behind it, so the
 * pipeline depends on neither: it is handed a [SubtitleTranslator] and never learns whether it is
 * ML Kit or Opus-MT. The Wi-Fi-only download rule is read here, once, and passed to whichever
 * engine was chosen, because both download a model the first time a pair is used and the policy is
 * the same for both.
 *
 * A pair Opus-MT has no catalog entry for falls back to a [PassthroughTranslator] rather than
 * throwing: the settings screen is what should prevent an impossible pair from being chosen, and
 * an engine that returns the original line is a better failure in a moving car than a crash.
 */
class DefaultSubtitleTranslatorFactory(context: Context) : SubtitleTranslatorFactory {

    private val appContext: Context = context.applicationContext

    override fun create(config: SubtitleTranslationConfig): SubtitleTranslator {
        val wifiOnly = SubtitleSettings.downloadOnWifiOnly(appContext)
        return when (config.engine) {
            TranslationEngine.ML_KIT -> MlKitSubtitleTranslator(
                signature = config.signature,
                sourceLanguage = config.sourceLanguage,
                targetLanguage = config.targetLanguage,
                wifiOnly = wifiOnly
            )

            TranslationEngine.OPUS_MT -> {
                val model = opusMtModelFor(config)
                    ?: return PassthroughTranslator(config.engine, config.signature)
                OpusMtSubtitleTranslator(
                    context = appContext,
                    signature = config.signature,
                    model = model,
                    wifiOnly = wifiOnly
                )
            }
        }
    }
}

/**
 * The engine that does nothing: it reports the pair it was asked for and hands every line back
 * untranslated.
 *
 * Used when the chosen pair has no model behind it. It keeps the pipeline's contract whole - there
 * is always a translator to bind - so the "should this line be translated at all" decision stays
 * in one place ([SubtitleTranslationConfig.isActive]) rather than being half here and half there.
 */
class PassthroughTranslator(
    override val engine: TranslationEngine,
    override val signature: String
) : SubtitleTranslator {
    override suspend fun prepare() = Unit
    override suspend fun translate(text: String): String = text
    override fun close() = Unit
}
