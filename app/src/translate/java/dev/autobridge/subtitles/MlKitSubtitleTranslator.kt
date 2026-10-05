package dev.autobridge.subtitles

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.autobridge.logging.StructuredLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The ML Kit side of [SubtitleTranslator]: Google's on-device translate engine, driving one
 * [Translator] bound to a single language pair.
 *
 * This is the default engine because the machinery is already on the phone - an installed Play
 * Services carries it - so the only cost is a compact per-language model download the first time a
 * language is used. The translation itself is fast enough that a line is on screen with the
 * dialogue rather than after it, which is the bar a subtitle has to clear.
 *
 * ML Kit's API is callback-based and tied to [com.google.android.gms.tasks.Task]; the two
 * suspending points here bridge that to the coroutine the pipeline runs its work on, cancellation
 * included, so a line abandoned mid-flight does not keep a dead translation alive.
 */
class MlKitSubtitleTranslator(
    override val signature: String,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val wifiOnly: Boolean
) : SubtitleTranslator {

    override val engine: TranslationEngine = TranslationEngine.ML_KIT

    private val translator: Translator? = buildTranslator()

    /**
     * Set once the pair's model is confirmed on the device. The pipeline calls [prepare] before
     * every line, and without this each call is another Play Services round trip through
     * `downloadModelIfNeeded` - latency added to every subtitle for a model that is already there.
     */
    @Volatile
    private var ready = false

    private fun buildTranslator(): Translator? {
        // ML Kit covers a subset of the languages the screen offers; a pair it has no code for is
        // not an error here, only an engine that answers with the original line. The settings
        // screen is what steers someone to Opus-MT for those pairs.
        val from = TranslateLanguage.fromLanguageTag(sourceLanguage) ?: return null
        val to = TranslateLanguage.fromLanguageTag(targetLanguage) ?: return null
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(from)
            .setTargetLanguage(to)
            .build()
        return Translation.getClient(options)
    }

    /**
     * Downloads the pair's model if it is missing, under the same Wi-Fi-only rule the Opus-MT
     * store honours: a car is usually on mobile data, and a model download is not something to
     * spend someone's allowance on unasked.
     */
    override suspend fun prepare() {
        if (ready) return
        val client = translator ?: return
        val conditions = DownloadConditions.Builder()
            .apply { if (wifiOnly) requireWifi() }
            .build()
        suspendCancellableCoroutine { continuation ->
            client.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    ready = true
                    continuation.resume(Unit)
                }
                .addOnFailureListener { error ->
                    StructuredLog.w("SUBTITLE", "ml kit model download failed: ${error.message}")
                    continuation.resumeWithException(error)
                }
        }
    }

    override suspend fun translate(text: String): String {
        val client = translator ?: return text
        return suspendCancellableCoroutine { continuation ->
            client.translate(text)
                .addOnSuccessListener { translated -> continuation.resume(translated) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }

    override fun close() {
        ready = false
        translator?.close()
    }
}
