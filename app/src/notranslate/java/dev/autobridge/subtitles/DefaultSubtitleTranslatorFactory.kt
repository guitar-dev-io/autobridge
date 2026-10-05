package dev.autobridge.subtitles

import android.content.Context
import dev.autobridge.logging.StructuredLog

/**
 * The factory a build without the translation engines gets.
 *
 * `autobridge.subtitleTranslation=false` drops ML Kit Translate and ONNX Runtime from the
 * dependency list, which also drops the two classes that import them; this stands in for the
 * `src/translate` copy so everything above it - [SubtitleController], the pipeline, the player -
 * compiles and runs unchanged, with every line coming back untranslated.
 *
 * Nothing should reach here in practice: [SubtitleSettings.enabled] is wired to the same flag, so
 * a config is never active in this build and the controller never asks for an engine. It answers
 * anyway, and logs when it does, because a factory that threw would turn a build-configuration
 * mistake into a dead subtitle track on the road.
 */
class DefaultSubtitleTranslatorFactory(context: Context) : SubtitleTranslatorFactory {

    init {
        // Reading the context keeps this constructor interchangeable with the real one.
        context.applicationContext
    }

    override fun create(config: SubtitleTranslationConfig): SubtitleTranslator {
        StructuredLog.i("SUBTITLE", "translation engines not built; passing ${config.signature} through")
        return PassthroughTranslator(config.engine, config.signature)
    }
}
