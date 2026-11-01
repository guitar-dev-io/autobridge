package dev.autobridge.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedAdapter(private val answers: Map<String, String>) : ObdTransport {
    override var onChunk: ((String) -> Unit)? = null
    override var onClosed: ((String?) -> Unit)? = null
    val sent = mutableListOf<String>()

    override suspend fun connect() {}

    override suspend fun write(text: String) {
        val command = text.trim()
        sent += command
        (answers[command] ?: "NO DATA\r>").chunked(5).forEach { onChunk?.invoke(it) }
    }

    override fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ObdExclusiveTest {
    private val setup = mapOf(
        "ATZ" to "ELM327 v2.2\r>", "ATE0" to "OK\r>", "ATL0" to "OK\r>", "ATS0" to "OK\r>",
        "ATH0" to "OK\r>", "ATAT1" to "OK\r>", "ATST64" to "OK\r>", "ATSP0" to "OK\r>",
        "0100" to "41 00 BE 3E B8 11\r>", "ATDPN" to "A6\r>",
        "010C" to "41 0C 0B B8\r>", "010D" to "41 0D 00\r>",
        "03" to "43 02 01 33 03 00 00\r>", "04" to "44\r>",
    )

    @Test fun aTroubleCodeRequestRunsBetweenGaugePassesAndOnTheBroadcastHeader() = runTest {
        val adapter = ScriptedAdapter(setup)
        val poller = ObdPoller(ElmSession(adapter), Transmission.T5) { _, _ -> }
        poller.initialize()
        assertTrue(poller.isCan)
        val job = launch { poller.poll() }
        testScheduler.runCurrent()

        val reply = poller.exclusively { it.request("03") }
        assertEquals(listOf("P0133", "P0300"), DtcParser.parse(reply, 0x43, poller.isCan))

        // Nothing but the request itself sits between the header being set and the answer.
        val at = adapter.sent.lastIndexOf("03")
        assertTrue(at > 0)
        job.cancel()
    }

    @Test fun clearingIsConfirmedByTheServiceByteOnTheAnswer() = runTest {
        val poller = ObdPoller(ElmSession(ScriptedAdapter(setup)), Transmission.T5) { _, _ -> }
        poller.initialize()
        val reply = poller.exclusively { it.request("04") }
        assertTrue(reply.replace(" ", "").contains("44"))
    }
}
