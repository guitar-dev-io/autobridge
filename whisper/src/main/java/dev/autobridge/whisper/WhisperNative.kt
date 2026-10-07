package dev.autobridge.whisper

/**
 * The JNI surface of whisper.cpp, exactly as `src/main/cpp/whisper_jni.cpp` exports it.
 *
 * Raw on purpose: a handle is a native pointer, nothing here is thread-safe, and nothing frees
 * itself. The app wraps this in one owner (`dev.autobridge.voice.WhisperEngine`) that serialises
 * every call onto a single background thread and releases the context when the model changes, so
 * no other class should call these directly.
 */
object WhisperNative {

    private const val LIBRARY = "autobridge_whisper"

    @Volatile
    private var loaded: Boolean? = null

    /** Why the library could not be loaded, for diagnostics; null when it loaded or was not tried. */
    @Volatile
    var loadError: String? = null
        private set

    /**
     * Loads the native library and the best ggml CPU backend for this device from [nativeLibDir]
     * (`ApplicationInfo.nativeLibraryDir`). Returns false when this device has no build of the
     * library (a 32-bit-only phone) or no backend could be registered. Idempotent and cheap after
     * the first call.
     */
    @Synchronized
    fun load(nativeLibDir: String?): Boolean {
        loaded?.let { return it }
        val result = try {
            System.loadLibrary(LIBRARY)
            val backends = initBackends(nativeLibDir.orEmpty())
            if (backends <= 0) loadError = "no ggml backend could be loaded from $nativeLibDir"
            backends > 0
        } catch (error: UnsatisfiedLinkError) {
            loadError = error.message ?: error.javaClass.simpleName
            false
        } catch (error: SecurityException) {
            loadError = error.message ?: error.javaClass.simpleName
            false
        }
        loaded = result
        return result
    }

    /** True once [load] has succeeded. */
    val isLoaded: Boolean get() = loaded == true

    @JvmStatic external fun initBackends(libDir: String): Int
    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun version(): String

    /** Returns a session handle, or 0 when the file is missing or not a whisper model. */
    @JvmStatic external fun initContext(modelPath: String): Long
    @JvmStatic external fun freeContext(handle: Long)

    /** Raises the abort flag a running [transcribe] checks; callable from any thread. */
    @JvmStatic external fun abort(handle: Long)

    @JvmStatic external fun isMultilingual(handle: Long): Boolean
    @JvmStatic external fun modelType(handle: Long): String
    @JvmStatic external fun modelFtype(handle: Long): Int

    /**
     * Transcribes 16 kHz mono PCM in [-1, 1]. [language] is an ISO 639-1 code or "auto".
     * Returns null when whisper failed or [abort] was called.
     */
    @JvmStatic external fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        translate: Boolean,
        threads: Int,
        prompt: String?
    ): String?

    @JvmStatic external fun lastLanguage(handle: Long): String

    /** encode, decode, sample, prompt - milliseconds, from the last [transcribe]. */
    @JvmStatic external fun lastTimings(handle: Long): FloatArray
}
