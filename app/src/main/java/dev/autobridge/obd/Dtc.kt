package dev.autobridge.obd

/** Which system a trouble code belongs to, from its first letter. */
enum class DtcSystem(val letter: Char) { POWERTRAIN('P'), CHASSIS('C'), BODY('B'), NETWORK('U') }

/** One trouble code such as P0133, and whether it is stored (confirmed) or only pending. */
data class Dtc(val code: String, val pending: Boolean) {
    val system: DtcSystem get() = DtcSystem.entries.first { it.letter == code.first() }

    /** True for the codes every maker shares (P0xxx, P2xxx, U0xxx...), false for Ford's own (P1xxx). */
    val generic: Boolean get() = code[1] == '0' || code[1] == '2'
}

/**
 * Reads the answers to the OBD "show stored codes" (03) and "show pending codes" (07) requests.
 *
 * An answer is the service byte plus 0x40 (43 or 47), then each code as two bytes. On CAN the
 * first byte after that is how many codes follow, and the rest is padded with zeros to a whole
 * frame; on the older buses there is no count. A reply longer than one frame arrives as numbered
 * lines (`0:`, `1:`...) after a byte-count line, and several control units may each answer, so
 * every message found is read and the codes are merged.
 */
object DtcParser {

    /** Codes in [raw], or an empty list. [service] is 0x43 or 0x47; [hasCount] is true on CAN. */
    fun parse(raw: String, service: Int, hasCount: Boolean): List<String> {
        val codes = LinkedHashSet<String>()
        for (message in messages(raw)) {
            val bytes = hexBytes(message)
            if (bytes.isEmpty() || bytes[0] != service) continue
            val data = bytes.drop(1)
            val body = if (hasCount && data.isNotEmpty()) data.drop(1) else data
            var i = 0
            while (i + 1 < body.size) {
                val a = body[i]
                val b = body[i + 1]
                i += 2
                if (a == 0 && b == 0) continue
                codes += decode(a, b)
            }
        }
        return codes.toList()
    }

    /**
     * True when [raw] holds a real answer to [service] (0x43 or 0x47), with or without codes, or
     * the adapter's NO DATA, which some buses send for "no codes". False for an error or silence,
     * so a failed read is not taken for "every code is gone".
     */
    fun answered(raw: String, service: Int): Boolean =
        raw.uppercase().contains("NO DATA") || messages(raw).any { hexBytes(it).firstOrNull() == service }

    /** `P0133` from the two bytes 0x01 0x33. */
    fun decode(a: Int, b: Int): String {
        val system = "PCBU"[(a shr 6) and 0x3]
        val digit = (a shr 4) and 0x3
        return buildString {
            append(system)
            append(digit)
            append("0123456789ABCDEF"[a and 0xF])
            append("0123456789ABCDEF"[(b shr 4) and 0xF])
            append("0123456789ABCDEF"[b and 0xF])
        }
    }

    /** Each answer as one run of hex digits: numbered multi-frame lines joined, length lines dropped. */
    private fun messages(raw: String): List<String> {
        val lines = raw.replace('>', '\n').split('\r', '\n')
            .map { it.replace(" ", "").uppercase() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }
        val out = ArrayList<String>()
        var current: StringBuilder? = null
        for (line in lines) {
            val numbered = line.length > 2 && line[1] == ':' && line[0].isLetterOrDigit()
            when {
                numbered && line[0] == '0' -> {
                    current?.let { out += it.toString() }
                    current = StringBuilder(line.substring(2))
                }
                numbered -> current?.append(line.substring(2))
                else -> {
                    current?.let { out += it.toString() }
                    current = null
                    // A bare three-digit line is the byte count that precedes a multi-frame answer.
                    if (line.length > 3 || !line.all { it in "0123456789ABCDEF" }) out += line
                }
            }
        }
        current?.let { out += it.toString() }
        return out
    }

    private fun hexBytes(text: String): List<Int> {
        if (text.length < 2) return emptyList()
        val bytes = ArrayList<Int>()
        var i = 0
        while (i + 1 < text.length) {
            bytes += text.substring(i, i + 2).toIntOrNull(16) ?: return emptyList()
            i += 2
        }
        return bytes
    }
}
