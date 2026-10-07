package dev.autobridge.voice

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import dev.autobridge.logging.StructuredLog
import dev.autobridge.whisper.WhisperNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The process-wide voice objects: one [WhisperModelStore] and one [WhisperEngine], shared by the
 * settings screens, the Home microphone and the car Agent so a model loaded by one is reused by the
 * others and a download started on one screen is visible on another.
 */
object VoiceRuntime {

    /** `filesDir/whisper/models`: app-private, not a cache the system may empty, not backed up. */
    const val MODELS_DIRECTORY = "whisper/models"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var store: WhisperModelStore? = null

    @Volatile
    private var engine: WhisperEngine? = null

    fun modelStore(context: Context): WhisperModelStore = store ?: synchronized(this) {
        store ?: WhisperModelStore(
            root = File(context.applicationContext.filesDir, MODELS_DIRECTORY),
            selection = VoiceSettings.selection(context),
            scope = scope,
            log = { StructuredLog.i(TAG, it) }
        ).also { store = it }
    }

    fun engine(context: Context): WhisperEngine = engine ?: synchronized(this) {
        engine ?: WhisperEngine(NativeWhisperBackend(context.applicationContext)).also { engine = it }
    }

    /** True when this device has the native library (an arm64 phone). Loads it on first call. */
    fun isSupported(context: Context): Boolean =
        WhisperNative.load(context.applicationContext.applicationInfo.nativeLibraryDir)

    /**
     * Frees the loaded model when the system is short of memory and the app is not using it. A
     * model is tens to hundreds of megabytes of native heap, the first thing worth giving back; the
     * next voice request loads it again.
     */
    fun onTrimMemory(level: Int) {
        val current = engine ?: return
        @Suppress("DEPRECATION") // RUNNING_LOW is still delivered to foreground apps.
        val release = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        if (release && !current.isBusy && current.loadedModelId != null) {
            StructuredLog.i(TAG, "releasing whisper model on trim level $level")
            scope.launch { current.release() }
        }
    }

    /** Drops the loaded context if it is [modelId] - before that model's file is deleted. */
    fun releaseIfLoaded(context: Context, modelId: String) {
        val current = engine(context)
        if (current.loadedModelId == modelId) scope.launch { current.release() }
    }

    const val TAG = "VOICE"
}

/** [WhisperBackend] over the JNI binding in :whisper. */
class NativeWhisperBackend(private val context: Context) : WhisperBackend {
    override fun prepare(): Boolean = WhisperNative.load(context.applicationInfo.nativeLibraryDir)
    override fun unavailableReason(): String = WhisperNative.loadError ?: "native library unavailable"
    override fun load(path: String): Long = WhisperNative.initContext(path)
    override fun free(handle: Long) = WhisperNative.freeContext(handle)
    override fun abort(handle: Long) = WhisperNative.abort(handle)
    override fun transcribe(
        handle: Long,
        pcm: FloatArray,
        language: String,
        translate: Boolean,
        threads: Int,
        prompt: String?
    ): String? = WhisperNative.transcribe(handle, pcm, language, translate, threads, prompt)
    override fun lastLanguage(handle: Long): String = WhisperNative.lastLanguage(handle)
    override fun lastTimings(handle: Long): FloatArray = WhisperNative.lastTimings(handle)
    override fun nativeHeapBytes(): Long = Debug.getNativeHeapAllocatedSize()
    override fun systemInfo(): String = if (WhisperNative.isLoaded) WhisperNative.systemInfo() else ""
}
