package dev.autobridge.obd

import dev.autobridge.obd.coding.*
import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.transport.ObdTransport
import org.junit.Assert.*
import org.junit.Test

/** A module that remembers what is written to its DE01 block. */
private class StatefulModule(var value: String, private val volts: String = "12.6V", private val refuseWrite: String? = null, private val dropWrite: Boolean = false) : ObdTransport {
    private val base = FakeTransport.canCar()
    private var last = ""
    val sent = ArrayList<String>()
    override fun open() = Unit
    override fun write(command: String) { last = command; sent.add(command) }
    override fun readUntilPrompt(timeoutMs: Long): String = when {
        last == "ATRV" -> "$volts\r"
        last.startsWith("ATSH") || last.startsWith("ATCRA") || last == "ATAR" -> "OK\r"
        last == "22DE01" -> "62DE01$value\r"
        last == "1003" -> "5003\r"
        last == "1001" -> "5001\r"
        last.startsWith("2EDE01") -> when {
            refuseWrite != null -> "7F2E$refuseWrite\r"
            else -> { if (!dropWrite) value = last.removePrefix("2EDE01"); "6EDE01\r" }
        }
        else -> { base.write(last); base.readUntilPrompt(timeoutMs) }
    }
    override fun close() = Unit
}

class CodingTest {
    private fun writer(m: StatefulModule) = UdsWriter(ElmSession(m).also { it.connect() })
    private fun change(text: String) = (CodingRecipe.parse(text) as CodingRecipe.Parsed.Ok).recipe.changes.single()

    @Test fun parsesByteEditsAndWholeBlocksAndComments() {
        val r = (CodingRecipe.parse("name: Test\n# note\n726 de01 2 4f 5F  # inline\n726 DE01 4 00 01") as CodingRecipe.Parsed.Ok).recipe
        assertEquals("Test", r.name)
        assertEquals(2, r.changes.single().edits.size)
        val w = change("726 DE01 0804B0 0804F0")
        assertEquals("0804F0", CodingChange.hex(w.fullNew!!))
    }

    @Test fun parseErrorsNameTheLine() {
        assertEquals(1, (CodingRecipe.parse("72 DE01 2 4F 5F") as CodingRecipe.Parsed.Error).line)
        assertEquals(2, (CodingRecipe.parse("726 DE01 2 4F 5F\n726 DE01 0804 0805") as CodingRecipe.Parsed.Error).line)
        assertEquals(1, (CodingRecipe.parse("726 DE01 08 0804") as CodingRecipe.Parsed.Error).line)
        assertTrue(CodingRecipe.parse("# nothing") is CodingRecipe.Parsed.Error)
    }

    @Test fun guardBlocksSafetyModulesAndOtherRanges() {
        assertNotNull(CodingGuard.check("737", 0xDE01))
        assertNotNull(CodingGuard.check("760", 0xDE01))
        assertNotNull(CodingGuard.check("726", 0xF190))
        assertNull(CodingGuard.check("726", 0xDE01))
    }

    @Test fun writesAndVerifiesReadBack() {
        val m = StatefulModule("0804B0")
        val w = writer(m)
        val plan = (w.plan(change("726 DE01 1 04 05")) as PlanResult.Ready).plan
        assertEquals("0805B0", CodingChange.hex(plan.after))
        assertEquals("0804B0", m.value) // planning writes nothing
        assertTrue(w.apply(plan) is WriteOutcome.Done)
        assertEquals("0805B0", m.value)
        assertEquals("1001", m.sent.last { it.startsWith("10") })
        assertEquals("ATAR", m.sent.last())
    }

    @Test fun refusesWhenTheModuleHoldsADifferentValue() {
        val blocked = writer(StatefulModule("0804B0")).plan(change("726 DE01 1 99 05"))
        assertTrue((blocked as PlanResult.Blocked).reason.contains("expects 99"))
        assertTrue(writer(StatefulModule("0804B0")).plan(change("726 DE01 0000 0001")) is PlanResult.Blocked)
    }

    @Test fun lowBatteryUnknownVoltageAndSecurityDenialWriteNothing() {
        val low = StatefulModule("0804B0", volts = "11.4V")
        val lw = writer(low)
        val lp = (lw.plan(change("726 DE01 1 04 05")) as PlanResult.Ready).plan
        assertTrue(lw.apply(lp) is WriteOutcome.Refused)
        assertEquals("0804B0", low.value)

        val locked = StatefulModule("0804B0", refuseWrite = "33")
        val kw = writer(locked)
        val kp = (kw.plan(change("726 DE01 1 04 05")) as PlanResult.Ready).plan
        val out = kw.apply(kp) as WriteOutcome.Refused
        assertTrue(out.reason.contains("security access denied"))
        assertEquals("0804B0", locked.value)
    }

    @Test fun aWriteThatDoesNotStickIsReportedAndRestored() {
        val m = StatefulModule("0804B0", dropWrite = true)
        val w = writer(m)
        val p = (w.plan(change("726 DE01 1 04 05")) as PlanResult.Ready).plan
        val out = w.apply(p) as WriteOutcome.Failed
        assertTrue(out.restored) // value never changed, so the old one is confirmed present
    }

    @Test fun restorePutsAnOldValueBack() {
        val m = StatefulModule("0805B0")
        val out = writer(m).restore("726", 0xDE01, byteArrayOf(0x08, 0x04, 0xB0.toByte()))
        assertTrue(out is WriteOutcome.Done)
        assertEquals("0804B0", m.value)
    }
}
