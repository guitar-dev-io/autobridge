// JNI bridge between dev.autobridge.whisper.WhisperNative and whisper.cpp.
//
// Deliberately thin: the Kotlin side owns threading (one inference at a time, never on the main
// thread), model selection and every policy decision. This file only turns Java arrays and
// strings into whisper.cpp calls and back, and holds the one piece of state that has to live next
// to the native context - the abort flag a second thread can raise while whisper_full() runs.
//
// A handle is a pointer to Session, returned to Kotlin as a jlong. 0 always means "no session".

#include <jni.h>

#include <atomic>
#include <cstring>
#include <algorithm>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define LOG_TAG "AutoBridgeWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { std::fprintf(stderr, __VA_ARGS__); std::fputc('\n', stderr); } while (0)
#define LOGW(...) LOGI(__VA_ARGS__)
#endif

namespace {

struct Session {
    whisper_context * ctx = nullptr;
    std::atomic<bool> abort{false};
    std::string language;
    float encode_ms = 0.f;
    float decode_ms = 0.f;
    float sample_ms = 0.f;
    float prompt_ms = 0.f;
};

Session * session_of(jlong handle) {
    return reinterpret_cast<Session *>(static_cast<intptr_t>(handle));
}

std::string to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

// NewStringUTF expects modified UTF-8 and aborts the VM on some byte sequences that are valid
// standard UTF-8 (4-byte code points) or that whisper may emit when a multi-byte character is
// split across tokens. Building the string from a byte array through java.lang.String's UTF-8
// decoder replaces malformed input instead of crashing.
jstring to_jstring(JNIEnv * env, const std::string & value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                            reinterpret_cast<const jbyte *>(value.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(string_class, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_class);
    return result;
}

#if defined(__ANDROID__)
void android_log(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) priority = ANDROID_LOG_INFO;
    __android_log_write(priority, LOG_TAG, text);
}
#endif

bool abort_requested(void * user_data) {
    auto * session = static_cast<Session *>(user_data);
    return session->abort.load(std::memory_order_relaxed);
}

bool encoder_may_begin(whisper_context *, whisper_state *, void * user_data) {
    return !abort_requested(user_data);
}

}  // namespace

