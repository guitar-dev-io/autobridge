package dev.autobridge.obd.elm

import dev.autobridge.obd.transport.ObdTimeoutException
import dev.autobridge.obd.transport.ObdTransport
import java.io.IOException

/** Why a request got no usable answer. Each maps to something the adapter literally says. */
sealed class ObdFailure(val message: String) {
    object NoData : ObdFailure("NO DATA")
    object NotConnected : ObdFailure("UNABLE TO CONNECT")
    object BusError : ObdFailure("BUS ERROR")
    object Unsupported : ObdFailure("?")
    object Timeout : ObdFailure("TIMEOUT")
    class Other(text: String) : ObdFailure(text)
}

sealed class ObdReply {
    /** Payloads of each responding ECU (mode byte onwards), multi-frame already reassembled. */
    class Data(val payloads: List<ByteArray>) : ObdReply() {
        val first: ByteArray get() = payloads.first()
    }

    class Failed(val failure: ObdFailure) : ObdReply()
}

/** What the adapter reported while initialising. */
data class AdapterInfo(
    val identity: String,
    val protocol: String,
    val voltage: String?,
    /** ISO 15765 (CAN) protocols answer Mode 03/07/0A with a leading count byte; the others do not. */
    val isCan: Boolean
)

/**
 * One conversation with an ELM327-style adapter: initialise, pick the vehicle protocol, then send
 * a request and get the answer back as bytes. Not thread-safe; the owner serialises calls.
 */
class ElmSession(private val transport: ObdTransport) {
    var info: AdapterInfo? = null
        private set

    /** Opens the transport and brings the adapter and the vehicle bus up. Throws on a dead adapter. */
    @Throws(IOException::class)
    fun connect(): AdapterInfo {
        transport.open()
        val identity = rawCommand("ATZ", RESET_TIMEOUT_MS).let { reset ->
            // An adapter that has just powered up prints a banner; one that was already awake
            // may only say OK. Ask for the version either way so the user can see what it is.
            val banner = reset.lines().map { it.trim() }.firstOrNull { it.contains("ELM", true) || it.contains("STN", true) }
            banner ?: rawCommand("ATI", COMMAND_TIMEOUT_MS).trim()
        }
        // Echo off, linefeeds off, spaces off, headers off, adaptive timing on.
        for (setup in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) rawCommand(setup, COMMAND_TIMEOUT_MS)
        rawCommand("ATSP0", COMMAND_TIMEOUT_MS)
        // The first real request is what makes the adapter search for the bus; it can take seconds.
        val probe = rawCommand("0100", PROTOCOL_SEARCH_TIMEOUT_MS)
        if (probe.contains("UNABLE TO CONNECT", true) || probe.contains("BUS INIT", true) && probe.contains("ERROR", true)) {
            throw IOException("Adapter is connected but the car did not answer. Turn the ignition on.")
        }
        val protocol = rawCommand("ATDP", COMMAND_TIMEOUT_MS).trim().removePrefix("AUTO,").trim()
        val numeric = rawCommand("ATDPN", COMMAND_TIMEOUT_MS).trim().removePrefix("A").trim()
        val isCan = numeric.firstOrNull()?.digitToIntOrNull(16)?.let { it >= 6 } ?: protocol.contains("CAN", true)
        val voltage = rawCommand("ATRV", COMMAND_TIMEOUT_MS).trim().takeIf { it.isNotEmpty() && !it.contains("?") }
        return AdapterInfo(identity, protocol, voltage, isCan).also { info = it }
    }

    /** Adapter-input battery voltage ("12.4V"), or null if the adapter does not report it. */
    fun voltage(): String? = runCatching {
        rawCommand("ATRV", COMMAND_TIMEOUT_MS).trim().takeIf { it.isNotEmpty() && !it.contains("?") }
    }.getOrNull()

    /** Sends a request such as `010C` or `03` and parses the answer. */
    fun request(command: String, timeoutMs: Long = REQUEST_TIMEOUT_MS): ObdReply {
        val text = try {
            rawCommand(command, timeoutMs)
        } catch (e: ObdTimeoutException) {
            return ObdReply.Failed(ObdFailure.Timeout)
        }
        return parse(text)
    }

