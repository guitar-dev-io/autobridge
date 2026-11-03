package dev.autobridge.obd

import dev.autobridge.obd.profile.BuiltInProfiles
import dev.autobridge.obd.profile.Formula
import dev.autobridge.obd.profile.ProfileCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scripted adapter: answers each command from a table, split into chunks like BLE does. */
private class FakeAdapter(
    private val answers: Map<String, String>,
    /** What an unknown command gets; null means the adapter stays silent. */
    private val otherwise: String? = "NO DATA\r>",
    private val split: Int = 4,
) : ObdTransport {
    override var onChunk: ((String) -> Unit)? = null
    override var onClosed: ((String?) -> Unit)? = null
    val sent = mutableListOf<String>()

    override suspend fun connect() {}

    override suspend fun write(text: String) {
        val command = text.trim()
        sent += command
        val answer = answers[command] ?: otherwise ?: return // silent: the request times out
        answer.chunked(split).forEach { onChunk?.invoke(it) }
    }

    override fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ElmSessionTest {
    @Test fun answerEndsAtThePrompt() = runTest {
        val adapter = FakeAdapter(mapOf("010C" to "41 0C 13 88 \r\r>"))
        val rpm = BuiltInProfiles.find(BuiltInProfiles.GENERIC_ID)!!.candidates("rpm").single()
        val reply = ElmSession(adapter).read(rpm)
        assertEquals(1250f, Formula.parse(rpm.formula)!!.evaluate((reply as Reply.Value).data)!!, 0.01f)
    }

    @Test fun aSilentAdapterTimesOut() = runTest {
        val session = ElmSession(FakeAdapter(emptyMap(), otherwise = null))
        var timedOut = false
        try { session.request("010C", 500) } catch (_: ObdTimeoutException) { timedOut = true }
        assertTrue(timedOut)
    }

    @Test fun pollerSetsUpTheAdapterAndPublishesValues() = runTest {
        val adapter = FakeAdapter(
            mapOf(
                "ATZ" to "ELM327 v2.2\r>", "ATE0" to "OK\r>", "ATL0" to "OK\r>", "ATS0" to "OK\r>",
                "ATH0" to "OK\r>", "ATAT1" to "OK\r>", "ATSP0" to "OK\r>",
                "0100" to "SEARCHING...\r41 00 BE 3E B8 11\r>", "ATDPN" to "A6\r>",
                "010C" to "41 0C 0B B8\r>", "010D" to "41 0D 3C\r>",
            )
        )
        val seen = mutableMapOf<Gauge, Float>()
        val poller = ObdPoller(ElmSession(adapter), BuiltInProfiles.findResolved("ford_ranger_pjpk")!!) { g, v -> seen[g] = v }
        poller.initialize()
        assertEquals("A6", poller.protocol)
        val job = launch { poller.poll() }
        // Let a couple of passes run, then stop it.
        testScheduler.advanceTimeBy(2_000)
        job.cancel()
        assertEquals(750f, seen[Gauge.RPM]!!, 0.01f)
        assertEquals(60f, seen[Gauge.SPEED]!!, 0f)
    }

    @Test fun anUnansweredCarFailsTheSetup() = runTest {
        val adapter = FakeAdapter(
            mapOf(
                "ATZ" to ">", "ATE0" to ">", "ATL0" to ">", "ATS0" to ">", "ATH0" to ">", "ATAT1" to ">",
                "ATSP0" to ">", "0100" to "UNABLE TO CONNECT\r>",
            )
        )
        var failure: ObdFailure? = null
        try { ObdPoller(ElmSession(adapter), BuiltInProfiles.findResolved("ford_ranger_pjpk")!!) { _, _ -> }.initialize() } catch (e: ObdFailure) { failure = e }
        assertTrue(failure != null)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePollerTest {
    private val setup = mapOf(
        "ATZ" to "ELM327 v2.2\r>", "ATE0" to "OK\r>", "ATL0" to "OK\r>", "ATS0" to "OK\r>",
        "ATH0" to "OK\r>", "ATAT1" to "OK\r>", "ATST64" to "OK\r>", "ATSP0" to "OK\r>",
        "0100" to "41 00 BE 3E B8 11\r>", "ATDPN" to "A6\r>",
    )

    @Test fun aGaugeOnlyAProfileDefinesIsReadWithItsOwnFormulaAndHeader() = runTest {
        val profile = ProfileCodec.decode(
            """
            id: my_truck
            name: My truck
            pid: rpm | RPM | 010C | 2 | - | (A*256+B)/4 | rpm | 0..5000 | - | -
            pid: oil | Oil temperature | 221234 | 2 | 7E0 | (A*256+B)/10-40 | °C | 0..150 | -40..200 | -
            """.trimIndent()
        ).profile!!
        val adapter = FakeAdapter(setup + mapOf("010C" to "41 0C 0B B8\r>", "221234" to "62 12 34 03 E8\r>"))
        val seen = mutableMapOf<Gauge, Float>()
        val poller = ObdPoller(ElmSession(adapter), profile) { g, v -> seen[g] = v }
        poller.initialize()
        val job = launch { poller.poll() }
        testScheduler.advanceTimeBy(2_000)
        job.cancel()
        assertEquals(750f, seen[Gauge.RPM]!!, 0.01f)
        // 0x03E8 = 1000; 1000/10-40 = 60.
        assertEquals(60f, seen[Gauge("oil")]!!, 0.01f)
        assertTrue("the oil request went to the engine module", adapter.sent.contains("ATSH7E0"))
    }

    @Test fun aGaugeWithTwoCandidatesFallsBackWhenTheFirstIsRefused() = runTest {
        val profile = ProfileCodec.decode(
            """
            id: my_truck
            name: My truck
            pid: rpm | RPM | 010C | 2 | - | (A*256+B)/4 | rpm | 0..5000 | - | -
            pid: atf | Gearbox oil | 221674 | 2 | 7E1 | A | °C | 0..150 | - | -
            pid: atf | Gearbox oil | 221E1C | 2 | 7E1 | (A*256+B)/16 | °C | 0..150 | - | -
            """.trimIndent()
        ).profile!!
        // The first candidate is refused (7F 22 31), the second answers.
        val adapter = FakeAdapter(
            setup + mapOf("010C" to "41 0C 0B B8\r>", "221674" to "7F 22 31\r>", "221E1C" to "62 1E 1C 05 A0\r>")
        )
        val seen = mutableMapOf<Gauge, Float>()
        val poller = ObdPoller(ElmSession(adapter), profile) { g, v -> seen[g] = v }
        poller.initialize()
        val job = launch { poller.poll() }
        testScheduler.advanceTimeBy(5_000)
        job.cancel()
        assertEquals(90f, seen[Gauge.ATF]!!, 0.01f)
    }
}
