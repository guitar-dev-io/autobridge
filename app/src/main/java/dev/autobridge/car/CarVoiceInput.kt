package dev.autobridge.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import dev.autobridge.R
import dev.autobridge.display.StructuredLog
import java.io.IOException

/**
 * One-shot speech-to-text for the car Agent screen.
 *
 * Two microphone routes, chosen per attempt:
 * - **Car microphone** (preferred): [CarAudioRecord] streams 16 kHz mono PCM from the head unit,
 *   and the phone's [SpeechRecognizer] reads it through [RecognizerIntent.EXTRA_AUDIO_SOURCE].
 *   Needs Car App API level 5 on the host and Android 13 on the phone, plus a recognizer that
 *   accepts an external audio source. The phone is usually in a pocket or a mount, so this is the
 *   route that actually hears the driver.
 * - **Phone microphone** (fallback): a plain [SpeechRecognizer] session. Used when the car route is
 *   unavailable, or once automatically if the recognizer rejects the car stream.
 *
 * Audio focus is taken (transient exclusive, as the car-microphone guide requires) so playing media
 * pauses and is not transcribed, and it is released before results are delivered so the caller's
 * spoken reply is not fighting this request for focus.
 *
 * All public methods and listener callbacks run on the main thread. [SpeechRecognizer] requires it.
 */
class CarVoiceInput(
    private val carContext: CarContext,
    private val listener: Listener,
) {
    interface Listener {
        fun onListening(source: Source)
        fun onResult(text: String)
        fun onError(message: String)
    }

    enum class Source { CAR_MIC, PHONE_MIC }

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = carContext.getSystemService(AudioManager::class.java)

    private var recognizer: SpeechRecognizer? = null
    private var source: Source? = null
    private var focusRequest: AudioFocusRequest? = null
    private var readSide: ParcelFileDescriptor? = null
    private var pumpThread: Thread? = null
    @Volatile private var pumping = false
    private var fellBackToPhone = false

    private val timeout = Runnable {
        StructuredLog.i("VOICE", "listen timeout, finishing")
        pumping = false
        recognizer?.stopListening()
    }

    val isListening: Boolean get() = recognizer != null

    fun start() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(carContext)) {
            listener.onError(carContext.getString(R.string.voice_error_no_recognizer))
            return
        }
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // The host shows "check your phone"; the phone shows the system permission dialog.
            carContext.requestPermissions(
                listOf(Manifest.permission.RECORD_AUDIO),
                carContext.mainExecutor
            ) { granted, _ ->
                if (Manifest.permission.RECORD_AUDIO in granted) start()
                else listener.onError(
                    carContext.getString(R.string.voice_error_needs_mic_permission)
                )
            }
            return
        }
        if (!requestFocus()) {
            listener.onError(carContext.getString(R.string.voice_error_mic_busy))
            return
        }
        fellBackToPhone = false
        if (carMicSupported()) startCarMic() else startPhoneMic()
    }

    /** Stops without delivering a result. Safe to call when idle. */
    fun cancel() {
        main.removeCallbacks(timeout)
        teardownSession()
        abandonFocus()
    }

    private fun carMicSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun startCarMic() {
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // start() already asked; re-checked here because the grant can be revoked in between.
            cancel()
            listener.onError(carContext.getString(R.string.voice_error_needs_mic_permission))
            return
        }
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
        val record = runCatching { CarAudioRecord.create(carContext) }.getOrNull()
        if (pipe == null || record == null) {
            pipe?.forEach { runCatching { it.close() } }
            startPhoneMic()
            return
        }
        readSide = pipe[0]
        val ok = runCatching { record.startRecording() }.isSuccess
        if (!ok) {
            pipe.forEach { runCatching { it.close() } }
            readSide = null
            startPhoneMic()
            return
        }
        pumping = true
        pumpThread = Thread({ pump(record, pipe[1]) }, "AutoBridge-CarMic").also { it.start() }

        val intent = baseIntent().apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        }
        listen(Source.CAR_MIC, intent)
    }

    private fun startPhoneMic() {
        listen(Source.PHONE_MIC, baseIntent())
    }

    private fun listen(source: Source, intent: Intent) {
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(carContext) }.getOrNull()
        if (created == null) {
            cancel()
            listener.onError(carContext.getString(R.string.voice_error_start_failed))
            return
        }
        this.source = source
        recognizer = created
        created.setRecognitionListener(recognitionListener)
        StructuredLog.i("VOICE", "listening source=$source")
        created.startListening(intent)
        main.postDelayed(timeout, MAX_LISTEN_MS)
        listener.onListening(source)
    }

    private fun baseIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // Deliberately not the UI language. What is recognised is what the driver *says*, and the
        // command vocabularies in AgentCommandParser/CommandParser accept Thai and English either
        // way; Thai recognition also passes English site names through intact, while English
        // recognition does not survive Thai. Someone reading the English UI can still speak Thai.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, carContext.packageName)
    }

    /** Copies car-mic PCM into the pipe until stopped; closing the pipe is the recognizer's EOF. */
    private fun pump(record: CarAudioRecord, writeSide: ParcelFileDescriptor) {
        val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        val deadline = SystemClock.elapsedRealtime() + MAX_LISTEN_MS
        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
            try {
                while (pumping && SystemClock.elapsedRealtime() < deadline) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read > 0) out.write(buffer, 0, read)
                }
            } catch (_: IOException) {
                // Recognizer closed its end; nothing left to feed.
            } finally {
                runCatching { record.stopRecording() }
            }
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onEndOfSpeech() {
            // Stop feeding audio so the recognizer finalises promptly.
            pumping = false
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            cancel()
            if (text.isEmpty()) {
                listener.onError(carContext.getString(R.string.voice_error_nothing_heard))
            }
            else listener.onResult(text)
        }

        override fun onError(error: Int) {
            StructuredLog.i("VOICE", "recognizer error=$error source=$source")
            val wasCarMic = source == Source.CAR_MIC
            main.removeCallbacks(timeout)
            teardownSession()
            val userSilent = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (wasCarMic && !userSilent && !fellBackToPhone) {
                // Most likely the recognizer does not accept an external audio source. Keep focus
                // and retry once on the phone's own microphone.
                fellBackToPhone = true
                startPhoneMic()
                return
            }
            abandonFocus()
            listener.onError(messageFor(error))
        }
    }

    private fun teardownSession() {
        pumping = false
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        source = null
        readSide?.let { runCatching { it.close() } }
        readSide = null
        pumpThread = null
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change ->
                // Losing focus mid-listen (a call, a nav prompt) means the mic is no longer ours.
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    if (isListening) {
                        cancel()
                        listener.onError(carContext.getString(R.string.voice_error_interrupted))
                    }
                }
            }, main)
            .build()
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        audioManager?.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    private fun messageFor(error: Int): String = carContext.getString(
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_error_nothing_heard
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> R.string.voice_error_server
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                R.string.voice_error_needs_mic_permission
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.voice_error_recognizer_busy
            else -> R.string.voice_error_generic
        }
    )

    private companion object {
        const val MAX_LISTEN_MS = 10_000L
        const val LANGUAGE = "th-TH"
    }
}
