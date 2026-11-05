package dev.autobridge.obd.transport

import java.io.IOException

/**
 * A byte pipe to an ELM327-style adapter. Blocking on purpose: the session is driven from one
 * worker thread, and a blocking interface is what a scripted test transport implements most simply.
 */
interface ObdTransport {
    /** Connects. Throws [IOException] when the adapter cannot be reached. */
    @Throws(IOException::class)
    fun open()

    /** Sends [command] followed by a carriage return. */
    @Throws(IOException::class)
    fun write(command: String)

    /**
     * Reads until the adapter's `>` prompt, which ends every reply, and returns everything before it.
     * Throws [ObdTimeoutException] when no prompt arrives within [timeoutMs].
     */
    @Throws(IOException::class)
    fun readUntilPrompt(timeoutMs: Long): String

    fun close()
}

class ObdTimeoutException(message: String) : IOException(message)
