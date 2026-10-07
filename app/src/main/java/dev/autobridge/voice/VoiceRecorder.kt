package dev.autobridge.voice

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A microphone that delivers 16 kHz mono 16-bit PCM - the format whisper.cpp takes, so nothing is
 * resampled. Both sources below produce it natively.
 */
interface PcmSource {
    /** Starts capture. False when the microphone could not be opened. */
    fun start(): Boolean
    /** Reads up to [buffer].size samples; a negative value is a recorder error. */
    fun read(buffer: ShortArray): Int
    /** Stops and frees the microphone. Safe to call more than once. */
    fun release()
}

/** The audio of one listen session and why it ended. */
class VoiceRecording(
    val samples: ShortArray,
    val count: Int,
    val ending: Ending,
    val heardSpeech: Boolean
) {
    enum class Ending { END_OF_SPEECH, NO_SPEECH, MAX_DURATION, STOPPED, CANCELLED, INTERRUPTED, RECORDER_ERROR }

    val durationMs: Long get() = count * 1000L / WhisperEngine.SAMPLE_RATE
}

/**
 * Pulls PCM from a [PcmSource] until the [SilenceDetector] says the speaker is done, the user stops
 * it, or something takes the microphone away. Blocking; run it on a background thread.
 */
object VoiceRecorder {

    class Control {
        /** Stop now and keep what was heard (the user tapped the mic again). */
        val stop = AtomicBoolean(false)
        /** Stop now and throw it away. */
        val cancel = AtomicBoolean(false)
        /** Audio focus was lost or the app left the foreground. */
        val interrupted = AtomicBoolean(false)
    }

    fun record(
        source: PcmSource,
        detector: SilenceDetector,
        maxDurationMs: Int,
        control: Control,
        onLevel: (levelDb: Double, elapsedMs: Long) -> Unit = { _, _ -> }
    ): VoiceRecording {
        val capacity = (maxDurationMs.toLong() * WhisperEngine.SAMPLE_RATE / 1000).toInt()
        val samples = ShortArray(capacity)
        val frame = ShortArray(WhisperEngine.SAMPLE_RATE * SilenceDetector.FRAME_MS / 1000)
        var count = 0
        var ending = VoiceRecording.Ending.MAX_DURATION
        try {
            while (count < capacity) {
                if (control.cancel.get()) { ending = VoiceRecording.Ending.CANCELLED; break }
                if (control.interrupted.get()) { ending = VoiceRecording.Ending.INTERRUPTED; break }
                if (control.stop.get()) { ending = VoiceRecording.Ending.STOPPED; break }
                val read = source.read(frame)
                if (read < 0) { ending = VoiceRecording.Ending.RECORDER_ERROR; break }
                if (read == 0) continue
                val take = minOf(read, capacity - count)
                System.arraycopy(frame, 0, samples, count, take)
                count += take
                val decision = detector.accept(frame, take)
                onLevel(detector.lastLevelDb, count * 1000L / WhisperEngine.SAMPLE_RATE)
                when (decision) {
                    SilenceDetector.Decision.CONTINUE -> Unit
                    SilenceDetector.Decision.END_OF_SPEECH -> { ending = VoiceRecording.Ending.END_OF_SPEECH; break }
                    SilenceDetector.Decision.NO_SPEECH -> { ending = VoiceRecording.Ending.NO_SPEECH; break }
                    SilenceDetector.Decision.MAX_DURATION -> { ending = VoiceRecording.Ending.MAX_DURATION; break }
                }
            }
        } finally {
            source.release()
        }
        return VoiceRecording(samples, count, ending, detector.heardSpeech)
    }
}
