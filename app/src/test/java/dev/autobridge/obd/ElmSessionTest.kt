package dev.autobridge.obd

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
        val reply = ElmSession(adapter).read(ObdPids.RPM)
        assertEquals(1250f, ObdPids.RPM.decode((reply as Reply.Value).data), 0.01f)
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
        val poller = ObdPoller(ElmSession(adapter), Transmission.T5) { g, v -> seen[g] = v }
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
        try { ObdPoller(ElmSession(adapter), Transmission.T5) { _, _ -> }.initialize() } catch (e: ObdFailure) { failure = e }
        assertTrue(failure != null)
    }
}
