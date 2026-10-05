package dev.autobridge.subtitles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Turns the cues coming off a subtitle track into lines on the screen, translated where it can be.
 *
 * The sequencing is the whole job here, and it is what a straight "translate every cue" loop gets
 * wrong:
 *
 * - The original goes up immediately and is replaced in place when the translation lands. Waiting
 *   for the engine would mean an empty picture through the first part of every line.
 * - A line already translated once comes back from [SubtitleLineCache] with no engine call, which
 *   matters most on live caption tracks that re-deliver the same cue as it is extended.
 * - Only one translation is in flight at a time, and a result is published only if its line is
 *   still the one on screen. Dialogue arrives faster than a Marian decoder answers, so without
 *   that guard a slow line would overwrite the two that followed it.
 *
 * No Android types: the cue text arrives as plain strings and the result leaves through [publish],
 * so the ordering rules above are testable against a fake engine.
 */
class SubtitleTranslationPipeline(
    private val scope: CoroutineScope,
    private val cache: SubtitleLineCache = SubtitleLineCache(),
    private val publish: (SubtitleLine) -> Unit
) {
    private var translator: SubtitleTranslator? = null
    private var config: SubtitleTranslationConfig? = null

    /** Identifies the line currently on screen, so a late result can tell it is late. */
    private var sequence = 0L
    private var pending: Job? = null
    private var warmUp: Job? = null
    private var lastPublishedOriginal: String? = null

    /**
     * Points the pipeline at an engine, or at none.
     *
     * The previous engine is closed here rather than by the caller: it is this class that knows no
     * translation is still running against it. A change of pair also drops the "same as last
     * line" guard, because the next cue has to be re-translated even if its text is unchanged.
     */
    fun bind(translator: SubtitleTranslator?, config: SubtitleTranslationConfig?) {
        if (this.translator === translator && this.config == config) return
        val engineChanged = this.translator !== translator
        pending?.cancel()
        pending = null
        if (engineChanged) {
            warmUp?.cancel()
            warmUp = null
        }
        this.translator?.takeIf { engineChanged }?.close()
        this.translator = translator
        this.config = config
        lastPublishedOriginal = null
        if (engineChanged && translator != null && config?.isActive == true) {
            // Load the model now, not on the first cue: otherwise the first line of every video
            // waits out a download or a graph load before its translation can start. A failure
            // here is not reported; the first line's own prepare() surfaces it on screen.
            warmUp = scope.launch { runCatching { translator.prepare() } }
        }
    }

    /** One cue group from the track; an empty list is the gap between two lines. */
    fun onCues(raw: List<CharSequence?>) = onText(SubtitleCueText.normalizeAll(raw))

    fun onText(raw: String) {
        val text = SubtitleCueText.normalize(raw)
        val activeConfig = config
        sequence++

        if (text.isEmpty()) {
            pending?.cancel()
            pending = null
            lastPublishedOriginal = null
            publish(SubtitleLine.NONE)
            return
        }

        // The same cue re-delivered while it is still on screen: whatever is up there, including a
        // translation that has already landed, is still the right thing to show.
        if (text == lastPublishedOriginal) return
        lastPublishedOriginal = text

        val engine = activeConfig?.takeIf { it.isActive }?.let { translator }
        if (engine == null || SubtitleCueText.isUntranslatable(text)) {
            pending?.cancel()
            pending = null
            publish(SubtitleLine(original = text))
            return
        }

        val cached = cache.get(engine.signature, text)
        if (cached != null) {
            pending?.cancel()
            pending = null
            publish(SubtitleLine(text, cached, activeConfig.showOriginal))
            return
        }

        publish(SubtitleLine(original = text, showOriginal = activeConfig.showOriginal))
        val mine = sequence
        pending?.cancel()
        pending = scope.launch {
            val result = runCatching {
                engine.prepare()
                engine.translate(text)
            }
            // A cancelled line is not a failed one; there is nothing to show for it.
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            // The line may have been replaced, or the engine swapped, while this ran.
            if (sequence != mine || translator !== engine) return@launch
            result.onSuccess { translated ->
                val clean = translated.trim()
                if (clean.isNotEmpty()) cache.put(engine.signature, text, clean)
                publish(SubtitleLine(text, clean.ifEmpty { null }, activeConfig.showOriginal))
            }.onFailure { failure ->
                publish(
                    SubtitleLine(
                        original = text,
                        showOriginal = activeConfig.showOriginal,
                        error = failure.message ?: failure.javaClass.simpleName
                    )
                )
            }
        }
    }

    /** Clears the screen without touching the engine; used when playback stops. */
    fun clear() {
        pending?.cancel()
        pending = null
        lastPublishedOriginal = null
        publish(SubtitleLine.NONE)
    }

    fun release() {
        bind(null, null)
        cache.clear()
        publish(SubtitleLine.NONE)
    }
}
