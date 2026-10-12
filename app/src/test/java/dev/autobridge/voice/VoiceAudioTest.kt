package dev.autobridge.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceAudioTest {

    private val frameSamples = WhisperEngine.SAMPLE_RATE * SilenceDetector.FRAME_MS / 1000

    /** One 20 ms frame: a 220 Hz tone at [amplitude] (0..1) plus a little deterministic noise. */
    private fun frame(amplitude: Double, noise: Double = 0.002, seed: Int = 0): ShortArray {
        val random = java.util.Random(seed.toLong())
        return ShortArray(frameSamples) { i ->
            val tone = amplitude * sin(2 * PI * 220 * i / WhisperEngine.SAMPLE_RATE)
            val hiss = noise * (random.nextDouble() * 2 - 1)
            ((tone + hiss).coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    private fun framesFor(ms: Int) = ms / SilenceDetector.FRAME_MS

    @Test fun `speech then silence ends after the silence timeout`() {
        val detector = SilenceDetector(silenceTimeoutMs = 1_000, maxDurationMs = 10_000)
        repeat(framesFor(300)) { assertEquals(SilenceDetector.Decision.CONTINUE, detector.accept(frame(0.0, seed = it))) }
        // A sentence (over two seconds of speech) waits for the whole setting.
        repeat(framesFor(2_200)) { assertEquals(SilenceDetector.Decision.CONTINUE, detector.accept(frame(0.3, seed = it))) }
        assertTrue(detector.heardSpeech)
        var decision = SilenceDetector.Decision.CONTINUE
        var silentFrames = 0
        while (decision == SilenceDetector.Decision.CONTINUE && silentFrames < 200) {
            decision = detector.accept(frame(0.0, seed = silentFrames))
            silentFrames++
        }
        assertEquals(SilenceDetector.Decision.END_OF_SPEECH, decision)
        assertEquals(framesFor(1_000), silentFrames)
    }

    @Test fun `a short command is sent sooner than a sentence`() {
        val detector = SilenceDetector(silenceTimeoutMs = 1_200, maxDurationMs = 10_000)
        repeat(framesFor(300)) { detector.accept(frame(0.0, seed = it)) }
        repeat(framesFor(500)) { detector.accept(frame(0.3, seed = it)) }
        var silentFrames = 0
        var decision = SilenceDetector.Decision.CONTINUE
        while (decision == SilenceDetector.Decision.CONTINUE && silentFrames < 200) {
            decision = detector.accept(frame(0.0, seed = silentFrames))
            silentFrames++
        }
        assertEquals(SilenceDetector.Decision.END_OF_SPEECH, decision)
        assertEquals(framesFor(720), silentFrames)
    }

    @Test fun `a click after the speaker stopped does not restart the wait`() {
        val detector = SilenceDetector(silenceTimeoutMs = 1_000, maxDurationMs = 10_000)
        repeat(framesFor(300)) { detector.accept(frame(0.0, seed = it)) }
        repeat(framesFor(1_200)) { detector.accept(frame(0.3, seed = it)) }
        repeat(framesFor(500)) { detector.accept(frame(0.0, seed = it)) }
        detector.accept(frame(0.8, seed = 1)) // one loud frame: a click
        var silentFrames = 0
        var decision = SilenceDetector.Decision.CONTINUE
        while (decision == SilenceDetector.Decision.CONTINUE && silentFrames < 200) {
            decision = detector.accept(frame(0.0, seed = silentFrames))
            silentFrames++
        }
        assertEquals(SilenceDetector.Decision.END_OF_SPEECH, decision)
        // A 1.2 s command waits 800 ms of the 1 s setting: 500 ms before the click, 20 ms of
        // click, so 280 ms more ends it.
        assertEquals(800, detector.currentTimeoutMs())
        assertEquals(framesFor(280), silentFrames)
    }

    @Test fun `steady cabin noise is not speech, speech above it is`() {
        val detector = SilenceDetector(silenceTimeoutMs = 800, maxDurationMs = 10_000)
        // Loud, steady road noise...
        repeat(framesFor(1_000)) { detector.accept(frame(0.0, noise = 0.05, seed = it)) }
        assertFalse(detector.heardSpeech)
        // ...and a voice clearly above it.
        repeat(framesFor(500)) { detector.accept(frame(0.5, noise = 0.05, seed = it)) }
        assertTrue(detector.heardSpeech)
    }

    @Test fun `a long sentence is not mistaken for background`() {
        // Five seconds of continuous speech must not raise the noise floor until the speaker
        // reads as silence and the session ends mid-sentence.
        val detector = SilenceDetector(silenceTimeoutMs = 800, maxDurationMs = 10_000)
        repeat(framesFor(300)) { detector.accept(frame(0.0, seed = it)) }
        repeat(framesFor(5_000)) {
            assertEquals("frame $it", SilenceDetector.Decision.CONTINUE, detector.accept(frame(0.3, seed = it)))
        }
    }

    @Test fun `nothing said ends with no speech`() {
        val detector = SilenceDetector(silenceTimeoutMs = 1_000, maxDurationMs = 10_000, noSpeechTimeoutMs = 2_000)
        var decision = SilenceDetector.Decision.CONTINUE
        var n = 0
        while (decision == SilenceDetector.Decision.CONTINUE) decision = detector.accept(frame(0.0, seed = n++))
        assertEquals(SilenceDetector.Decision.NO_SPEECH, decision)
        assertEquals(framesFor(2_000), n)
    }

    @Test fun `a click is not the start of a command`() {
        val detector = SilenceDetector(silenceTimeoutMs = 1_000, maxDurationMs = 10_000)
        repeat(framesFor(400)) { detector.accept(frame(0.0, seed = it)) }
        repeat(3) { detector.accept(frame(0.8, seed = it)) } // 60 ms
        assertFalse(detector.heardSpeech)
    }

    /** Feeds silence until the detector decides; returns how many silent frames that took. */
    private fun silenceUntilDecision(detector: SilenceDetector, limit: Int = 300): Pair<SilenceDetector.Decision, Int> {
        var decision = SilenceDetector.Decision.CONTINUE
        var silentFrames = 0
        while (decision == SilenceDetector.Decision.CONTINUE && silentFrames < limit) {
            decision = detector.accept(frame(0.0, seed = silentFrames))
            silentFrames++
        }
        return decision to silentFrames
    }

    @Test fun `a typical command waits most of the setting, not all of it`() {
        val detector = SilenceDetector(silenceTimeoutMs = 1_200, maxDurationMs = 10_000)
        repeat(framesFor(300)) { detector.accept(frame(0.0, seed = it)) }
        repeat(framesFor(1_200)) { detector.accept(frame(0.3, seed = it)) }
        assertEquals(960, detector.currentTimeoutMs())
        val (decision, frames) = silenceUntilDecision(detector)
        assertEquals(SilenceDetector.Decision.END_OF_SPEECH, decision)
        assertEquals(framesFor(960), frames)
        assertEquals(960L, detector.silenceMs)
    }

    @Test fun `no tier ends sooner than its floor`() {
        // 0.8 of 600 would be 480 ms: below the gap between Thai words, so it stays at 600.
        val medium = SilenceDetector(silenceTimeoutMs = 600, maxDurationMs = 10_000)
        repeat(framesFor(300)) { medium.accept(frame(0.0, seed = it)) }
        repeat(framesFor(1_000)) { medium.accept(frame(0.3, seed = it)) }
        assertEquals(SilenceDetector.MIN_MEDIUM_TIMEOUT_MS, medium.currentTimeoutMs())
        val short = SilenceDetector(silenceTimeoutMs = 600, maxDurationMs = 10_000)
        repeat(framesFor(300)) { short.accept(frame(0.0, seed = it)) }
        repeat(framesFor(300)) { short.accept(frame(0.3, seed = it)) }
        assertEquals(SilenceDetector.MIN_TIMEOUT_MS, short.currentTimeoutMs())
    }

    @Test fun `a quiet sentence-final particle after a pause is kept`() {
        // "เปิดยูทูบ ... ครับ": the polite particle comes after a pause and is much softer.
        val detector = SilenceDetector(silenceTimeoutMs = 1_200, maxDurationMs = 10_000)
        repeat(framesFor(300)) { detector.accept(frame(0.0, seed = it)) }
        repeat(framesFor(900)) { assertEquals(SilenceDetector.Decision.CONTINUE, detector.accept(frame(0.3, seed = it))) }
        repeat(framesFor(450)) { assertEquals(SilenceDetector.Decision.CONTINUE, detector.accept(frame(0.0, seed = it))) }
        repeat(framesFor(240)) { assertEquals(SilenceDetector.Decision.CONTINUE, detector.accept(frame(0.03, seed = it))) }
        val (decision, frames) = silenceUntilDecision(detector)
        assertEquals(SilenceDetector.Decision.END_OF_SPEECH, decision)
        // The wait restarted at the particle: it was heard as speech, not as the tail of silence.
        assertEquals(framesFor(detector.currentTimeoutMs()), frames)
    }

    @Test fun `a pause inside a sentence does not end it`() {
        val detector = SilenceDetector(silenceTimeoutMs = 1_200, maxDurationMs = 10_000)
        repeat(framesFor(300)) { detector.accept(frame(0.0, seed = it)) }
        repeat(framesFor(800)) { detector.accept(frame(0.3, seed = it)) }
        // Hesitation: 700 ms is below the 960 ms the command tier waits.
        repeat(framesFor(700)) { assertEquals(SilenceDetector.Decision.CONTINUE, detector.accept(frame(0.0, seed = it))) }
        repeat(framesFor(600)) { assertEquals(SilenceDetector.Decision.CONTINUE, detector.accept(frame(0.3, seed = it))) }
        assertEquals(SilenceDetector.Decision.END_OF_SPEECH, silenceUntilDecision(detector).first)
    }

    @Test fun `speech that starts late is still heard and timed from its start`() {
        val detector = SilenceDetector(silenceTimeoutMs = 1_200, maxDurationMs = 10_000)
        repeat(framesFor(3_000)) { assertEquals(SilenceDetector.Decision.CONTINUE, detector.accept(frame(0.0, seed = it))) }
        assertEquals(-1L, detector.speechStartMs)
        repeat(framesFor(500)) { detector.accept(frame(0.3, seed = it)) }
        assertEquals(3_000L, detector.speechStartMs)
        assertEquals(SilenceDetector.Decision.END_OF_SPEECH, silenceUntilDecision(detector).first)
    }

    @Test fun `talking without pause stops at the maximum`() {
        val detector = SilenceDetector(silenceTimeoutMs = 1_000, maxDurationMs = 2_000)
        var decision = SilenceDetector.Decision.CONTINUE
        var n = 0
        while (decision == SilenceDetector.Decision.CONTINUE) decision = detector.accept(frame(0.4, seed = n++))
        assertEquals(SilenceDetector.Decision.MAX_DURATION, decision)
    }

    // --- recorder ---------------------------------------------------------------------------------

    private class FakeSource(private val frames: List<ShortArray>, private val failAt: Int = -1) : PcmSource {
        var released = 0
        private var index = 0
        override fun start() = true
        override fun read(buffer: ShortArray): Int {
            if (index == failAt) return -3
            val next = frames.getOrNull(index++) ?: return 0.also { Thread.sleep(1) }
            next.copyInto(buffer)
            return next.size
        }
        override fun release() { released++ }
    }

    @Test fun `recorder stops at end of speech and releases the microphone`() {
        val frames = List(framesFor(200)) { frame(0.0, seed = it) } +
            List(framesFor(600)) { frame(0.4, seed = it) } +
            List(framesFor(2_000)) { frame(0.0, seed = it) }
        val source = FakeSource(frames)
        val recording = VoiceRecorder.record(
            source, SilenceDetector(1_000, 10_000), 10_000, VoiceRecorder.Control()
        )
        assertEquals(VoiceRecording.Ending.END_OF_SPEECH, recording.ending)
        assertTrue(recording.heardSpeech)
        // 600 ms of speech is a short command: it ends after 60% of the 1 s setting.
        assertEquals(1_400L, recording.durationMs)
        assertEquals(1, source.released)
    }

    @Test fun `recorder reports where speech began and the endpoint wait`() {
        val frames = List(framesFor(400)) { frame(0.0, seed = it) } +
            List(framesFor(600)) { frame(0.4, seed = it) } +
            List(framesFor(2_000)) { frame(0.0, seed = it) }
        var clock = 0L
        val recording = VoiceRecorder.record(
            FakeSource(frames), SilenceDetector(1_000, 10_000), 10_000, VoiceRecorder.Control(),
            clock = { ++clock }
        )
        assertEquals(400L, recording.speechStartMs)
        // Short command: 60% of the 1 s setting.
        assertEquals(600L, recording.trailingSilenceMs)
        assertEquals(1L, recording.endedAtNanos)
    }

    @Test fun `nothing said reports no speech and no speech start`() {
        val recording = VoiceRecorder.record(
            FakeSource(List(framesFor(6_000)) { frame(0.0, seed = it) }),
            SilenceDetector(1_000, 10_000), 10_000, VoiceRecorder.Control()
        )
        assertEquals(VoiceRecording.Ending.NO_SPEECH, recording.ending)
        assertFalse(recording.heardSpeech)
        assertEquals(-1L, recording.speechStartMs)
    }

    @Test fun `recorder failure is reported and the microphone released`() {
        val source = FakeSource(List(10) { frame(0.4) }, failAt = 5)
        val recording = VoiceRecorder.record(source, SilenceDetector(1_000, 10_000), 10_000, VoiceRecorder.Control())
        assertEquals(VoiceRecording.Ending.RECORDER_ERROR, recording.ending)
        assertEquals(1, source.released)
    }

    @Test fun `user stop keeps what was heard, cancel and interruption are reported`() {
        listOf(
            VoiceRecorder.Control().apply { stop.set(true) } to VoiceRecording.Ending.STOPPED,
            VoiceRecorder.Control().apply { cancel.set(true) } to VoiceRecording.Ending.CANCELLED,
            VoiceRecorder.Control().apply { interrupted.set(true) } to VoiceRecording.Ending.INTERRUPTED
        ).forEach { (control, ending) ->
            val source = FakeSource(List(10) { frame(0.4) })
            assertEquals(ending, VoiceRecorder.record(source, SilenceDetector(1_000, 10_000), 10_000, control).ending)
            assertEquals(1, source.released)
        }
    }

    // --- conversion and cleanup -------------------------------------------------------------------

    @Test fun `pcm conversion scales and pads to a second`() {
        val out = PcmConversion.toFloat(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0), 3)
        assertEquals(WhisperEngine.SAMPLE_RATE, out.size)
        assertEquals(32767f / 32768f, out[0], 1e-6f)
        assertEquals(-1f, out[1], 1e-6f)
        assertEquals(0f, out[2], 0f)
        assertEquals(20_000, PcmConversion.toFloat(ShortArray(20_000)).size)
    }

    @Test fun `little-endian bytes to samples`() {
        val into = ShortArray(2)
        assertEquals(2, PcmConversion.bytesToShorts(byteArrayOf(0x01, 0x02, 0xff.toByte(), 0xff.toByte()), 4, into))
        assertEquals(0x0201.toShort(), into[0])
        assertEquals((-1).toShort(), into[1])
    }

    @Test fun `transcript cleanup`() {
        assertEquals("เปิด YouTube เพลง Bodyslam", TranscriptCleaner.clean(" เปิด YouTube เพลง Bodyslam."))
        assertEquals("", TranscriptCleaner.clean("[BLANK_AUDIO]"))
        assertEquals("", TranscriptCleaner.clean(" (music) ♪ "))
        assertEquals("go back", TranscriptCleaner.clean("[Music] go back"))
        val prompt = VoiceCommandParser.RECOGNITION_PROMPT
        assertEquals("", TranscriptCleaner.clean(prompt, prompt))
        assertEquals("YouTube", TranscriptCleaner.clean("YouTube", prompt))
    }

    @Test fun `thread default stays on the big cores`() {
        assertEquals(2, VoiceSettings.autoThreads(2))
        assertEquals(4, VoiceSettings.autoThreads(8))
        assertEquals(4, VoiceSettings.autoThreads(12))
        assertEquals(3, VoiceSettings.autoThreads(6))
    }
}
