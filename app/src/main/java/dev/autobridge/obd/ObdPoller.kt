package dev.autobridge.obd

import kotlinx.coroutines.delay

/** The adapter or the bus failed in a way that needs a fresh connection. */
class ObdFailure(message: String) : Exception(message)

/**
 * Sets an ELM327 up for a Ford and reads the gauges in a loop.
 *
 * Reads come in three tiers so the fast gauges are not slowed by the slow ones: RPM and speed on
 * every pass, temperatures and load every third, everything else every fifteenth. A PID the car
 * does not answer is retried a few times, then its next candidate is tried (Ford battery voltage
 * when the standard one is silent, the engine module when the transmission module does not answer
 * for ATF), and a slot with no working candidate is left alone for a while instead of costing a
 * timeout on every pass.
 */
class ObdPoller(
    private val session: ElmSession,
    transmission: Transmission,
    private val onValue: (Gauge, Float) -> Unit,
) {
    /** The protocol the adapter settled on, as ATDPN reports it ("A6" is auto, CAN 11 bit 500 k). */
    var protocol: String? = null
        private set

    private var canHeaders = false
    private var currentHeader = STANDARD_HEADER

    private class Slot(val candidates: List<Pid>) {
        var active = 0
        var misses = 0
        var idleUntilPass = 0
        val pid get() = candidates[active]
    }

    private fun slot(vararg pids: Pid) = Slot(pids.toList())

    private val fast = listOf(slot(ObdPids.RPM), slot(ObdPids.SPEED))
    private val medium = listOf(
        slot(ObdPids.COOLANT), slot(ObdPids.LOAD), slot(ObdPids.MAP), slot(ObdPids.INTAKE),
    )
    private val slow = listOf(
        slot(ObdPids.BARO),
        slot(ObdPids.BATTERY_STANDARD, ObdPids.BATTERY_FORD),
        Slot(ObdPids.atf(transmission)),
        slot(ObdPids.FUEL_LEVEL),
        slot(ObdPids.AMBIENT),
    )

    suspend fun initialize() {
        // The reset can take a few seconds and some adapters answer it with noise; neither matters.
        try { session.request("ATZ", 3_000) } catch (_: ObdTimeoutException) { }
        for (command in SETUP) session.request(command, 1_500)
        session.request("ATSP0", 1_500)
        // The first real request makes the adapter search for the car's protocol, which takes seconds.
        val probe = session.request("0100", 12_000).uppercase()
        if ("UNABLE TO CONNECT" in probe || "BUS INIT" in probe) {
            throw ObdFailure("The car did not answer. Is the ignition on?")
        }
        val id = session.request("ATDPN", 1_500).trim().uppercase()
        protocol = id.takeIf { it.isNotEmpty() }
        val digit = id.removePrefix("A").firstOrNull()
        canHeaders = digit != null && digit in '6'..'9'
        currentHeader = STANDARD_HEADER
    }

    /** Reads until cancelled. Throws [ObdFailure] or [ObdTimeoutException] when the link is no good. */
    suspend fun poll() {
        var pass = 0
        while (true) {
            var answered = 0
            answered += readAll(fast, pass)
            if (pass % 3 == 0) answered += readAll(medium, pass)
            if (pass % 15 == 0) answered += readAll(slow, pass)
            pass++
            // A car that answers nothing (ignition just went off) must not spin the adapter.
            delay(if (answered == 0) 500L else PASS_PAUSE_MS)
        }
    }

    private suspend fun readAll(slots: List<Slot>, pass: Int): Int {
        var answered = 0
        for (slot in slots) {
            if (pass < slot.idleUntilPass) continue
            if (read(slot, pass)) answered++
        }
        return answered
    }

    private suspend fun read(slot: Slot, pass: Int): Boolean {
        val pid = slot.pid
        selectHeader(pid.header ?: STANDARD_HEADER)
        when (val reply = session.read(pid)) {
            is Reply.Value -> {
                val value = pid.decode(reply.data)
                if (isPlausible(pid.gauge, value)) {
                    slot.misses = 0
                    onValue(pid.gauge, value)
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

    private fun isPlausible(gauge: Gauge, value: Float): Boolean =
        PLAUSIBLE[gauge]?.contains(value) ?: true

    companion object {
        const val STANDARD_HEADER = "7DF"
        private const val PASS_PAUSE_MS = 20L
        private const val MISSES_BEFORE_NEXT = 3
        private const val IDLE_PASSES = 300
        // ATST64 is a 400 ms wait for an answer: the transmission module is slower than the engine
        // module, and the adapter's default of 200 ms can read its silence as "no data".
        private val SETUP = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATST64")
        private val PLAUSIBLE: Map<Gauge, ClosedFloatingPointRange<Float>> = mapOf(
            Gauge.RPM to 0f..9_000f,
            Gauge.SPEED to 0f..255f,
            Gauge.ATF to -40f..200f,
            Gauge.BATTERY to 6f..20f,
            Gauge.COOLANT to -40f..215f,
        )
    }
}
