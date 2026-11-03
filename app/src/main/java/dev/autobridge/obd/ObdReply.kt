package dev.autobridge.obd

/** What came back for one request. */
sealed interface Reply {
    class Value(val data: IntArray) : Reply
    /** The car does not answer this PID (no data, or a negative response). */
    data object NoData : Reply
    /** The adapter or the bus failed; the connection may need to restart. */
    class Failure(val text: String) : Reply
}

object ObdParser {
    private val ERRORS = listOf(
        "UNABLE TO CONNECT", "BUS INIT", "CAN ERROR", "BUS ERROR", "BUFFER FULL", "FB ERROR",
        "DATA ERROR", "STOPPED", "ERROR",
    )

    /** Hex bytes of a reply line that starts with [reply], or null. Spaces are ignored. */
    fun extract(raw: String, request: String, reply: String, bytes: Int): Reply {
        val lines = raw.replace('>', '\n').split('\r', '\n')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            // The adapter may echo the request when echo is still on, and searches before the first answer.
            .filter { it.replace(" ", "") != request.uppercase() && !it.startsWith("SEARCHING") }
        for (line in lines) {
            val compact = stripFrameIndex(line.replace(" ", ""))
            val at = compact.indexOf(reply.uppercase())
            if (at < 0) continue
            val hex = compact.substring(at + reply.length)
            if (hex.length < bytes * 2) continue
            val data = IntArray(bytes)
            for (i in 0 until bytes) {
                data[i] = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return Reply.NoData
            }
            return Reply.Value(data)
        }
        for (line in lines) {
            val compact = line.replace(" ", "")
            if (compact.startsWith("NODATA") || compact.startsWith("7F") || compact == "?") return Reply.NoData
        }
        val fault = lines.firstOrNull { line -> ERRORS.any { line.contains(it) } }
        return if (fault != null) Reply.Failure(fault) else Reply.NoData
    }

    /** ELM multi-line replies number their lines "0:", "1:"; the number is not data. */
    private fun stripFrameIndex(line: String): String =
        if (line.length > 2 && line[1] == ':' && line[0].isLetterOrDigit()) line.substring(2) else line
}
