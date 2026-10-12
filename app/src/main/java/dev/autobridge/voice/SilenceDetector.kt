package dev.autobridge.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides when a listen session is over, from the level of the audio alone.
 *
 * Push-to-talk without the push: the user taps the microphone once and talks, and recording should
 * end shortly after they stop, not on a fixed timer. This is an energy detector rather than a
 * model: it measures each 20 ms frame, learns the room's noise floor from the quietest frames, and
 * calls a frame speech when it stands well above that floor. A car cabin at speed is loud but
 * steady, which is exactly what a floor-relative threshold copes with and an absolute one does not.
 *
 * When it decides the speaker is done is what makes listening feel quick or sluggish, so the
 * wait is not one fixed number:
 * - **Short commands end sooner.** "หยุด", "YouTube", "ถัดไป" are over in well under a second and
 *   nobody pauses inside them, so after a short utterance the wait is a fraction of the setting.
 *   A typical command ("เปิดยูทูบมิวสิค", "เล่นเพลงบอดี้สแลม", one to two seconds of speech) gets
 *   most of it, and only a longer sentence gets the whole setting, so a breath between words does
 *   not cut it off. No tier goes below [MIN_TIMEOUT_MS] or [MIN_MEDIUM_TIMEOUT_MS], which stay above
 *   the pauses Thai speakers leave between words, so a sentence-final "ครับ"/"ค่ะ" is not cut.
 * - **Blips do not restart the wait.** A click, a breath or the indicator tick after the speaker
 *   stopped is a frame or two above the floor; only a run of speech frames ([ONSET_FRAMES]) counts
 *   as talking again, so the wait is not reset by noise and the command is sent on time.
 *
 * Pure Kotlin and frame-driven, so it is tested by feeding it synthetic frames.
 */
class SilenceDetector(
    private val silenceTimeoutMs: Int,
    private val maxDurationMs: Int,
    private val noSpeechTimeoutMs: Int = DEFAULT_NO_SPEECH_MS,
    private val sampleRate: Int = WhisperEngine.SAMPLE_RATE,
    /** Share of the setting a typical command waits; 1.0 is the rule before the command tier. */
    private val mediumFactor: Double = MEDIUM_UTTERANCE_FACTOR
) {
    enum class Decision { CONTINUE, END_OF_SPEECH, NO_SPEECH, MAX_DURATION }

    private var elapsedMs = 0.0
    private var speechMs = 0.0
    private var silenceSinceSpeechMs = 0.0
    private var speechRun = 0
    private var noiseFloorDb = Double.NaN
    private var speechStartedAtMs = -1.0

    /** Level of the last frame, in dBFS (0 is full scale, -90 is silence). */
    var lastLevelDb: Double = SILENCE_DB
        private set

    val heardSpeech: Boolean get() = speechMs >= MIN_SPEECH_MS

    /** Audio time at which the first speech frame began, or -1 before any speech. For latency traces. */
    val speechStartMs: Long get() = speechStartedAtMs.toLong()

    /** Silence counted since the speaker last talked; at an endpoint, how long the wait was. */
    val silenceMs: Long get() = silenceSinceSpeechMs.toLong()

    /** Feeds [count] 16-bit samples from [frame] and says whether to keep recording. */
    fun accept(frame: ShortArray, count: Int = frame.size): Decision {
        if (count <= 0) return Decision.CONTINUE
        val level = levelDb(frame, count)
        lastLevelDb = level
        val frameMs = count * 1000.0 / sampleRate
        elapsedMs += frameMs

        if (noiseFloorDb.isNaN()) noiseFloorDb = level
        val threshold = max(noiseFloorDb + SPEECH_ABOVE_FLOOR_DB, MIN_SPEECH_DB)
        val isSpeech = level >= threshold
        // The floor follows quiet frames down at once and rises slowly - and far more slowly while
        // someone is talking - so a long sentence does not drag it up until the speaker sounds like
        // background, while noise that really did get louder (the car sped up) is still learned.
        noiseFloorDb = when {
            level < noiseFloorDb -> level
            isSpeech -> noiseFloorDb + (level - noiseFloorDb) * FLOOR_RISE_DURING_SPEECH
            else -> noiseFloorDb + (level - noiseFloorDb) * FLOOR_RISE
        }

        if (isSpeech) {
            if (speechStartedAtMs < 0) speechStartedAtMs = elapsedMs - frameMs
            speechMs += frameMs
            speechRun++
        } else {
            speechRun = 0
        }
        // Talking resumes only after a short run of speech frames; a single loud frame after the
        // speaker stopped is a click or a breath and keeps counting towards the end.
        if (speechRun >= ONSET_FRAMES || (isSpeech && !heardSpeech)) {
            silenceSinceSpeechMs = 0.0
        } else if (heardSpeech) {
            silenceSinceSpeechMs += frameMs
        }

        return when {
            heardSpeech && silenceSinceSpeechMs >= currentTimeoutMs() -> Decision.END_OF_SPEECH
            elapsedMs >= maxDurationMs -> Decision.MAX_DURATION
            !heardSpeech && elapsedMs >= noSpeechTimeoutMs -> Decision.NO_SPEECH
            else -> Decision.CONTINUE
        }
    }

    /**
     * The silence that ends this utterance: a fraction of the setting after a short or typical
     * command, the whole setting once the speaker has said more than a sentence's worth.
     */
    fun currentTimeoutMs(): Int = when {
        speechMs < SHORT_UTTERANCE_MS -> scaled(SHORT_UTTERANCE_FACTOR, MIN_TIMEOUT_MS)
        speechMs < MEDIUM_UTTERANCE_MS -> scaled(mediumFactor, MIN_MEDIUM_TIMEOUT_MS)
        else -> silenceTimeoutMs
    }

    private fun scaled(factor: Double, floor: Int): Int =
        (silenceTimeoutMs * factor).toInt().coerceAtLeast(floor).coerceAtMost(silenceTimeoutMs)

    companion object {
        const val DEFAULT_NO_SPEECH_MS = 5_000
        /** Speech shorter than this is a one- or two-word command. */
        const val SHORT_UTTERANCE_MS = 700.0
        /** ...and is sent after this share of the silence setting. */
        const val SHORT_UTTERANCE_FACTOR = 0.6
        /** Never end sooner than this after the last word. */
        const val MIN_TIMEOUT_MS = 450
        /** Speech shorter than this is a command, not a sentence: one to two seconds of words. */
        const val MEDIUM_UTTERANCE_MS = 2_000.0
        /** ...sent after this share of the setting (1.2 s -> 0.96 s by default). */
        const val MEDIUM_UTTERANCE_FACTOR = 0.8
        /** ...and never sooner than this, above the longest pause between Thai words in a command. */
        const val MIN_MEDIUM_TIMEOUT_MS = 600
        /** Consecutive speech frames (60 ms) that count as talking again after a pause. */
        const val ONSET_FRAMES = 3
        const val FRAME_MS = 20
        const val SILENCE_DB = -90.0
        /** Speech has to clear the floor by this much. */
        const val SPEECH_ABOVE_FLOOR_DB = 12.0
        /** ...and be at least this loud, so a dead-silent room does not make breath count as speech. */
        const val MIN_SPEECH_DB = -48.0
        /** Shorter bursts than this (a click, a cough) are not the start of a command. */
        const val MIN_SPEECH_MS = 200.0
        private const val FLOOR_RISE = 0.02
        private const val FLOOR_RISE_DURING_SPEECH = 0.003

        fun levelDb(frame: ShortArray, count: Int = frame.size): Double {
            if (count <= 0) return SILENCE_DB
            var sum = 0.0
            for (i in 0 until count) {
                val sample = frame[i] / 32768.0
                sum += sample * sample
            }
            val rms = sqrt(sum / count)
            return if (rms <= 0.0) SILENCE_DB else max(SILENCE_DB, 20 * log10(rms))
        }
    }
}

