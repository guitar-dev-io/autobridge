# WhisperNative's external functions are bound by JNI name (Java_dev_autobridge_whisper_WhisperNative_*),
# an edge R8 cannot see. Keep the class and its native methods under their own names.
-keep class dev.autobridge.whisper.WhisperNative { native <methods>; }
