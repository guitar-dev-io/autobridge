package dev.autobridge.obd.transport

data class BleChar(val uuid: String, val canNotify: Boolean, val canWrite: Boolean, val canWriteNoResponse: Boolean)
data class BleService(val uuid: String, val chars: List<BleChar>)

/** The pair of characteristics an adapter uses as its serial line: [notify] in, [write] out. */
data class BleSerial(val serviceUuid: String, val notify: String, val write: String)

/**
 * BLE ELM327 adapters have no standard serial profile; each vendor invented one (FFF0/FFF1/FFF2,
 * FFE0/FFE1, 18F0/2AF0/2AF1, ...). Rather than a table that is always one adapter behind, pick the
 * first non-system service that has something to listen on and something to write to, preferring a
 * single characteristic that does both (the FFE1 style) and the well-known vendor services.
 */
object BleLayout {
    private val SYSTEM_PREFIXES = listOf("00001800", "00001801", "0000180a", "0000180f")
    private val KNOWN = listOf("0000fff0", "0000ffe0", "000018f0", "0000ffe5")

    fun pick(services: List<BleService>): BleSerial? {
        val candidates = services
            .filter { s -> SYSTEM_PREFIXES.none { s.uuid.lowercase().startsWith(it) } }
            .sortedBy { s -> KNOWN.indexOfFirst { s.uuid.lowercase().startsWith(it) }.let { if (it < 0) KNOWN.size else it } }
        for (s in candidates) {
            val both = s.chars.firstOrNull { it.canNotify && (it.canWrite || it.canWriteNoResponse) }
            val notify = s.chars.firstOrNull { it.canNotify }
            val write = s.chars.firstOrNull { (it.canWrite || it.canWriteNoResponse) && it.uuid != notify?.uuid } ?: both
            if (notify != null && write != null) {
                // A lone characteristic that does both is the serial line itself.
                return if (both != null && s.chars.none { it.uuid != both.uuid && (it.canWrite || it.canWriteNoResponse) })
                    BleSerial(s.uuid, both.uuid, both.uuid)
                else BleSerial(s.uuid, notify.uuid, write.uuid)
            }
        }
        return null
    }
}
