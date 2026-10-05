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
 *
 * This is the `src/translate` copy, compiled when `autobridge.subtitleTranslation` is on. It is
 * the only file that names the two engine classes, which is what lets a build that leaves ML Kit
 * and ONNX Runtime out swap in the `src/notranslate` copy and compile without them.
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
