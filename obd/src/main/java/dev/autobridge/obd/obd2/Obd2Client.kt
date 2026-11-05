package dev.autobridge.obd.obd2

import dev.autobridge.obd.dtc.DtcCode
import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.elm.ObdFailure
import dev.autobridge.obd.elm.ObdReply

/** Outcome of a request that should produce a value. */
sealed class ObdResult<out T> {
    data class Ok<T>(val value: T) : ObdResult<T>()
    data class Err(val failure: ObdFailure) : ObdResult<Nothing>()
}

enum class DtcKind(val mode: Int) { STORED(0x03), PENDING(0x07), PERMANENT(0x0A) }

data class Monitor(val name: String, val supported: Boolean, val complete: Boolean)

/** Mode 01 PID 01: warning lamp, stored-code count and the emissions readiness monitors. */
data class Readiness(val milOn: Boolean, val codeCount: Int, val compressionIgnition: Boolean, val monitors: List<Monitor>)

data class FreezeFrame(val triggeringCode: DtcCode?, val values: List<PidValue>)

/**
 * The generic OBD-II services (SAE J1979) Phase 1 needs: live data, trouble codes, freeze frame,
 * readiness and vehicle identification. Stateless apart from the session it talks through.
 */
class Obd2Client(private val session: ElmSession) {

