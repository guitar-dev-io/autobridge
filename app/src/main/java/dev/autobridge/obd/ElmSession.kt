package dev.autobridge.obd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A byte pipe to an ELM327-style adapter; Bluetooth LE today, Wi-Fi later. */
interface ObdTransport {
    /** Receives each chunk of text as it arrives, on any thread. Set before [connect]. */
    var onChunk: ((String) -> Unit)?

    /** Called once when the link drops; the argument says why, when it is known. */
    var onClosed: ((String?) -> Unit)?

    suspend fun connect()
    suspend fun write(text: String)
    fun close()
}

class ObdTimeoutException(command: String) : Exception("No answer to $command")

/**
 * Sends one command at a time and returns the adapter's whole answer. An ELM327 ends every answer
 * with a '>' prompt; that, not a fixed delay, is what says it is done, so a fast adapter is not
 * made to wait.
 */
class ElmSession(private val transport: ObdTransport) {
    private val chunks = Channel<String>(Channel.UNLIMITED)
    private val lock = Mutex()

    init {
        transport.onChunk = { chunks.trySend(it) }
    }

    suspend fun request(command: String, timeoutMs: Long = 2_000): String = lock.withLock {
        while (chunks.tryReceive().isSuccess) { /* drop what an earlier timed-out request left behind */ }
        transport.write(command + "\r")
        val answer = StringBuilder()
        val done = withTimeoutOrNull(timeoutMs) {
            while (true) {
                answer.append(chunks.receive())
                if (answer.indexOf(">") >= 0) break
            }
            true
        }
        if (done == null) {
            ObdLog.add(command, "(timed out) " + answer.toString())
            throw ObdTimeoutException(command)
        }
        val text = answer.toString().substringBefore('>')
        ObdLog.add(command, text)
        text
    }

    /** Reads one PID. [Reply.Failure] means the adapter or the bus is in trouble, not just this PID. */
    suspend fun read(pid: Pid, timeoutMs: Long = 1_500): Reply {
        val raw = request(pid.request, timeoutMs)
        return ObdParser.extract(raw, pid.request, pid.reply, pid.bytes)
    }
}
