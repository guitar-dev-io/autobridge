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
import kotlinx.coroutines.coroutineScope
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
 *
 * Each session carries a [VoiceLatencyTrace] from the microphone opening to the transcript; the
 * owner adds the intent and action marks and calls [VoiceLatencyTrace.finish]. Failed and
 * cancelled sessions are finished here.
 *
 * [keepAudio] holds on to the last recording (in memory only) so the test screen can replay it
 * through other thread counts and settings; nothing else should set it.
 */
class VoiceRecognizer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val keepAudio: Boolean = false
) {
    sealed interface State {
        data object Idle : State
        data class Listening(val levelDb: Double, val elapsedMs: Long) : State
        data class Transcribing(val audioMs: Long) : State
        data class Done(
            val text: String,
            val transcription: Transcription,
            val trace: VoiceLatencyTrace = VoiceLatencyTrace()
        ) : State
        data class Failed(val error: VoiceError, val detail: String = "") : State
    }

    private val app = context.applicationContext
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var control: VoiceRecorder.Control? = null
    private var ticket: WhisperEngine.Ticket? = null
    private var trace: VoiceLatencyTrace? = null
    private var focusRequest: AudioFocusRequest? = null

    /** The last recording as whisper took it; only with [keepAudio]. */
    @Volatile
    var lastAudio: FloatArray? = null
        private set

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
        val trace = VoiceLatencyTrace().also { this.trace = it }
        trace.source = if (source is PhoneMicSource) "phone" else "car"
        val store = VoiceRuntime.modelStore(app)
        val engine = VoiceRuntime.engine(app)
        if (!hasMicPermission()) return fail(VoiceError.PERMISSION_DENIED)
        if (!VoiceRuntime.isSupported(app)) return fail(VoiceError.UNSUPPORTED_DEVICE)
        val model = store.getSelectedModel() ?: return fail(VoiceError.NO_MODEL)
        val file = store.getModelPath(model.id) ?: return fail(VoiceError.NO_MODEL)
        val language = VoiceSettings.language(app)
        val threads = VoiceSettings.effectiveThreads(app)

        val control = VoiceRecorder.Control().also { this.control = it }
        val ticket = WhisperEngine.Ticket().also { this.ticket = it }
        trace.modelId = model.id
        trace.modelWarm = engine.loadedModelId == model.id
        trace.threads = threads
        _state.value = State.Listening(SilenceDetector.SILENCE_DB, 0)
        val lease = engine.lease()
        job = scope.launch {
            listen(source, engine, model, file, language, threads, control, ticket, trace)
        }.also {
            // On completion rather than in a finally: a job cancelled before it ever ran has no
            // finally to run, and the lease would keep the model from being freed for good.
            it.invokeOnCompletion { lease.close() }
        }
    }

    private suspend fun listen(
        source: PcmSource,
        engine: WhisperEngine,
        model: WhisperModelInfo,
        file: java.io.File,
        language: VoiceLanguage,
        threads: Int,
        control: VoiceRecorder.Control,
        ticket: WhisperEngine.Ticket,
        trace: VoiceLatencyTrace
    ) = coroutineScope {
        // Load (or confirm loaded) in parallel with recording.
        val preload = async(Dispatchers.Default) {
            runCatching { engine.ensureLoaded(model, file, language, threads) }
        }
        if (!requestFocus(control)) {
            preload.cancel()
            return@coroutineScope fail(VoiceError.MIC_BUSY)
        }
        val recording = try {
            withContext(Dispatchers.IO) {
                if (!source.start()) {
                    source.release()
                    null
                } else {
                    trace.mark(VoiceLatencyTrace.Mark.MICROPHONE_START)
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
        if (recording == null) return@coroutineScope fail(VoiceError.RECORDER_FAILED)
        markRecording(trace, recording)
        StructuredLog.i(VoiceRuntime.TAG, "recorded ${recording.durationMs} ms, ending=${recording.ending}")
        when (recording.ending) {
            VoiceRecording.Ending.CANCELLED -> return@coroutineScope cancelled()
            VoiceRecording.Ending.INTERRUPTED -> return@coroutineScope fail(VoiceError.INTERRUPTED)
            VoiceRecording.Ending.RECORDER_ERROR -> return@coroutineScope fail(VoiceError.RECORDER_FAILED)
            else -> Unit
        }
        if (!recording.heardSpeech) return@coroutineScope fail(VoiceError.NOTHING_HEARD)

        _state.value = State.Transcribing(recording.durationMs)
        preload.await().exceptionOrNull()?.let { error ->
            return@coroutineScope fail(errorFor(error), error.message.orEmpty())
        }
        val pcm = PcmConversion.toFloat(recording.samples, recording.count)
        if (keepAudio) lastAudio = pcm
        val prompt = VoiceCommandParser.RECOGNITION_PROMPT
        VoiceRuntime.describeEnvironment(app, trace)
        val result = runCatching {
            engine.transcribe(
                model = model,
                file = file,
                pcm = pcm,
                language = language,
                translate = VoiceSettings.translate(app),
                threads = threads,
                prompt = prompt,
                ticket = ticket
            )
        }
        if (control.cancel.get() || ticket.aborted) return@coroutineScope cancelled()
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
            return@coroutineScope fail(errorFor(error), error.message.orEmpty())
        }
        val transcription = result.getOrThrow()
        trace.markAt(VoiceLatencyTrace.Mark.INFERENCE_START, transcription.startedAtNanos)
        trace.markAt(VoiceLatencyTrace.Mark.INFERENCE_END, transcription.endedAtNanos)
        trace.audioMs = transcription.audioMs
        trace.modelLoadMs = transcription.modelLoadMs
        trace.warmUpMs = transcription.warmUpMs
        trace.language = transcription.language
        trace.encodeMs = transcription.encodeMs
        trace.decodeMs = transcription.decodeMs
        trace.fallback = transcription.fallback
        val text = TranscriptCleaner.clean(transcription.text, prompt)
        StructuredLog.i(
            VoiceRuntime.TAG,
            "transcribed model=${transcription.modelId} audio=${transcription.audioMs}ms " +
                "took=${transcription.processingMs}ms lang=${transcription.language}"
        )
        if (text.isEmpty()) {
            fail(VoiceError.NOTHING_HEARD)
        } else {
            this@VoiceRecognizer.trace = null
            _state.value = State.Done(text, transcription.copy(text = text), trace)
        }
    }

    /**
     * Endpoint marks from the recording: the loop's own stop time, and the speech boundaries the
     * detector placed in audio time, converted to the clock through the microphone start.
     */
    private fun markRecording(trace: VoiceLatencyTrace, recording: VoiceRecording) {
        trace.ending = recording.ending.name
        val endedAt = recording.endedAtNanos.takeIf { it != 0L } ?: trace.now()
        trace.markAt(VoiceLatencyTrace.Mark.ENDPOINT_DETECTED, endedAt)
        if (!recording.heardSpeech) return
        trace.markAt(VoiceLatencyTrace.Mark.SPEECH_END, endedAt - recording.trailingSilenceMs * 1_000_000)
        val micStart = trace.at(VoiceLatencyTrace.Mark.MICROPHONE_START)
        if (micStart != null && recording.speechStartMs >= 0) {
            trace.markAt(VoiceLatencyTrace.Mark.SPEECH_START, micStart + recording.speechStartMs * 1_000_000)
        }
    }

    /** Stops listening and transcribes what was heard so far (a second tap on the microphone). */
    fun finish() {
        control?.stop?.set(true)
    }

    /** Abandons the session: the microphone is released and nothing is transcribed or reported. */
    fun cancel() {
        control?.cancel?.set(true)
        // Unconditional: a request still queued behind another must not start, and one running
        // must stop, or the next session's inference waits for audio nobody wants any more.
        ticket?.let { VoiceRuntime.engine(app).abort(it) }
        abandonFocus()
        if (_state.value !is State.Done && _state.value !is State.Failed) {
            _state.value = State.Idle
            trace?.let { it.outcome = "cancelled"; it.finish() }
            trace = null
        }
    }

    /** Back to [State.Idle] after a result has been shown. */
    fun reset() {
        if (!isActive) _state.value = State.Idle
    }

    private fun fail(error: VoiceError, detail: String = "") {
        StructuredLog.i(VoiceRuntime.TAG, "voice failed: $error $detail")
        trace?.let { it.outcome = error.name; it.finish() }
        trace = null
        _state.value = State.Failed(error, detail)
    }

    private fun cancelled() {
        trace?.let { it.outcome = "cancelled"; it.finish() }
        trace = null
        _state.value = State.Idle
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
