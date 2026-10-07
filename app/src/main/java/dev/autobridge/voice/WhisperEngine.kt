package dev.autobridge.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** The native calls [WhisperEngine] needs; the app's is [NativeWhisperBackend]. */
interface WhisperBackend {
    /** Loads the native library; false when this device cannot run it. */
    fun prepare(): Boolean
    fun unavailableReason(): String
    fun load(path: String): Long
    fun free(handle: Long)
    fun abort(handle: Long)
    fun transcribe(handle: Long, pcm: FloatArray, language: String, translate: Boolean, threads: Int, prompt: String?): String?
    fun lastLanguage(handle: Long): String
    /** encode, decode milliseconds of the last transcribe. */
    fun lastTimings(handle: Long): FloatArray
    /** Native heap in use, for the benchmark screen; -1 when unknown. */
    fun nativeHeapBytes(): Long = -1L
    fun systemInfo(): String = ""
}

/** One finished transcription, with what the test screen reports about it. */
data class Transcription(
    val text: String,
    val language: String,
    val modelId: String,
    val audioMs: Long,
    val processingMs: Long,
    val encodeMs: Float,
    val decodeMs: Float,
    val threads: Int,
    /** How long the model took to load for this request; 0 when it was already loaded. */
    val modelLoadMs: Long
) {
    /** Real-time factor: processing time over audio time. Below 1 is faster than speech. */
    val realTimeFactor: Double get() = if (audioMs > 0) processingMs.toDouble() / audioMs else 0.0
}

class WhisperException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { UNSUPPORTED_DEVICE, MODEL_LOAD_FAILED, TRANSCRIPTION_FAILED, ABORTED }
}

/**
 * Owns the one whisper.cpp context the app keeps in memory.
 *
 * Loading a model costs from a fraction of a second (tiny) to a few seconds (small) and tens to
 * hundreds of megabytes, so it happens once and the context is reused for every request after it -
 * pressing the microphone again costs nothing. The context changes only when the selected model
 * does: the old one is freed *before* the new one is loaded, so the two are never resident at once.
 * [release] frees it outright, which the app does on memory pressure and when a model is deleted.
 *
 * Every native call runs on one dedicated thread ([dispatcher]). That serialises load, transcribe
 * and free without a lock (a whisper context is not thread-safe), and keeps all of it off the main
 * thread. [abort] is the one call that does not wait: it raises a flag the running transcription
 * checks.
 */
class WhisperEngine(
    private val backend: WhisperBackend,
    private val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AutoBridge-Whisper").apply { priority = Thread.NORM_PRIORITY + 1 }
    }.asCoroutineDispatcher(),
    private val clock: () -> Long = System::nanoTime
) {
    sealed interface State {
        data object Unloaded : State
        data class Loading(val modelId: String) : State
        data class Ready(val modelId: String, val loadMs: Long, val memoryBytes: Long) : State
        data class Failed(val modelId: String?, val reason: WhisperException.Reason) : State
    }

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    // Only touched on [dispatcher].
    private var handle = 0L
    private var loadedId: String? = null
    private var loadedPath: String? = null
    private var pendingLoadMs = 0L

    @Volatile
    private var activeHandle = 0L

    @Volatile
    private var busy = false

    val isBusy: Boolean get() = busy

    val loadedModelId: String? get() = (state.value as? State.Ready)?.modelId

    /**
     * Makes [model] (stored at [file]) the loaded model, freeing any other first. Returns how long
     * the load took, or 0 when it was already loaded.
     */
    suspend fun ensureLoaded(model: WhisperModelInfo, file: File): Long = withContext(dispatcher) {
        loadLocked(model, file)
    }

    private fun loadLocked(model: WhisperModelInfo, file: File): Long {
        if (handle != 0L && loadedId == model.id && loadedPath == file.path) return 0L
        if (!backend.prepare()) {
            _state.value = State.Failed(model.id, WhisperException.Reason.UNSUPPORTED_DEVICE)
            throw WhisperException(WhisperException.Reason.UNSUPPORTED_DEVICE, backend.unavailableReason())
        }
        freeLocked()
        _state.value = State.Loading(model.id)
        val heapBefore = backend.nativeHeapBytes()
        val started = clock()
        val loaded = backend.load(file.path)
        val elapsed = (clock() - started) / 1_000_000
        if (loaded == 0L) {
            _state.value = State.Failed(model.id, WhisperException.Reason.MODEL_LOAD_FAILED)
            throw WhisperException(WhisperException.Reason.MODEL_LOAD_FAILED, "could not load ${file.name}")
        }
        handle = loaded
        activeHandle = loaded
        loadedId = model.id
        loadedPath = file.path
        pendingLoadMs = elapsed
        val heapAfter = backend.nativeHeapBytes()
        val memory = if (heapBefore >= 0 && heapAfter >= 0) heapAfter - heapBefore else -1L
        _state.value = State.Ready(model.id, elapsed, memory)
        return elapsed
    }

    /**
     * Transcribes 16 kHz mono [pcm] with [model], loading it first if it is not the one in memory.
     */
    suspend fun transcribe(
        model: WhisperModelInfo,
        file: File,
        pcm: FloatArray,
        language: VoiceLanguage,
        translate: Boolean,
        threads: Int,
        prompt: String?
    ): Transcription = withContext(dispatcher) {
        busy = true
        try {
            loadLocked(model, file)
            // A load done ahead of time (while the user was still speaking) is reported with the
            // first request that used it, then not again.
            val loadMs = pendingLoadMs.also { pendingLoadMs = 0L }
            val started = clock()
            val text = backend.transcribe(handle, pcm, language.code, translate, threads, prompt)
            val elapsed = (clock() - started) / 1_000_000
            if (text == null) {
                throw WhisperException(WhisperException.Reason.TRANSCRIPTION_FAILED, "whisper returned no result")
            }
            val timings = backend.lastTimings(handle)
            Transcription(
                text = text,
                language = backend.lastLanguage(handle),
                modelId = model.id,
                audioMs = pcm.size * 1000L / SAMPLE_RATE,
                processingMs = elapsed,
                encodeMs = timings.getOrElse(0) { 0f },
                decodeMs = timings.getOrElse(1) { 0f },
                threads = threads,
                modelLoadMs = loadMs
            )
        } finally {
            busy = false
        }
    }

    /** Stops a running [transcribe] early; it then throws. Safe from any thread. */
    fun abort() {
        val current = activeHandle
        if (current != 0L && busy) backend.abort(current)
    }

    /** Frees the native context. The next request loads it again. */
    suspend fun release() = withContext(dispatcher) { freeLocked() }

    private fun freeLocked() {
        if (handle != 0L) {
            backend.free(handle)
            handle = 0L
            activeHandle = 0L
        }
        loadedId = null
        loadedPath = null
        pendingLoadMs = 0L
        _state.value = State.Unloaded
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
