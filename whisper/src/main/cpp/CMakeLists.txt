# Offline speech-to-text: whisper.cpp plus the small JNI bridge AutoBridge talks to.
#
# whisper.cpp is a git submodule (whisper/third_party/whisper.cpp, pinned to a release tag) and is
# built with its own CMake through add_subdirectory, so a version bump is a submodule bump and
# nothing here lists its sources.
#
# CPU dispatch. Android arm64 SoCs differ in what they can run: a Cortex-A53 phone has no dot
# product instructions, a recent one has dotprod, fp16 and i8mm. One build for the lowest of them
# runs everywhere at roughly half the speed of the newer cores; one build for the newer cores
# crashes with SIGILL on the older ones. ggml solves this upstream: GGML_BACKEND_DL with
# GGML_CPU_ALL_VARIANTS builds one CPU backend per feature level (libggml-cpu-android_armv8.*.so)
# and ggml_backend_load_all_from_path() loads the best one the device supports at runtime. That is
# what the upstream Android example does, and what this does. The libraries have to be on disk to
# be scanned, which is why :app packages native libraries extracted (useLegacyPackaging).
cmake_minimum_required(VERSION 3.22.1)

project(autobridge_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../third_party/whisper.cpp")
if (NOT EXISTS "${WHISPER_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp sources are missing at ${WHISPER_ROOT}. "
        "Run: git submodule update --init --recursive")
endif ()

# Only the library: no examples, tests, server, curl or SDL.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
# The submodule is pinned to a release tag; report it as that release, not "-dev".
set(WHISPER_BUILD_IS_DEV   OFF CACHE BOOL "" FORCE)

# Never tune for the build machine; the variants below carry the target features instead.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ggml's own thread pool. OpenMP on Android means shipping libomp.so next to everything else for
# no measurable gain on a transcription of a few seconds.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# KleidiAI would be fetched from the network at configure time; keep the build offline.
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
# No GPU backend is built (Vulkan needs shader compilation at build time and is not reliable
# across Android GPU drivers yet), so the app offers no GPU switch either.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
if (NOT DEFINED AUTOBRIDGE_CPU_VARIANTS)
    set(AUTOBRIDGE_CPU_VARIANTS ON)
endif ()
if (AUTOBRIDGE_CPU_VARIANTS)
    set(GGML_BACKEND_DL       ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
endif ()

add_subdirectory("${WHISPER_ROOT}" whisper.cpp)

add_library(autobridge_whisper SHARED whisper_jni.cpp)

target_include_directories(autobridge_whisper PRIVATE
    "${WHISPER_ROOT}/include"
    "${WHISPER_ROOT}/ggml/include")

if (AUTOBRIDGE_CPU_VARIANTS)
    target_compile_definitions(autobridge_whisper PRIVATE AUTOBRIDGE_BACKEND_DL=1)
endif ()

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(autobridge_whisper PRIVATE whisper ggml ggml-base ${LOG_LIB})
else ()
    # Host build, used only to exercise this bridge from a desktop JVM in tests.
    find_package(JNI REQUIRED)
    target_include_directories(autobridge_whisper PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(autobridge_whisper PRIVATE whisper ggml ggml-base)
endif ()
