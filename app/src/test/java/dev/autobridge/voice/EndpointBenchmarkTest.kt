package dev.autobridge.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The silence setting against synthetic, Thai-shaped commands: syllables of a tone with short gaps
 * inside words, longer gaps between them, a hesitation, and a soft sentence-final particle, in a
 * quiet room and in car-cabin noise. Prints, for every setting, how long after the last syllable the
 * detector ended and how many utterances it cut short.
 *
 * Synthetic audio says how the *rule* behaves - which pauses each setting survives - not how real
 * speakers pause; that needs recordings (see the replay on the Test Voice Recognition screen).
 */
class EndpointBenchmarkTest {

    private sealed interface Part { val ms: Int }
    private data class Syllable(override val ms: Int, val amplitude: Double = 0.3) : Part
    private data class Gap(override val ms: Int) : Part

    private data class Utterance(val name: String, val parts: List<Part>) {
        val speechEndMs: Int get() = LEAD_MS + parts.dropLastWhile { it is Gap }.sumOf { it.ms }
    }

    private val particle = 0.08

    private val corpus = listOf(
        Utterance("หยุด", listOf(Syllable(380))),
        Utterance("เปิดยูทูบ", listOf(Syllable(200), Gap(80), Syllable(180), Gap(250), Syllable(220), Gap(60), Syllable(240))),
        Utterance(
            "เปิดยูทูบมิวสิค…ครับ",
            listOf(
                Syllable(200), Gap(80), Syllable(180), Gap(250), Syllable(220), Gap(60), Syllable(240),
                Gap(300), Syllable(200), Gap(80), Syllable(220), Gap(400), Syllable(220, particle)
            )
        ),
        Utterance("เปิด…ยูทูบ (hesitation)", listOf(Syllable(220), Gap(650), Syllable(200), Gap(80), Syllable(240))),
        Utterance(
            "เปิดยูทูบ…มิวสิค",
            listOf(
                Syllable(200), Gap(80), Syllable(180), Gap(250), Syllable(220), Gap(60), Syllable(240),
                Gap(850), Syllable(200), Gap(80), Syllable(220)
            )
        ),
        Utterance(
            "ค้นหาเพลงของบอดี้สแลมในยูทูบ",
            listOf(
                Syllable(180), Gap(60), Syllable(200), Gap(200), Syllable(220), Gap(300), Syllable(180),
                Gap(250), Syllable(160), Gap(60), Syllable(180), Gap(60), Syllable(220), Gap(450),
                Syllable(160), Gap(200), Syllable(200), Gap(60), Syllable(240), Gap(350), Syllable(200, particle)
            )
        )
    )

    private val frameSamples = WhisperEngine.SAMPLE_RATE * SilenceDetector.FRAME_MS / 1000

    private fun frame(amplitude: Double, noise: Double, seed: Int): ShortArray {
        val random = java.util.Random(seed.toLong())
        return ShortArray(frameSamples) { i ->
            val tone = amplitude * sin(2 * PI * 180 * i / WhisperEngine.SAMPLE_RATE)
            val hiss = noise * (random.nextDouble() * 2 - 1)
            ((tone + hiss).coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    private data class Outcome(val endpointMs: Int?, val truncated: Boolean, val delayMs: Int?)

    private fun run(utterance: Utterance, settingMs: Int, noise: Double, before: Boolean = false): Outcome {
        val detector = SilenceDetector(
            silenceTimeoutMs = settingMs,
            maxDurationMs = 20_000,
            mediumFactor = if (before) 1.0 else SilenceDetector.MEDIUM_UTTERANCE_FACTOR
        )
        val amplitudes = buildList {
            repeat(LEAD_MS / SilenceDetector.FRAME_MS) { add(0.0) }
            utterance.parts.forEach { part ->
                val amplitude = if (part is Syllable) part.amplitude else 0.0
                repeat(part.ms / SilenceDetector.FRAME_MS) { add(amplitude) }
            }
            repeat(4_000 / SilenceDetector.FRAME_MS) { add(0.0) }
        }
        amplitudes.forEachIndexed { index, amplitude ->
            val decision = detector.accept(frame(amplitude, noise, index))
            if (decision == SilenceDetector.Decision.END_OF_SPEECH) {
                val endpoint = (index + 1) * SilenceDetector.FRAME_MS
                val truncated = endpoint < utterance.speechEndMs
                return Outcome(endpoint, truncated, if (truncated) null else endpoint - utterance.speechEndMs)
            }
        }
        return Outcome(null, false, null)
    }

    @Test fun `silence settings against Thai-shaped commands`() {
        val report = StringBuilder(
            "\nendpoint wait after the last syllable (ms); CUT = ended inside the command; * = before the command tier\n"
        )
        val truncations = mutableMapOf<Int, Int>()
        val newCuts = mutableListOf<String>()
        for ((noiseName, noise) in listOf("quiet" to 0.002, "cabin" to 0.02)) {
            report.append("\n[$noiseName]\n").append("setting".padEnd(9))
            corpus.forEach { report.append(it.name.take(14).padEnd(16)) }
            report.append('\n')
            for (setting in SETTINGS) {
                for (before in listOf(true, false)) {
                    // "before" is the rule without the command tier, for comparison.
                    report.append((if (before) "${setting}ms*" else "${setting}ms").padEnd(9))
                    corpus.forEach { utterance ->
                        val outcome = run(utterance, setting, noise, before)
                        if (outcome.truncated && !before) {
                            truncations.merge(setting, 1, Int::plus)
                            if (!run(utterance, setting, noise, before = true).truncated) {
                                newCuts += "${utterance.name} @${setting}ms $noiseName"
                            }
                        }
                        report.append((if (outcome.truncated) "CUT" else outcome.delayMs?.toString() ?: "none").padEnd(16))
                    }
                    report.append('\n')
                }
            }
        }
        println(report)

        // The shipped default cuts nothing in this corpus, in either room.
        assertEquals(0, truncations[VoiceSettings.DEFAULT_SILENCE_MS] ?: 0)
        // The command tier never cuts what the rule before it kept, at any setting.
        assertEquals(emptyList<String>(), newCuts)
        // 400 ms is too short for the hesitation: the rule is doing what it says, and it is why the
        // setting does not offer it.
        assertTrue((truncations[400] ?: 0) > 0)
        assertTrue(400 !in VoiceSettings.SILENCE_OPTIONS_MS)
    }

    @Test fun `the default waits less after a typical command than before`() {
        // Before the command tier, everything over 0.7 s of speech waited the full 1.2 s.
        val typical = corpus[1]
        val before = run(typical, VoiceSettings.DEFAULT_SILENCE_MS, 0.002, before = true)
        val after = run(typical, VoiceSettings.DEFAULT_SILENCE_MS, 0.002)
        // Delays land on 20 ms frames, so they are 10 ms short of the setting's exact share.
        assertEquals(1_190, before.delayMs)
        assertEquals(950, after.delayMs)
    }

    private companion object {
        const val LEAD_MS = 300
        val SETTINGS = listOf(400, 500, 600, 800, 1_200)
    }
}