extern "C" {

// Loads the ggml CPU backend best suited to this device from [libDir] (the app's native library
// directory) and returns how many backends are registered afterwards. Must run once before the
// first context is created; calling it again is harmless.
JNIEXPORT jint JNICALL
Java_dev_autobridge_whisper_WhisperNative_initBackends(JNIEnv * env, jclass, jstring lib_dir) {
#if defined(__ANDROID__)
    whisper_log_set(android_log, nullptr);
#endif
#if defined(AUTOBRIDGE_BACKEND_DL)
    std::string dir = to_string(env, lib_dir);
    if (ggml_backend_reg_count() == 0) {
        if (dir.empty()) ggml_backend_load_all();
        else ggml_backend_load_all_from_path(dir.c_str());
    }
#else
    (void) env;
    (void) lib_dir;
#endif
    const auto count = static_cast<jint>(ggml_backend_reg_count());
    LOGI("ggml backends registered: %d", count);
    return count;
}

JNIEXPORT jstring JNICALL
Java_dev_autobridge_whisper_WhisperNative_systemInfo(JNIEnv * env, jclass) {
    return to_jstring(env, whisper_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_dev_autobridge_whisper_WhisperNative_version(JNIEnv * env, jclass) {
    return to_jstring(env, whisper_version());
}

// Loads the model at [path]. Returns 0 when the file is missing or not a whisper model.
JNIEXPORT jlong JNICALL
Java_dev_autobridge_whisper_WhisperNative_initContext(JNIEnv * env, jclass, jstring path) {
    const std::string model_path = to_string(env, path);
    whisper_context_params params = whisper_context_default_params();
    // CPU only: no GPU backend is compiled in (see CMakeLists.txt).
    params.use_gpu = false;
    // Flash attention has a CPU path in ggml and cuts the encoder's attention cost; whisper.cpp
    // turns it on by default in current releases.
    params.flash_attn = true;
    whisper_context * ctx = whisper_init_from_file_with_params(model_path.c_str(), params);
    if (ctx == nullptr) {
        LOGW("whisper_init failed for %s", model_path.c_str());
        return 0;
    }
    auto * session = new Session();
    session->ctx = ctx;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

JNIEXPORT void JNICALL
Java_dev_autobridge_whisper_WhisperNative_freeContext(JNIEnv *, jclass, jlong handle) {
    Session * session = session_of(handle);
    if (session == nullptr) return;
    whisper_free(session->ctx);
    delete session;
}

// Asks a running transcribe() on [handle] to stop at the next checkpoint. Safe from any thread.
JNIEXPORT void JNICALL
Java_dev_autobridge_whisper_WhisperNative_abort(JNIEnv *, jclass, jlong handle) {
    Session * session = session_of(handle);
    if (session != nullptr) session->abort.store(true, std::memory_order_relaxed);
}

JNIEXPORT jboolean JNICALL
Java_dev_autobridge_whisper_WhisperNative_isMultilingual(JNIEnv *, jclass, jlong handle) {
    Session * session = session_of(handle);
    return session != nullptr && whisper_is_multilingual(session->ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_autobridge_whisper_WhisperNative_modelType(JNIEnv * env, jclass, jlong handle) {
    Session * session = session_of(handle);
    if (session == nullptr) return to_jstring(env, "");
    return to_jstring(env, whisper_model_type_readable(session->ctx));
}

JNIEXPORT jint JNICALL
Java_dev_autobridge_whisper_WhisperNative_modelFtype(JNIEnv *, jclass, jlong handle) {
    Session * session = session_of(handle);
    return session == nullptr ? -1 : whisper_model_ftype(session->ctx);
}

// Transcribes 16 kHz mono float PCM in [-1, 1]. Returns the text, or null when whisper failed or
// the call was aborted. [language] is an ISO code ("th", "en") or "auto".
JNIEXPORT jstring JNICALL
Java_dev_autobridge_whisper_WhisperNative_transcribe(
        JNIEnv * env, jclass, jlong handle, jfloatArray samples, jstring language,
        jboolean translate, jint threads, jstring prompt) {
    Session * session = session_of(handle);
    if (session == nullptr || samples == nullptr) return nullptr;

    const jsize count = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());

    const std::string lang = to_string(env, language);
    const std::string initial_prompt = to_string(env, prompt);

    session->abort.store(false, std::memory_order_relaxed);
    session->language.clear();

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.translate = translate == JNI_TRUE;
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.detect_language = false;
    // A voice command is a few seconds of speech: one segment, no timestamps, no carried context
    // from a previous command (which would bias this one towards repeating it).
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.initial_prompt = initial_prompt.empty() ? nullptr : initial_prompt.c_str();
    // The encoder always works on a 30 s window (1500 frames, 50 per second) however short the
    // recording, and for a 3 s command most of that work is silence. audio_ctx shrinks the window
    // to the speech that is there, rounded up and with headroom; it is the single biggest saving
    // for short audio. Floored at 512 frames (~10 s): narrower windows start to cost accuracy.
    const int frames = static_cast<int>((static_cast<long long>(count) * 50 + 15999) / 16000);
    params.audio_ctx = std::min(1500, std::max(512, ((frames + 64 + 63) / 64) * 64));
    // A command is a sentence. A cap stops a rare repetition loop from decoding to the limit.
    params.max_tokens = 96;
    params.abort_callback = abort_requested;
    params.abort_callback_user_data = session;
    params.encoder_begin_callback = encoder_may_begin;
    params.encoder_begin_callback_user_data = session;

    whisper_reset_timings(session->ctx);
    const int status = whisper_full(session->ctx, params, pcm.data(), static_cast<int>(pcm.size()));
    if (status != 0 || session->abort.load(std::memory_order_relaxed)) {
        LOGW("whisper_full returned %d (aborted=%d)", status, session->abort.load() ? 1 : 0);
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(session->ctx);
    for (int i = 0; i < segments; ++i) {
        const char * piece = whisper_full_get_segment_text(session->ctx, i);
        if (piece != nullptr) text += piece;
    }

    const int lang_id = whisper_full_lang_id(session->ctx);
    if (lang_id >= 0) {
        const char * code = whisper_lang_str(lang_id);
        if (code != nullptr) session->language = code;
    }
    if (whisper_timings * timings = whisper_get_timings(session->ctx)) {
        session->encode_ms = timings->encode_ms;
        session->decode_ms = timings->decode_ms;
        session->sample_ms = timings->sample_ms;
        session->prompt_ms = timings->prompt_ms;
    }
    return to_jstring(env, text);
}

// Language whisper used for the last transcribe() ("th", "en", ...), or "" when unknown.
JNIEXPORT jstring JNICALL
Java_dev_autobridge_whisper_WhisperNative_lastLanguage(JNIEnv * env, jclass, jlong handle) {
    Session * session = session_of(handle);
    return to_jstring(env, session == nullptr ? std::string() : session->language);
}

// Per-stage timings of the last transcribe(), in milliseconds: encode, decode, sample, prompt.
JNIEXPORT jfloatArray JNICALL
Java_dev_autobridge_whisper_WhisperNative_lastTimings(JNIEnv * env, jclass, jlong handle) {
    Session * session = session_of(handle);
    jfloatArray result = env->NewFloatArray(4);
    if (result == nullptr || session == nullptr) return result;
    const float values[4] = {session->encode_ms, session->decode_ms, session->sample_ms, session->prompt_ms};
    env->SetFloatArrayRegion(result, 0, 4, values);
    return result;
}

}  // extern "C"
