plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Offline speech-to-text: whisper.cpp (git submodule at third_party/whisper.cpp) built for the
// device, plus the JNI binding the app's voice feature calls. Nothing else lives here; model
// management, recording and every UI are in :app, which keeps this module the only one that needs
// the NDK and CMake.
//
// A fresh clone needs the submodule before the first build:
//
//   git submodule update --init --recursive
//
// arm64-v8a only. That is every phone a head unit is driven from today, and the one ABI ggml's
// runtime CPU dispatch (GGML_CPU_ALL_VARIANTS, see src/main/cpp/CMakeLists.txt) is published for
// on Android. A 32-bit-only phone gets no native library; WhisperNative.isAvailable is false there
// and the app says offline voice recognition is not supported on the device instead of crashing.
//
// Offline voice is parked (VoiceSettings.OFFLINE_WHISPER in :app), so by default the native
// library is not built and the APK carries no whisper.cpp or ggml: this module is then only the
// Kotlin binding, and WhisperNative.load() returns false. `-Pautobridge.whisperNative=true`
// builds it again, which needs the submodule, the NDK and CMake below.
val buildWhisperNative = providers.gradleProperty("autobridge.whisperNative")
    .map { it.toBoolean() }
    .getOrElse(false)

android {
    namespace = "dev.autobridge.whisper"
    compileSdk = 36
    if (buildWhisperNative) {
        // The NDK and CMake whisper.cpp/ggml's own Android example builds with. ggml's Android CPU
        // variants go up to armv9.2 with SME, which needs a recent clang; AGP's default NDK is
        // older. AGP installs both on first build when the SDK licences are accepted (CI's
        // setup-android does).
        ndkVersion = "29.0.13113456"
    }

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
        if (buildWhisperNative) {
            externalNativeBuild {
                cmake {
                    // Always optimised, debug APK included: an -O0 ggml is around ten times slower,
                    // which makes a debug build useless for judging a model and turns a two-second
                    // command into a twenty-second wait.
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                    arguments += "-DAUTOBRIDGE_CPU_VARIANTS=ON"
                }
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    if (buildWhisperNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
