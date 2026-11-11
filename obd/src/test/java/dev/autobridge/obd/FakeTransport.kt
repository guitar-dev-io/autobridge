package dev.autobridge.obd

import dev.autobridge.obd.transport.ObdTimeoutException
import dev.autobridge.obd.transport.ObdTransport

/** Answers each command from a map; an unknown command times out like a silent adapter. */
class FakeTransport(private val replies: Map<String, String>) : ObdTransport {
    val sent = ArrayList<String>()
    private var pending: String? = null
    override fun open() = Unit
    override fun write(command: String) { sent.add(command); pending = command }
    override fun readUntilPrompt(timeoutMs: Long): String =
        replies[pending] ?: throw ObdTimeoutException("no reply to $pending")
    override fun close() = Unit

    companion object {
        /** A CAN car answering the usual start-up questions. */
        fun canCar(extra: Map<String, String> = emptyMap()) = FakeTransport(
            mapOf(
                "ATZ" to "\r\rELM327 v1.5\r\r", "ATE0" to "OK\r", "ATL0" to "OK\r", "ATS0" to "OK\r",
                "ATH0" to "OK\r", "ATAT1" to "OK\r", "ATSP0" to "OK\r",
                "0100" to "SEARCHING...\r4100BE1FA813\r", "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r",
                "ATDPN" to "A6\r", "ATRV" to "12.4V\r"
            ) + extra
        )
    }
}