/** 16-bit PCM to the float format whisper.cpp takes. */
object PcmConversion {

    /** Whisper refuses (or hallucinates on) less than a second; shorter input is padded with silence. */
    const val MIN_SAMPLES = WhisperEngine.SAMPLE_RATE

    fun toFloat(samples: ShortArray, count: Int = samples.size): FloatArray {
        val out = FloatArray(maxOf(count, MIN_SAMPLES))
        for (i in 0 until count) out[i] = samples[i] / 32768f
        return out
    }

    /** Little-endian 16-bit bytes (what CarAudioRecord delivers) to samples. */
    fun bytesToShorts(bytes: ByteArray, count: Int, into: ShortArray): Int {
        val samples = minOf(count / 2, into.size)
        for (i in 0 until samples) {
            val lo = bytes[2 * i].toInt() and 0xff
            val hi = bytes[2 * i + 1].toInt()
            into[i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }
}

/**
 * Cleans what Whisper hands back before it is shown or parsed: drops the non-speech annotations it
 * emits on noise ("[BLANK_AUDIO]", "(music)", "♪"), collapses whitespace, and treats a transcript
 * that is only the recognition prompt echoed back as nothing heard.
 */
object TranscriptCleaner {
    private val ANNOTATION = Regex("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*|[♪♫]")
    private val SPACES = Regex("\\s+")

    fun clean(raw: String, prompt: String? = null): String {
        val text = raw.replace(ANNOTATION, " ").replace(SPACES, " ").trim()
            .trim('"', '\'', '“', '”')
            .trimEnd('.', '。', '!', '?')
            .trim()
        // Only a long echo counts: "YouTube" alone is in the prompt too, and is a real command.
        if (prompt != null && text.length > prompt.length / 2 && prompt.contains(text)) return ""
        return text
    }
}
