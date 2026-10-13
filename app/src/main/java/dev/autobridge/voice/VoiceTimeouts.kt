package dev.autobridge.voice

/** How long a transcription may take before it is given up, pure so it is tested on the JVM. */
object VoiceTimeouts {
    /** Never less than this: a cold model load and warm-up alone can take several seconds. */
    const val MIN_TRANSCRIBE_MS = 15_000L

    /** Never more than this: a driver will not wait longer for a command. */
    const val MAX_TRANSCRIBE_MS = 30_000L

    /**
     * The limit for [audioMs] of recorded audio: three times the audio, within
     * [MIN_TRANSCRIBE_MS]..[MAX_TRANSCRIBE_MS]. A healthy run on base takes well under the audio's
     * own length, so this only ever stops a run that has gone wrong.
     */
    fun transcribeLimitMs(audioMs: Long): Long =
        (audioMs.coerceAtLeast(0) * 3).coerceIn(MIN_TRANSCRIBE_MS, MAX_TRANSCRIBE_MS)
}
