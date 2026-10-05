package dev.autobridge.subtitles

/**
 * One loaded translation engine, for one language pair.
 *
 * An instance owns something expensive - ML Kit's downloaded model, or an ONNX Runtime session
 * holding a Marian encoder and decoder - so it is created when translation is switched on and
 * [close]d when it is switched off or the pair changes, rather than per line.
 *
 * [translate] is suspending and may block for a noticeable time: a Marian decoder step runs per
 * output token. It is never called on the main thread.
 */
interface SubtitleTranslator {
    val engine: TranslationEngine

    /** The engine and pair this instance was built for; see [SubtitleTranslationConfig.signature]. */
    val signature: String

    /**
     * Makes the engine ready, downloading a model if one is missing and allowed.
     *
     * Separate from construction so the settings screen can report what is happening instead of a
     * player stalling on its first cue. Calling it twice is harmless.
     */
    suspend fun prepare()

    /** The translation of [text], or [text] itself when the engine has nothing better. */
    suspend fun translate(text: String): String

    fun close()
}

/** Builds the engine a config asks for. Separated out so the pipeline can be tested without one. */
interface SubtitleTranslatorFactory {
    fun create(config: SubtitleTranslationConfig): SubtitleTranslator
}

/**
 * The engine that does nothing: it reports the pair it was asked for and hands every line back
 * untranslated.
 *
 * Used when the chosen pair has no model behind it, and by the whole factory in a build without
 * the engines compiled in. It keeps the pipeline's contract whole - there is always a translator to
 * bind - so the "should this line be translated at all" decision stays in one place
 * ([SubtitleTranslationConfig.isActive]) rather than being half here and half there.
 */
class PassthroughTranslator(
    override val engine: TranslationEngine,
    override val signature: String
) : SubtitleTranslator {
    override suspend fun prepare() = Unit
    override suspend fun translate(text: String): String = text
    override fun close() = Unit
}