    fun supportedPids(): Set<Int> {
        val supported = HashSet<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val data = (mode01(base) as? ObdResult.Ok)?.value ?: break
            if (data.size < 4) break
            for (i in 0 until 32) {
                if (data[i / 8].toInt() and (0x80 shr (i % 8)) != 0) supported.add(base + i + 1)
            }
            // The last bit of each bitmap says whether the next block exists.
            if (supported.contains(base + 0x20)) base += 0x20 else break
        }
        return supported
    }

    fun readPid(id: Int): ObdResult<PidValue> {
        val pid = Pids.find(id) ?: return ObdResult.Err(ObdFailure.Unsupported)
        return when (val r = mode01(id)) {
            is ObdResult.Err -> r
            is ObdResult.Ok -> {
                if (r.value.size < pid.bytes) ObdResult.Err(ObdFailure.Other("short reply"))
                else ObdResult.Ok(PidValue(pid, pid.decode(IntArray(pid.bytes) { r.value[it].toInt() and 0xFF })))
            }
        }
    }

    fun readiness(): ObdResult<Readiness> = when (val r = mode01(0x01)) {
        is ObdResult.Err -> r
        is ObdResult.Ok -> decodeReadiness(r.value)?.let { ObdResult.Ok(it) } ?: ObdResult.Err(ObdFailure.Other("short reply"))
    }

    fun readDtcs(kind: DtcKind): ObdResult<List<DtcCode>> {
        val reply = session.request("%02X".format(kind.mode))
        return when (reply) {
            is ObdReply.Failed -> if (reply.failure == ObdFailure.NoData) ObdResult.Ok(emptyList()) else ObdResult.Err(reply.failure)
            is ObdReply.Data -> {
                val isCan = session.info?.isCan ?: true
                val codes = reply.payloads.flatMap { decodeDtcPayload(it, 0x40 or kind.mode, isCan) }
                ObdResult.Ok(codes.distinct())
            }
        }
    }

    /** Mode 04. Also resets the readiness monitors, which the UI warns about. */
    fun clearDtcs(): ObdResult<Unit> = when (val reply = session.request("04", timeoutMs = 5_000L)) {
        is ObdReply.Failed -> ObdResult.Err(reply.failure)
        is ObdReply.Data -> if (reply.payloads.any { it.firstOrNull() == 0x44.toByte() }) ObdResult.Ok(Unit)
        else ObdResult.Err(ObdFailure.Other("clear not acknowledged"))
    }

    fun freezeFrame(pidIds: List<Int> = Pids.defaultLive): ObdResult<FreezeFrame> {
        val code = when (val reply = session.request("020200")) {
            is ObdReply.Data -> reply.payloads.firstNotNullOfOrNull { p ->
                if (p.size >= 5 && p[0] == 0x42.toByte() && p[1] == 0x02.toByte()) DtcCode.decode(p[3].toInt() and 0xFF, p[4].toInt() and 0xFF).takeIf { it.raw != 0 } else null
            }
            is ObdReply.Failed -> if (reply.failure == ObdFailure.NoData) return ObdResult.Err(ObdFailure.NoData) else return ObdResult.Err(reply.failure)
        }
        val values = pidIds.mapNotNull { id ->
            val pid = Pids.find(id) ?: return@mapNotNull null
            val reply = session.request("02%02X00".format(id)) as? ObdReply.Data ?: return@mapNotNull null
            val p = reply.payloads.firstOrNull { it.size >= 4 + pid.bytes && it[0] == 0x42.toByte() && (it[1].toInt() and 0xFF) == id } ?: return@mapNotNull null
            PidValue(pid, pid.decode(IntArray(pid.bytes) { p[3 + it].toInt() and 0xFF }))
        }
        return ObdResult.Ok(FreezeFrame(code, values))
    }

    fun vin(): ObdResult<String> = when (val reply = session.request("0902")) {
        is ObdReply.Failed -> ObdResult.Err(reply.failure)
        is ObdReply.Data -> decodeVin(reply.payloads)?.let { ObdResult.Ok(it) } ?: ObdResult.Err(ObdFailure.Other("no VIN"))
    }

    /** Sends `01xx` and returns the data bytes after the echoed `41 xx`. */
    private fun mode01(pid: Int): ObdResult<ByteArray> {
        return when (val reply = session.request("01%02X".format(pid))) {
            is ObdReply.Failed -> ObdResult.Err(reply.failure)
            is ObdReply.Data -> {
                val p = reply.payloads.firstOrNull { it.size >= 2 && it[0] == 0x41.toByte() && (it[1].toInt() and 0xFF) == pid }
                if (p == null) ObdResult.Err(ObdFailure.Other("unexpected reply")) else ObdResult.Ok(p.copyOfRange(2, p.size))
            }
        }
    }

    companion object {
        /** [payload] starts with the response mode byte (0x43/0x47/0x4A). CAN adds a count byte. */
        fun decodeDtcPayload(payload: ByteArray, responseMode: Int, isCan: Boolean): List<DtcCode> {
            if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != responseMode) return emptyList()
            var i = 1
            if (isCan) i++
            val codes = ArrayList<DtcCode>()
            while (i + 1 < payload.size) {
                val a = payload[i].toInt() and 0xFF
                val b = payload[i + 1].toInt() and 0xFF
                if (a != 0 || b != 0) codes.add(DtcCode.decode(a, b))
                i += 2
            }
            return codes
        }

        fun decodeReadiness(d: ByteArray): Readiness? {
            if (d.size < 4) return null
            val a = d[0].toInt() and 0xFF
            val b = d[1].toInt() and 0xFF
            val c = d[2].toInt() and 0xFF
            val dd = d[3].toInt() and 0xFF
            val diesel = b and 0x08 != 0
            val monitors = ArrayList<Monitor>()
            // Continuous monitors: support is bits 0-2 of B, "not complete" is bits 4-6.
            monitors.add(Monitor("Misfire", b and 0x01 != 0, b and 0x10 == 0))
            monitors.add(Monitor("Fuel system", b and 0x02 != 0, b and 0x20 == 0))
            monitors.add(Monitor("Components", b and 0x04 != 0, b and 0x40 == 0))
            val names = if (diesel) listOf("NMHC catalyst", "NOx/SCR", "", "Boost pressure", "", "Exhaust sensor", "PM filter", "EGR/VVT")
            else listOf("Catalyst", "Heated catalyst", "Evaporative", "Secondary air", "A/C refrigerant", "Oxygen sensor", "O2 sensor heater", "EGR")
            for (bit in 0 until 8) {
                if (names[bit].isEmpty()) continue
                monitors.add(Monitor(names[bit], c and (1 shl bit) != 0, dd and (1 shl bit) == 0))
            }
            return Readiness(a and 0x80 != 0, a and 0x7F, diesel, monitors)
        }

        fun decodeVin(payloads: List<ByteArray>): String? {
            val chars = StringBuilder()
            for (p in payloads) {
                // 49 02 <seq> then ASCII. CAN gives one reassembled message, older buses one per line.
                if (p.size < 3 || p[0] != 0x49.toByte() || p[1] != 0x02.toByte()) continue
                for (i in 3 until p.size) {
                    val ch = (p[i].toInt() and 0xFF).toChar()
                    if (ch.isLetterOrDigit() && ch.code < 128) chars.append(ch)
                }
            }
            return chars.toString().takeIf { it.length >= 17 }?.takeLast(17)
        }
    }
}
