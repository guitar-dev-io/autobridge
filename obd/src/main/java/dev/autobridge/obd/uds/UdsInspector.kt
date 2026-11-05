package dev.autobridge.obd.uds

import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.elm.ObdReply

/** One answer to a UDS request, reduced to what a caller needs. */
sealed class UdsAnswer {
    class Positive(val data: ByteArray) : UdsAnswer()
    class Negative(val code: Int) : UdsAnswer() {
        val text: String get() = nrcText(code)
    }
    object Silent : UdsAnswer()
}

fun nrcText(code: Int): String = when (code) {
    0x10 -> "general reject"
    0x11 -> "service not supported"
    0x12 -> "sub-function not supported"
    0x13 -> "incorrect message length"
    0x22 -> "conditions not correct"
    0x24 -> "request sequence error"
    0x31 -> "request out of range"
    0x33 -> "security access denied"
    0x35 -> "invalid key"
    0x36 -> "exceeded number of attempts"
    0x37 -> "required time delay not expired"
    0x7E -> "sub-function not supported in active session"
    0x7F -> "service not supported in active session"
    else -> "code 0x%02X".format(code)
}

enum class Access { ACCEPTED, REFUSED, SILENT }

/**
 * What a module will let a tester do, found without changing anything stored: whether it enters
 * the extended diagnostic session, and whether it asks for security access (a seed comes back
 * non-zero) or is already open. Entering a session and asking for a seed are transient - the
 * module drops back to its default session by itself - but they are not "read-only" in the strict
 * sense, so [UdsInspector.probe] returns to the default session when it is done.
 */
data class AccessProbe(
    val extendedSession: Access,
    val extendedRefusal: String?,
    val security: Access,
    val securityRefusal: String?,
    val seedLength: Int,
    /** A seed of all zeros means the module is already unlocked. */
    val alreadyUnlocked: Boolean
)

data class DidDump(val did: Int, val value: ByteArray?, val refusal: String?)

/** Read-only inspection of one addressed module: raw DID reads, an access probe, a configuration backup. */
class UdsInspector(private val session: ElmSession) {

    fun request(hex: String): UdsAnswer {
        val reply = session.request(hex, timeoutMs = 1_500L) as? ObdReply.Data ?: return UdsAnswer.Silent
        val p = reply.first
        if (p.size >= 3 && p[0] == 0x7F.toByte()) return UdsAnswer.Negative(p[2].toInt() and 0xFF)
        return UdsAnswer.Positive(p)
    }

    fun readDid(did: Int): UdsAnswer {
        val answer = request("22%04X".format(did))
        if (answer !is UdsAnswer.Positive) return answer
        val p = answer.data
        val ok = p.size >= 3 && p[0] == 0x62.toByte() && (p[1].toInt() and 0xFF) == (did shr 8) && (p[2].toInt() and 0xFF) == (did and 0xFF)
        return if (ok) UdsAnswer.Positive(p.copyOfRange(3, p.size)) else UdsAnswer.Silent
    }

    /** The module must already be addressed ([ElmSession.addressEcu]). */
    fun probe(): AccessProbe {
        var extended = Access.SILENT
        var extendedRefusal: String? = null
        var security = Access.SILENT
        var securityRefusal: String? = null
        var seedLength = 0
        var unlocked = false
        try {
            when (val a = request("1003")) {
                is UdsAnswer.Positive -> extended = if (a.data.firstOrNull() == 0x50.toByte()) Access.ACCEPTED else Access.SILENT
                is UdsAnswer.Negative -> { extended = Access.REFUSED; extendedRefusal = a.text }
                UdsAnswer.Silent -> Unit
            }
            when (val a = request("2701")) {
                is UdsAnswer.Positive -> if (a.data.size >= 2 && a.data[0] == 0x67.toByte()) {
                    val seed = a.data.copyOfRange(2, a.data.size)
                    security = Access.ACCEPTED
                    seedLength = seed.size
                    unlocked = seed.isNotEmpty() && seed.all { it == 0.toByte() }
                }
                is UdsAnswer.Negative -> { security = Access.REFUSED; securityRefusal = a.text }
                UdsAnswer.Silent -> Unit
            }
        } finally {
            request("1001") // back to the default session
        }
        return AccessProbe(extended, extendedRefusal, security, securityRefusal, seedLength, unlocked)
    }

    /** Reads every DID in [dids]; a DID the module refuses is recorded with the reason, not skipped. */
    fun backup(dids: Iterable<Int>, cancelled: () -> Boolean = { false }): List<DidDump> {
        val out = ArrayList<DidDump>()
        for (did in dids) {
            if (cancelled()) break
            when (val a = readDid(did)) {
                is UdsAnswer.Positive -> out.add(DidDump(did, a.data, null))
                is UdsAnswer.Negative -> {
                    // "Out of range" just means the module has no such DID; keep the list to what exists
                    // plus the ones that exist but are protected.
                    if (a.code != 0x31) out.add(DidDump(did, null, a.text))
                }
                UdsAnswer.Silent -> Unit
            }
        }
        return out
    }

    companion object {
        /** Identification block and the 0xDE00 block Ford uses for module configuration (unverified here). */
        val DEFAULT_RANGES: List<IntRange> = listOf(0xF180..0xF1A0, 0xDE00..0xDE3F)

        fun csv(vin: String?, module: EcuInfo, dumps: List<DidDump>): String {
            val sb = StringBuilder("# AutoBridge module backup (read-only)\n")
            sb.append("# vin,").append(vin ?: "").append('\n')
            sb.append("# module,").append(module.name ?: "").append(',').append(module.requestId).append('\n')
            sb.append("did,value_hex,note\n")
            for (d in dumps) {
                sb.append("%04X,".format(d.did))
                d.value?.let { v -> sb.append(v.joinToString("") { "%02X".format(it) }) }
                sb.append(',').append(d.refusal ?: "").append('\n')
            }
            return sb.toString()
        }
    }
}
