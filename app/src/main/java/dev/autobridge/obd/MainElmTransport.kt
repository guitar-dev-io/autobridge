package dev.autobridge.obd

import kotlinx.coroutines.runBlocking

/**
 * Lets the blocking module code in `:obd` talk through the gauge connection's own session, so there
 * is one conversation with the adapter. Call it from a worker thread, never the main thread.
 */
internal class MainElmTransport(private val session: ElmSession) : dev.autobridge.obd.transport.ObdTransport {
    private var pending = ""

    override fun open() = Unit

    override fun write(command: String) { pending = command }

    override fun readUntilPrompt(timeoutMs: Long): String = try {
        runBlocking { session.request(pending, timeoutMs) }
    } catch (e: ObdTimeoutException) {
        throw dev.autobridge.obd.transport.ObdTimeoutException(e.message ?: pending)
    }

    override fun close() = Unit
}
