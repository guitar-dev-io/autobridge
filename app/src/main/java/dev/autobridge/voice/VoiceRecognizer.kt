package dev.autobridge.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.autobridge.logging.StructuredLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why a listen session produced no transcript. The screens map each to a message. */
enum class VoiceError {
    PERMISSION_DENIED,
    NO_MODEL,
    UNSUPPORTED_DEVICE,
    MODEL_LOAD_FAILED,
    RECORDER_FAILED,
    MIC_BUSY,
    INTERRUPTED,
    NOTHING_HEARD,
    TRANSCRIPTION_FAILED
}

/**
 * One microphone press, end to end: permission check, record until the speaker stops, transcribe
 * with the selected model, hand back the text. The Home microphone, the Test Voice Recognition
 * screen and the car Agent each hold one of these and render its [state].
 *
 * Plays the role a ViewModel would elsewhere - this codebase builds its screens as plain
 * Activities and Car App screens without androidx ViewModels, so the controller is a plain class
 * whose owner calls [cancel] when its screen stops (which is also how leaving the app mid-sentence
 * releases the microphone).
 *
 * The model is loaded *while* the user speaks, not after: by the time they stop, a first-use load
 * has usually finished, and on every later press it is already resident (see [WhisperEngine]).
 */
class VoiceRecognizer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    sealed interface State {
        data object Idle : State
        data class Listening(val levelDb: Double, val elapsedMs: Long) : State
        data class Transcribing(val audioMs: Long) : State
        data class Done(val text: String, val transcription: Transcription) : State
        data class Failed(val error: VoiceError, val detail: String = "") : State
    }

    private val app = context.applicationContext
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var control: VoiceRecorder.Control? = null
    private var focusRequest: AudioFocusRequest? = null

    val isActive: Boolean get() = job?.isActive == true

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts a session on [source] (the phone microphone unless given another). The caller asks
     * for the microphone permission first; without it this fails with [VoiceError.PERMISSION_DENIED].
     */
    fun start(source: PcmSource = PhoneMicSource(app)) {
        if (isActive) return
        val store = VoiceRuntime.modelStore(app)
        val engine = VoiceRuntime.engine(app)
        if (!hasMicPermission()) return fail(VoiceError.PERMISSION_DENIED)
        if (!VoiceRuntime.isSupported(app)) return fail(VoiceError.UNSUPPORTED_DEVICE)
        val model = store.getSelectedModel() ?: return fail(VoiceError.NO_MODEL)
        val file = store.getModelPath(model.id) ?: return fail(VoiceError.NO_MODEL)

        val control = VoiceRecorder.Control().also { this.control = it }
        _state.value = State.Listening(SilenceDetector.SILENCE_DB, 0)
        job = scope.launch {
            // Load (or confirm loaded) in parallel with recording.
            val preload = async(Dispatchers.Default) { runCatching { engine.ensureLoaded(model, file) } }
            if (!requestFocus(control)) {
                preload.cancel()
                return@launch fail(VoiceError.MIC_BUSY)
            }
            val recording = try {
                withContext(Dispatchers.IO) {
                    if (!source.start()) {
                        source.release()
                        null
                    } else {
                        VoiceRecorder.record(
                            source = source,
                            detector = SilenceDetector(
                                silenceTimeoutMs = VoiceSettings.silenceTimeoutMs(app),
                                maxDurationMs = VoiceSettings.maxListenMs(app)
                            ),
                            maxDurationMs = VoiceSettings.maxListenMs(app),
                            control = control
                        ) { level, elapsed -> _state.value = State.Listening(level, elapsed) }
                    }
                }
            } finally {
                abandonFocus()
            }
            if (recording == null) return@launch fail(VoiceError.RECORDER_FAILED)
            StructuredLog.i(VoiceRuntime.TAG, "recorded ${recording.durationMs} ms, ending=${recording.ending}")
            when (recording.ending) {
                VoiceRecording.Ending.CANCELLED -> { _state.value = State.Idle; return@launch }
                VoiceRecording.Ending.INTERRUPTED -> return@launch fail(VoiceError.INTERRUPTED)
                VoiceRecording.Ending.RECORDER_ERROR -> return@launch fail(VoiceError.RECORDER_FAILED)
                else -> Unit
            }
            if (!recording.heardSpeech) return@launch fail(VoiceError.NOTHING_HEARD)

            _state.value = State.Transcribing(recording.durationMs)
            preload.await().exceptionOrNull()?.let { error ->
                return@launch fail(errorFor(error), error.message.orEmpty())
            }
            val prompt = VoiceCommandParser.RECOGNITION_PROMPT
            val result = runCatching {
                engine.transcribe(
                    model = model,
                    file = file,
                    pcm = PcmConversion.toFloat(recording.samples, recording.count),
                    language = VoiceSettings.language(app),
                    translate = VoiceSettings.translate(app),
                    threads = VoiceSettings.effectiveThreads(app),
                    prompt = prompt
                )
            }
            if (control.cancel.get()) { _state.value = State.Idle; return@launch }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                return@launch fail(errorFor(error), error.message.orEmpty())
            }
            val transcription = result.getOrThrow()
            val text = TranscriptCleaner.clean(transcription.text, prompt)
            StructuredLog.i(
                VoiceRuntime.TAG,
                "transcribed model=${transcription.modelId} audio=${transcription.audioMs}ms " +
                    "took=${transcription.processingMs}ms lang=${transcription.language}"
            )
            _state.value = if (text.isEmpty()) State.Failed(VoiceError.NOTHING_HEARD)
            else State.Done(text, transcription.copy(text = text))
        }
    }

    /** Stops listening and transcribes what was heard so far (a second tap on the microphone). */
    fun finish() {
        control?.stop?.set(true)
    }

    /** Abandons the session: the microphone is released and nothing is transcribed or reported. */
    fun cancel() {
        control?.cancel?.set(true)
        if (_state.value is State.Transcribing) VoiceRuntime.engine(app).abort()
        abandonFocus()
        if (_state.value !is State.Done && _state.value !is State.Failed) _state.value = State.Idle
    }

    /** Back to [State.Idle] after a result has been shown. */
    fun reset() {
        if (!isActive) _state.value = State.Idle
    }

    private fun fail(error: VoiceError, detail: String = "") {
        StructuredLog.i(VoiceRuntime.TAG, "voice failed: $error $detail")
        _state.value = State.Failed(error, detail)
    }

    private fun errorFor(error: Throwable): VoiceError = when ((error as? WhisperException)?.reason) {
        WhisperException.Reason.UNSUPPORTED_DEVICE -> VoiceError.UNSUPPORTED_DEVICE
        WhisperException.Reason.MODEL_LOAD_FAILED -> VoiceError.MODEL_LOAD_FAILED
        else -> VoiceError.TRANSCRIPTION_FAILED
    }

    /**
     * Transient exclusive focus, as for any assistant: playing media pauses rather than being
     * transcribed, and a call or navigation prompt taking focus ends the session as interrupted.
     */
    private fun requestFocus(control: VoiceRecorder.Control): Boolean {
        val manager = app.getSystemService(AudioManager::class.java) ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    control.interrupted.set(true)
                }
            }, main)
            .build()
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        app.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
    }
}
