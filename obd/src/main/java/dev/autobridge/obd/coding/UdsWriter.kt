package dev.autobridge.obd.coding

import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.uds.EcuTarget
import dev.autobridge.obd.uds.UdsAnswer
import dev.autobridge.obd.uds.UdsInspector

/** A write that has been checked against the module's current value but not yet sent. */
class CodingPlan(val change: CodingChange, val before: ByteArray, val after: ByteArray) {
    val summary: String get() = "${change.module} %04X: ${CodingChange.hex(before)} -> ${CodingChange.hex(after)}".format(change.did)
}

sealed class PlanResult {
    class Ready(val plan: CodingPlan) : PlanResult()
    class Blocked(val reason: String) : PlanResult()
}

sealed class WriteOutcome {
    class Done(val plan: CodingPlan) : WriteOutcome()
    /** Nothing was written. */
    class Refused(val reason: String) : WriteOutcome()
    /** A write was sent but did not read back; [restored] says whether the old value was put back and confirmed. */
    class Failed(val reason: String, val restored: Boolean) : WriteOutcome()
}

/**
 * The only code that writes to a module. It never unlocks security access: a module that asks for
 * a key refuses the write and stays untouched. Every write is checked against the current value,
 * confined by [CodingGuard], read back, and undone if the read-back differs.
 */
class UdsWriter(private val session: ElmSession) {
    private val inspector = UdsInspector(session)

    fun plan(change: CodingChange): PlanResult {
        CodingGuard.check(change.module, change.did)?.let { return PlanResult.Blocked(it) }
        return withModule(change.module, { PlanResult.Blocked(it) }) {
            val current = (inspector.readDid(change.did) as? UdsAnswer.Positive)?.data
                ?: return@withModule PlanResult.Blocked("module did not return block %04X".format(change.did))
            change.applyTo(current).fold(
                { PlanResult.Ready(CodingPlan(change, current, it)) },
                { PlanResult.Blocked(it.message ?: "does not match") }
            )
        }
    }

    fun apply(plan: CodingPlan, minVolts: Double = MIN_VOLTS): WriteOutcome {
        val change = plan.change
        CodingGuard.check(change.module, change.did)?.let { return WriteOutcome.Refused(it) }
        val volts = session.voltage()?.removeSuffix("V")?.trim()?.toDoubleOrNull()
            ?: return WriteOutcome.Refused("battery voltage unknown")
        if (volts < minVolts) return WriteOutcome.Refused("battery %.1f V is below %.1f V".format(volts, minVolts))

        return withModule(change.module, { WriteOutcome.Refused(it) }) {
            val now = (inspector.readDid(change.did) as? UdsAnswer.Positive)?.data
            if (now == null || !now.contentEquals(plan.before)) return@withModule WriteOutcome.Refused("the module's value changed since the preview")
            try {
                if (inspector.request("1003") !is UdsAnswer.Positive) return@withModule WriteOutcome.Refused("module refused the extended session")
                when (val w = write(change.did, plan.after)) {
                    is UdsAnswer.Negative -> return@withModule WriteOutcome.Refused("module refused the write: ${w.text}")
                    UdsAnswer.Silent -> return@withModule WriteOutcome.Refused("module did not answer the write")
                    is UdsAnswer.Positive -> Unit
                }
                val back = (inspector.readDid(change.did) as? UdsAnswer.Positive)?.data
                if (back != null && back.contentEquals(plan.after)) return@withModule WriteOutcome.Done(plan)
                // The write was acknowledged but did not stick: put the old value back and check that.
                write(change.did, plan.before)
                val restored = (inspector.readDid(change.did) as? UdsAnswer.Positive)?.data?.contentEquals(plan.before) == true
                WriteOutcome.Failed("read-back did not match", restored)
            } finally {
                inspector.request("1001")
            }
        }
    }

    /** Puts [value] back with no precondition beyond the guard and the battery; used by Restore. */
    fun restore(module: String, did: Int, value: ByteArray, minVolts: Double = MIN_VOLTS): WriteOutcome {
        val current = withModule(module, { null }) { (inspector.readDid(did) as? UdsAnswer.Positive)?.data } ?: return WriteOutcome.Refused("module did not answer")
        if (current.contentEquals(value)) return WriteOutcome.Refused("the module already has this value")
        return apply(CodingPlan(CodingChange(module, did, emptyList(), current, value), current, value), minVolts)
    }

    private fun write(did: Int, value: ByteArray): UdsAnswer {
        val answer = inspector.request("2E%04X".format(did) + CodingChange.hex(value))
        if (answer !is UdsAnswer.Positive) return answer
        val p = answer.data
        return if (p.size >= 3 && p[0] == 0x6E.toByte() && (p[1].toInt() and 0xFF) == (did shr 8) && (p[2].toInt() and 0xFF) == (did and 0xFF)) answer else UdsAnswer.Silent
    }

    private fun <T> withModule(module: String, unreachable: (String) -> T, block: () -> T): T {
        val target = EcuTarget(module.uppercase(), null)
        if (!session.addressEcu(target.requestId, target.replyId)) return unreachable("module $module not reachable")
        try {
            return block()
        } finally {
            session.resetAddressing()
        }
    }

    companion object {
        const val MIN_VOLTS = 12.2
    }
}
