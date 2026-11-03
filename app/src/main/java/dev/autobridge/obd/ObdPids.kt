package dev.autobridge.obd

/**
 * One value read from the car. [request] is what is sent, [reply] the start of a good answer
 * (service + 0x40, then the PID), [bytes] how many data bytes follow, and [header] the CAN
 * request id to talk to (7E0 engine, 7E1 transmission) or null to leave the adapter as it is.
 */
class Pid(
    val gauge: Gauge,
    val request: String,
    val reply: String,
    val bytes: Int,
    val header: String? = null,
    val decode: (IntArray) -> Float,
)

/**
 * Which Ranger this is. It decides the automatic-transmission temperature PID and the normal
 * ranges ([GaugeSpec]): [T5] is the PJ/PK (2009-2011), [T6] the PX (2011-2018), and [NEXT_GEN] the
 * 2022 and later Ranger, which reads gearbox oil like the T6 but runs hotter and boosts harder.
 */
enum class Transmission(val badge: String) {
    T5("T5"), T6("T6"), NEXT_GEN("NG")
}

object ObdPids {
    private fun word(d: IntArray) = d[0] * 256 + d[1]

    val RPM = Pid(Gauge.RPM, "010C", "410C", 2) { word(it) / 4f }
    val SPEED = Pid(Gauge.SPEED, "010D", "410D", 1) { it[0].toFloat() }
    val COOLANT = Pid(Gauge.COOLANT, "0105", "4105", 1) { it[0] - 40f }
    val LOAD = Pid(Gauge.LOAD, "0104", "4104", 1) { it[0] * 100f / 255f }
    val INTAKE = Pid(Gauge.INTAKE, "010F", "410F", 1) { it[0] - 40f }
    val MAP = Pid(Gauge.MAP, "010B", "410B", 1) { it[0].toFloat() }
    val BARO = Pid(Gauge.BARO, "0133", "4133", 1) { it[0].toFloat() }
    val THROTTLE = Pid(Gauge.THROTTLE, "0111", "4111", 1) { it[0] * 100f / 255f }
    val FUEL_LEVEL = Pid(Gauge.FUEL_LEVEL, "012F", "412F", 1) { it[0] * 100f / 255f }
    val AMBIENT = Pid(Gauge.AMBIENT, "0146", "4146", 1) { it[0] - 40f }

    /** Control module voltage, the standard way to read the battery. */
    val BATTERY_STANDARD = Pid(Gauge.BATTERY, "0142", "4142", 2) { word(it) / 1000f }

    /** Ford's own battery voltage, for a PCM that does not answer 0142. */
    val BATTERY_FORD = Pid(Gauge.BATTERY, "221172", "621172", 1, header = "7E0") { it[0] / 16f }

    /**
     * ATF temperature. The PID of the chosen [transmission] goes first, asked of the transmission
     * module and then the engine module, and the other family's PID follows as a fallback: a truck
     * that answers `7F 22 31` ("out of range", seen from a 2022 Ranger asked for the PJ/PK PID) is
     * simply on the other one, and finding it should not depend on the right choice having been
     * made. A PID the car refuses costs three quick negative answers before the next is tried.
     */
    fun atf(transmission: Transmission): List<Pid> {
        val t5 = atfFamily("221674", "621674") { word(it) * 5f / 72f - 18f }
        val t6 = atfFamily("221E1C", "621E1C") { word(it) / 16f }
        return when (transmission) {
            Transmission.T5 -> t5 + t6
            Transmission.T6, Transmission.NEXT_GEN -> t6 + t5
        }
    }

    private fun atfFamily(request: String, reply: String, decode: (IntArray) -> Float): List<Pid> =
        listOf("7E1", "7E0").map { Pid(Gauge.ATF, request, reply, 2, header = it, decode = decode) }
}

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
