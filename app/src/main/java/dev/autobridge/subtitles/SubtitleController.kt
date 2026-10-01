package dev.autobridge.subtitles

import android.content.Context
import dev.autobridge.display.StructuredLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The one object the player hands cues to, and the one that keeps the translation engine in step
 * with the settings.
 *
 * It sits between [dev.autobridge.media.MediaPlaybackService] - which owns the ExoPlayer that
 * decodes the text track - and the pure [SubtitleTranslationPipeline], and it is where the two
 * Android-shaped concerns the pipeline deliberately has none of live:
 *
 * - A [CoroutineScope] for the translation work, on a background dispatcher so a Marian decoder
 *   step never runs on the main thread, torn down with the controller.
 * - A [SubtitleSettings] listener, so turning translation on, switching engine or changing the
 *   pair rebinds the engine mid-playback without the service having to know when settings moved.
 *
 * Cues come in through [onCues]; translated lines leave through [SubtitleHub], which the player
 * screen reads. The settings are re-read on every change rather than captured, so a line already
 * in flight for the old pair is still finished against the engine that was asked for it.
 */
class SubtitleController(
    context: Context,
    private val factory: SubtitleTranslatorFactory = DefaultSubtitleTranslatorFactory(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val appContext = context.applicationContext
    private val pipeline = SubtitleTranslationPipeline(scope = scope, publish = SubtitleHub::publish)

    /** The engine currently bound, kept so an unchanged pair reuses it instead of rebuilding. */
    private var activeTranslator: SubtitleTranslator? = null

    private val settingsListener: () -> Unit = { syncEngine() }

    /** Starts listening to settings and binds the engine the current settings ask for. */
    fun start() {
        SubtitleSettings.addListener(settingsListener)
        syncEngine()
    }

    /** One cue group off the text track; an empty group is the gap between two lines. */
    fun onCues(cues: List<CharSequence?>) = pipeline.onCues(cues)

    /** Clears the line without dropping the engine; used when playback stops or the item changes. */
    fun clear() = pipeline.clear()

    /**
     * Rebinds the pipeline to match the current settings.
     *
     * An unchanged engine+pair reuses the loaded translator and only re-binds the config, so a
     * settings change that merely flipped "show original" - which the pipeline reads per line from
     * the config, not from the engine - does not throw away a resident model. A newly inactive
     * config (translation off, or a same-language pair) drops the engine so nothing stays loaded
     * for work that will not happen, while still passing the config through so a line on screen is
     * relabelled correctly.
     */
    private fun syncEngine() {
        val config = SubtitleSettings.current(appContext)
        if (!config.isActive) {
            if (activeTranslator != null) {
                StructuredLog.i("SUBTITLE", "translation off; releasing engine")
            }
            pipeline.bind(null, config)
            activeTranslator = null
            return
        }

        val existing = activeTranslator
        if (existing != null && existing.signature == config.signature) {
            pipeline.bind(existing, config)
            return
        }

        StructuredLog.i("SUBTITLE", "binding ${config.engine.name} ${config.sourceLanguage}>${config.targetLanguage}")
        val translator = factory.create(config)
        activeTranslator = translator
        // The pipeline closes whatever it was bound to, so the previous engine is released here.
        pipeline.bind(translator, config)
    }

    fun release() {
        SubtitleSettings.removeListener(settingsListener)
        pipeline.release()
        activeTranslator = null
        scope.cancel()
    }
}
