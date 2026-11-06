package dev.autobridge.obd

import dev.autobridge.obd.profile.Formula
import dev.autobridge.obd.profile.PidDef
import dev.autobridge.obd.profile.VehicleProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The adapter or the bus failed in a way that needs a fresh connection. */
class ObdFailure(message: String) : Exception(message)

/**
 * Sets an ELM327 up and reads the gauges of a [VehicleProfile] in a loop.
 *
 * Reads come in three tiers so the fast gauges are not slowed by the slow ones: RPM and speed on
 * every pass, the temperatures, load and pressures every third, everything else (including any
 * gauge a profile adds) every fifteenth. A gauge with several candidate PIDs keeps the first the
 * car answers: one the car does not answer is retried a few times, then the next candidate is
 * tried (the standard battery voltage before Ford's, the transmission module before the engine
 * module for gearbox oil), and a gauge with no working candidate is left alone for a while
 * instead of costing a timeout on every pass.
 */
class ObdPoller(
    private val session: ElmSession,
    profile: VehicleProfile,
    private val onValue: (Gauge, Float) -> Unit,
) {
    /** The protocol the adapter settled on, as ATDPN reports it ("A6" is auto, CAN 11 bit 500 k). */
    var protocol: String? = null
    /** The protocol digit the adapter settled on, to be tried first next time. */
    var protocolDigit: Char? = null
        private set

    private var canHeaders = false
    private var currentHeader = STANDARD_HEADER

    /** A candidate PID with its formula already compiled; a formula that does not compile is left out. */
    private class Candidate(val pid: PidDef, val formula: Formula)

    private class Slot(val candidates: List<Candidate>) {
        var active = 0
        var misses = 0
        var idleUntilPass = 0
        val candidate get() = candidates[active]
    }

    private val fast = ArrayList<Slot>()
    private val medium = ArrayList<Slot>()
    private val slow = ArrayList<Slot>()

    init {
        for (gauge in profile.gauges) {
            val candidates = profile.candidates(gauge).mapNotNull { pid ->
                Formula.parse(pid.formula)?.let { Candidate(pid, it) }
            }
            if (candidates.isEmpty()) continue
            val slot = Slot(candidates)
            when (gauge) {
                in FAST -> fast += slot
                in MEDIUM -> medium += slot
                else -> slow += slot
            }
        }
    }

    /**
     * [preferredProtocol] is the protocol digit that worked last time (ATDPN's answer without the
     * "A"). Trying it first skips the adapter's search, which takes seconds; when the car does not
     * answer on it, the usual automatic search runs, so a changed car still connects.
     */
    suspend fun initialize(preferredProtocol: Char? = null) {
        // The reset can take a few seconds and some adapters answer it with noise; neither matters.
        try { session.request("ATZ", 3_000) } catch (_: ObdTimeoutException) { }
        for (command in SETUP) session.request(command, 1_500)
        var probe = ""
        if (preferredProtocol != null && preferredProtocol.uppercaseChar() in PROTOCOL_DIGITS) {
            session.request("ATSP${preferredProtocol.uppercaseChar()}", 1_500)
            probe = session.request("0100", 4_000).uppercase()
            if (!answered(probe) || "NO DATA" in probe || "ERROR" in probe) probe = ""
        }
        if (probe.isEmpty()) {
            session.request("ATSP0", 1_500)
            // The first real request makes the adapter search for the car's protocol, which takes seconds.
            probe = session.request("0100", 12_000).uppercase()
            if (!answered(probe)) throw ObdFailure("The car did not answer. Is the ignition on?")
        }
        val id = session.request("ATDPN", 1_500).trim().uppercase()
        protocol = id.takeIf { it.isNotEmpty() }
        val digit = id.removePrefix("A").firstOrNull()
        protocolDigit = digit?.takeIf { it.uppercaseChar() in PROTOCOL_DIGITS }
        canHeaders = digit != null && digit in '6'..'9'
        currentHeader = STANDARD_HEADER
    }

    /** Reads until cancelled. Throws [ObdFailure] or [ObdTimeoutException] when the link is no good. */
    suspend fun poll() {
        var pass = 0
        while (true) {
            // One pass at a time under the gate, so a trouble-code request can slip in between
            // passes and never in the middle of one (the header it sets would be changed under it).
            val answered = gate.withLock {
                var count = 0
                count += readAll(fast, pass)
                if (pass % 3 == 0) count += readAll(medium, pass)
                if (pass % 15 == 0) count += readAll(slow, pass)
                count
            }
            pass++
            // A car that answers nothing (ignition just went off) must not spin the adapter.
            delay(if (answered == 0) 500L else PASS_PAUSE_MS)
        }
    }

    private val gate = Mutex()

    /**
     * Runs [block] with the adapter to itself and set to talk to every control unit (the broadcast
     * header), then lets the gauges carry on. Used for the requests that are not gauge readings.
     */
    suspend fun <T> exclusively(block: suspend (ElmSession) -> T): T = gate.withLock {
        selectHeader(STANDARD_HEADER)
        block(session)
    }

    /** True when the adapter is on CAN, where an answer to a trouble-code request starts with a count. */
    val isCan: Boolean get() = canHeaders

    private suspend fun readAll(slots: List<Slot>, pass: Int): Int {
        var answered = 0
        for (slot in slots) {
            if (pass < slot.idleUntilPass) continue
            if (read(slot, pass)) answered++
        }
        return answered
    }

    private suspend fun read(slot: Slot, pass: Int): Boolean {
        val candidate = slot.candidate
        val pid = candidate.pid
        selectHeader(pid.header ?: STANDARD_HEADER)
        when (val reply = session.read(pid)) {
            is Reply.Value -> {
                val value = candidate.formula.evaluate(reply.data)
                if (value != null && isPlausible(pid, value)) {
                    slot.misses = 0
                    onValue(Gauge(pid.gauge), value)
                    return true
                }
                miss(slot, pass)
            }
            Reply.NoData -> miss(slot, pass)
            is Reply.Failure -> throw ObdFailure(reply.text)
        }
        return false
    }

    private fun miss(slot: Slot, pass: Int) {
        slot.misses++
        if (slot.misses < MISSES_BEFORE_NEXT) return
        slot.misses = 0
        slot.active = (slot.active + 1) % slot.candidates.size
        // Every candidate has had its turn; give the car a rest before trying this gauge again.
        if (slot.active == 0) slot.idleUntilPass = pass + IDLE_PASSES
    }

    private suspend fun selectHeader(header: String) {
        if (!canHeaders || header == currentHeader) return
        session.request("ATSH$header", 1_000)
        currentHeader = header
    }

    private fun isPlausible(pid: PidDef, value: Float): Boolean {
        val lo = pid.plausibleMin
        val hi = pid.plausibleMax
        return (lo == null || value >= lo) && (hi == null || value <= hi)
    }

    companion object {
        const val STANDARD_HEADER = "7DF"
        private const val PASS_PAUSE_MS = 20L
        private const val MISSES_BEFORE_NEXT = 3
        private const val IDLE_PASSES = 300
        private val FAST = setOf("rpm", "speed")
        private val MEDIUM = setOf("coolant", "load", "map", "intake", "throttle")
        // ATST64 is a 400 ms wait for an answer: the transmission module is slower than the engine
        // module, and the adapter's default of 200 ms can read its silence as "no data".
        private const val PROTOCOL_DIGITS = "123456789ABC"
        private fun answered(probe: String) = "UNABLE TO CONNECT" !in probe && "BUS INIT" !in probe
        private val SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST64")
    }
}