    /**
     * Aims requests at one ECU on an 11-bit CAN bus ([requestId] such as "7E0") and listens only to
     * [replyId] ("7E8"), so a scan can tell who answered. Always pair with [resetAddressing].
     */
    fun addressEcu(requestId: String, replyId: String): Boolean =
        rawCommand("ATSH$requestId", COMMAND_TIMEOUT_MS).contains("OK") &&
            rawCommand("ATCRA$replyId", COMMAND_TIMEOUT_MS).contains("OK")

    /** Back to the functional broadcast address and no reply filter, which generic OBD-II expects. */
    fun resetAddressing() {
        runCatching {
            rawCommand("ATSH7DF", COMMAND_TIMEOUT_MS)
            rawCommand("ATAR", COMMAND_TIMEOUT_MS)
        }
    }

    fun close() = transport.close()

    private fun rawCommand(command: String, timeoutMs: Long): String {
        transport.write(command)
        return transport.readUntilPrompt(timeoutMs)
    }

    companion object {
        const val RESET_TIMEOUT_MS = 4_000L
        const val COMMAND_TIMEOUT_MS = 1_500L
        const val PROTOCOL_SEARCH_TIMEOUT_MS = 12_000L
        const val REQUEST_TIMEOUT_MS = 2_500L

        /** Pure text -> reply; kept separate so it can be tested without a transport. */
        fun parse(text: String): ObdReply {
            val lines = text.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            val noise = setOf("SEARCHING...", "SEARCHING", "OK")
            val content = lines.filterNot { line -> noise.any { line.equals(it, true) } || line.startsWith("BUS INIT", true) && !line.contains("ERROR", true) }

            for (line in content) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("NO DATA") -> return ObdReply.Failed(ObdFailure.NoData)
                    upper.startsWith("UNABLE TO CONNECT") -> return ObdReply.Failed(ObdFailure.NotConnected)
                    upper.contains("BUS ERROR") || upper.contains("CAN ERROR") || upper.contains("BUS BUSY") ||
                        upper.contains("FB ERROR") || upper.contains("DATA ERROR") || upper.contains("BUFFER FULL") ->
                        return ObdReply.Failed(ObdFailure.BusError)
                    upper == "?" -> return ObdReply.Failed(ObdFailure.Unsupported)
                    upper == "STOPPED" -> return ObdReply.Failed(ObdFailure.Other("STOPPED"))
                }
            }

            // Multi-frame answers print a byte-count line ("014") and then numbered frames
            // ("0: 49 02 01 ..."). With frame numbers present, only the numbered lines carry data.
            val framed = Regex("^([0-9A-F]):(.*)$", RegexOption.IGNORE_CASE)
            val numbered = content.mapNotNull { framed.matchEntire(it) }
            val dataLines: List<String> = if (numbered.isNotEmpty()) {
                // Several ECUs can each answer in frames; frame 0 starts a new message. The count
                // line before it is the message length in bytes (hex): the last frame is padded to
                // eight bytes (Ford pads with CC), and that padding is not part of the answer. Left
                // in, it would show up in every value and be written back by a coding change.
                val count = Regex("^[0-9A-F]{3}$", RegexOption.IGNORE_CASE)
                val messages = ArrayList<Pair<StringBuilder, Int?>>()
                var pendingCount: Int? = null
                for (line in content) {
                    if (count.matches(line)) { pendingCount = line.toInt(16); continue }
                    val m = framed.matchEntire(line) ?: continue
                    if (m.groupValues[1] == "0" || messages.isEmpty()) {
                        messages.add(StringBuilder() to pendingCount)
                        pendingCount = null
                    }
                    messages.last().first.append(m.groupValues[2])
                }
                messages.map { (text, length) ->
                    val hex = text.toString().filterNot { it.isWhitespace() }
                    if (length != null && hex.length > length * 2) hex.substring(0, length * 2) else hex
                }
            } else content

            val payloads = dataLines.mapNotNull { hexToBytes(it) }.filter { it.isNotEmpty() }
            if (payloads.isEmpty()) return ObdReply.Failed(ObdFailure.Other(content.firstOrNull() ?: "empty reply"))
            return ObdReply.Data(payloads)
        }

        /** "41 0C 1A F8" or "410C1AF8" -> bytes; null if the text is not hex. */
        fun hexToBytes(text: String): ByteArray? {
            val clean = text.filterNot { it.isWhitespace() }
            if (clean.isEmpty() || clean.length % 2 != 0 || !clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
            return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
