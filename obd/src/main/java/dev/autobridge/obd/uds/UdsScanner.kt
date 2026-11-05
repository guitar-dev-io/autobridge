package dev.autobridge.obd.uds

import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.elm.ObdReply

/** What one module said about itself. [values] maps a data identifier name to its text. */
data class EcuInfo(val requestId: String, val replyId: String, val values: Map<String, String>, val name: String? = null)

/** An address to ask. Reply is the request + 8 on every target here. */
data class EcuTarget(val requestId: String, val name: String?) {
    val replyId: String get() = "%X".format(requestId.toInt(16) + 8)
}

object EcuTargets {
    /** The standard OBD-II 11-bit addresses. */
    val standard: List<EcuTarget> = (0..7).map { EcuTarget("7E$it", if (it == 0) "Engine (PCM)" else if (it == 1) "Transmission (TCM)" else null) }

    /**
     * Ford body/chassis modules on the HS-CAN pins (6/14) of the OBD port, as listed in the public
     * FORScan community documentation. Not verified on a vehicle by this project: a module that is
     * not there simply does not answer. Modules on Ford's MS-CAN (pins 3/11) need an adapter with
     * the MS/HS switch and are out of reach of a plain ELM327.
     */
    val ford: List<EcuTarget> = standard + listOf(
        EcuTarget("760", "ABS / ESP"), EcuTarget("720", "Instrument cluster (IPC)"),
        EcuTarget("726", "Body control (BCM)"), EcuTarget("737", "Restraints (RCM)"),
        EcuTarget("730", "Power steering (PSCM)"), EcuTarget("7D0", "SYNC / APIM")
    )
}

/**
 * Read-only module discovery over UDS (ISO 14229) on the standard 11-bit CAN addresses
 * 7E0..7E7 (replies on +8). It only sends service 0x22 (read data by identifier) for the
 * identification DIDs every UDS module is expected to know; it never changes session, unlocks, or
 * writes. Modules on other addresses, or on KWP2000 rather than UDS, are simply not found.
 */
class UdsScanner(private val session: ElmSession, private val targets: List<EcuTarget> = EcuTargets.standard) {

    fun scan(): List<EcuInfo> {
        if (session.info?.isCan == false) return emptyList()
        val found = ArrayList<EcuInfo>()
        try {
            for (target in targets) {
                val req = target.requestId
                val rep = target.replyId
                if (!session.addressEcu(req, rep)) continue
                val values = LinkedHashMap<String, String>()
                for ((did, name) in IDENTIFIERS) {
                    readDid(did)?.let { values[name] = it }
                }
                if (values.isNotEmpty()) found.add(EcuInfo(req, rep, values, target.name))
            }
        } finally {
            session.resetAddressing()
        }
        return found
    }

    /** Positive reply is `62 <DID hi> <DID lo> data`; anything else (7F negative, NO DATA) is null. */
    private fun readDid(did: Int): String? {
        val reply = session.request("22%04X".format(did), timeoutMs = 1_500L) as? ObdReply.Data ?: return null
        val p = reply.payloads.firstOrNull {
            it.size >= 4 && it[0] == 0x62.toByte() && (it[1].toInt() and 0xFF) == (did shr 8) && (it[2].toInt() and 0xFF) == (did and 0xFF)
        } ?: return null
        return render(p.copyOfRange(3, p.size))
    }

    companion object {
        val IDENTIFIERS = listOf(
            0xF190 to "VIN",
            0xF187 to "Part number",
            0xF189 to "Software version",
            0xF191 to "Hardware number",
            0xF18C to "Serial number"
        )

        /** ASCII if it is all printable, else hex. */
        fun render(data: ByteArray): String {
            val trimmed = data.dropLastWhile { it == 0.toByte() || it == 0xFF.toByte() }.toByteArray()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.all { it in 0x20..0x7E }) String(trimmed, Charsets.US_ASCII).trim()
            else trimmed.joinToString(" ") { "%02X".format(it) }
        }
    }
}
