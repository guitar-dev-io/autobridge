package dev.autobridge.obd.obd2

/** One decoded live reading. */
data class PidValue(val pid: Pid, val value: Double) {
    val text: String get() = pid.format(value)
}

/**
 * A Mode 01 parameter. [decode] receives only the data bytes (A, B, ...) that follow the echoed
 * mode and PID, as unsigned ints.
 */
class Pid(
    val id: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    private val decimals: Int = 0,
    val decode: (IntArray) -> Double
) {
    fun format(value: Double): String =
        (if (decimals == 0) value.toLong().toString() else "%.${decimals}f".format(java.util.Locale.US, value)) + (if (unit.isEmpty()) "" else " $unit")
}

object Pids {
    private fun a(d: IntArray) = d[0]
    private fun ab(d: IntArray) = d[0] * 256 + d[1]

    val all: List<Pid> = listOf(
        Pid(0x04, "Engine load", "%", 1, 1) { a(it) * 100.0 / 255 },
        Pid(0x05, "Coolant temperature", "°C", 1) { a(it) - 40.0 },
        Pid(0x06, "Short fuel trim B1", "%", 1, 1) { (a(it) - 128) * 100.0 / 128 },
        Pid(0x07, "Long fuel trim B1", "%", 1, 1) { (a(it) - 128) * 100.0 / 128 },
        Pid(0x0A, "Fuel pressure", "kPa", 1) { a(it) * 3.0 },
        Pid(0x0B, "Intake manifold pressure", "kPa", 1) { a(it).toDouble() },
        Pid(0x0C, "Engine speed", "rpm", 2) { ab(it) / 4.0 },
        Pid(0x0D, "Vehicle speed", "km/h", 1) { a(it).toDouble() },
        Pid(0x0E, "Timing advance", "°", 1, 1) { a(it) / 2.0 - 64 },
        Pid(0x0F, "Intake air temperature", "°C", 1) { a(it) - 40.0 },
        Pid(0x10, "Air flow (MAF)", "g/s", 2, 2) { ab(it) / 100.0 },
        Pid(0x11, "Throttle position", "%", 1, 1) { a(it) * 100.0 / 255 },
        Pid(0x1F, "Run time since start", "s", 2) { ab(it).toDouble() },
        Pid(0x21, "Distance with MIL on", "km", 2) { ab(it).toDouble() },
        Pid(0x2F, "Fuel level", "%", 1, 1) { a(it) * 100.0 / 255 },
        Pid(0x31, "Distance since codes cleared", "km", 2) { ab(it).toDouble() },
        Pid(0x33, "Barometric pressure", "kPa", 1) { a(it).toDouble() },
        Pid(0x42, "Control module voltage", "V", 2, 2) { ab(it) / 1000.0 },
        Pid(0x43, "Absolute load", "%", 2, 1) { ab(it) * 100.0 / 255 },
        Pid(0x46, "Ambient temperature", "°C", 1) { a(it) - 40.0 },
        Pid(0x5C, "Oil temperature", "°C", 1) { a(it) - 40.0 },
        Pid(0x5E, "Fuel rate", "L/h", 2, 2) { ab(it) / 20.0 }
    )

    private val byId = all.associateBy { it.id }
    fun find(id: Int): Pid? = byId[id]

    const val SPEED = 0x0D
    const val RPM = 0x0C
    const val COOLANT = 0x05

    /** A sensible default set for the live screen. */
    val defaultLive = listOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x0F, 0x42)
}
