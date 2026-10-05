package dev.autobridge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.autobridge.logging.StructuredLog
import java.util.Locale

/**
 * Short spoken confirmations for voice commands ("กำลังเปิดมิเรอร์"), so the driver does not have
 * to read a toast.
 *
 * Takes transient may-duck focus while speaking, so music dips under the reply (handled by
 * [AudioFocusController]'s DUCK path) instead of stopping, and releases it when the utterance ends.
 * Uses the Thai voice when one is installed and falls back to the engine default otherwise; if no
 * engine is available at all, [speak] is a silent no-op and the caller's toast still shows.
 */
class VoiceFeedback(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(appContext) { status -> onInit(status) }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            StructuredLog.i("VOICE", "tts init failed status=$status")
            return
        }
        // Follow the device/app language so the spoken reply matches the UI language the toast is
        // shown in. The phrases themselves come from locale-aware string resources, so the voice
        // and the text always agree. Falls back to the engine default when no voice is installed.
        val locale = appLocale()
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            StructuredLog.i("VOICE", "tts voice unavailable for ${locale.toLanguageTag()}; using engine default")
        }
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = release()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = release()
            override fun onError(utteranceId: String?, errorCode: Int) = release()
        })
        ready = true
        pending?.let { pending = null; speak(it) }
    }

    /** Speaks [text], replacing anything still being spoken. Queued until the engine is ready. */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            pending = text
            return
        }
        audioManager?.requestAudioFocus(focusRequest)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
    }

    fun shutdown() {
        pending = null
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        release()
    }

    private fun release() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    /** The current app/device locale, honoring a per-app language override on Android 13+. */
    private fun appLocale(): Locale {
        val configLocales = appContext.resources.configuration.locales
        return if (!configLocales.isEmpty) configLocales.get(0) else Locale.getDefault()
    }

    private companion object {
        const val UTTERANCE_ID = "autobridge-voice-feedback"
    }
}
