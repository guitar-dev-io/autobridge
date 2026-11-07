package dev.autobridge.obd

import dev.autobridge.obd.coding.*
import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.transport.ObdTransport
import org.junit.Assert.*
import org.junit.Test

/** A module that remembers what is written to its DE01 block. */
private class StatefulModule(var value: String, private val volts: String = "12.6V", private val refuseWrite: String? = null, private val dropWrite: Boolean = false, private val pending: Boolean = false) : ObdTransport {
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
            // Busy with it: says "response pending" and stores the value anyway.
            pending -> { value = last.removePrefix("2EDE01"); "7F2E78\r" }
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
        assertNotNull(CodingGuard.check("706", 0xDE01))
        assertNotNull(CodingGuard.check("734", 0xDE01))
        assertNotNull(CodingGuard.check("726", 0xF190))
        assertNull(CodingGuard.check("726", 0xDE01))
        // Engine and transmission stay closed until a write to them is checked on a car.
        assertNotNull(CodingGuard.check("7E0", 0xDE01))
        assertNotNull(CodingGuard.check("7E1", 0xDE01))
    }

    @Test fun aPendingReplyThatStoredTheValueIsReportedAsDone() {
        val m = StatefulModule("0804B0", pending = true)
        val w = writer(m)
        val p = (w.plan(change("726 DE01 1 04 05")) as PlanResult.Ready).plan
        assertTrue(w.apply(p) is WriteOutcome.Done)
        assertEquals("0805B0", m.value)
    }

    @Test fun theChecksumByteOfASixByteBlockIsNeverChanged() {
        val w = writer(StatefulModule("0102030405AA"))
        assertTrue(w.plan(change("726 DE01 5 AA AB")) is PlanResult.Blocked)
        assertTrue(w.plan(change("726 DE01 0102030405AA 0102030406AB")) is PlanResult.Blocked)
        assertTrue(w.plan(change("726 DE01 4 05 06")) is PlanResult.Ready)
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
        // Acknowledged, but the read-back still shows the old value: nothing changed, and it says so.
        val out = w.apply(p) as WriteOutcome.Refused
        assertTrue(out.reason.contains("kept the old value"))
        assertEquals("0804B0", m.value)
    }

    @Test fun restorePutsAnOldValueBack() {
        val m = StatefulModule("0805B0")
        val out = writer(m).restore("726", 0xDE01, byteArrayOf(0x08, 0x04, 0xB0.toByte()))
        assertTrue(out is WriteOutcome.Done)
        assertEquals("0804B0", m.value)
    }
}

class CodingCatalogTest {
    private val csv = "feature_id,feature_en,module,asbuilt_address,source_pattern,risk,engineering_notes\n" +
        "R1,\"Fog, high beam\",BdyCM,726-39-02,xxx* xxxx xx--,Medium,\"say \"\"hi\"\"\"\n" +
        "R2,Bad,X,nope,xx,Low,\n" +
        "R3,Heated,SCCM,724-06-01,*xxx xxxx --,Medium,\n"

    @Test fun parsesRowsQuotesAndSkipsBadAddresses() {
        val list = CodingCatalog.parse(csv)
        assertEquals(listOf("R1", "R3"), list.map { it.id })
        val a = list[0]
        assertEquals("Fog, high beam", a.featureEn)
        assertEquals("say \"hi\"", a.engineeringNotes)
        assertEquals("726", a.ecu)
        assertEquals(0xDE39, a.did)
        assertEquals(setOf(3), a.markedNibbles)
        assertEquals("726-39-01".replace("01", "02"), a.address)
        assertEquals(setOf(0), list[1].markedNibbles)
    }

    @Test fun byteOrderMarkDoesNotHideTheIdColumn() {
        assertEquals(listOf("R1", "R3"), CodingCatalog.parse("\uFEFF" + csv).map { it.id })
    }

    @Test fun marksOnlyWhenValueIsOneLine() {
        val e = CodingCatalog.parse(csv)[0]
        assertEquals("0804B00102" to setOf(3), CodingCatalog.marked(e, byteArrayOf(8, 4, 0xB0.toByte(), 1, 2)))
        assertTrue(CodingCatalog.marked(e, ByteArray(12)).second.isEmpty())
    }
}

class BlockDraftTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun editsOneDigitAndTracksChanges() {
        val d = BlockDraft("726", 0xDE39, bytes("0804B00102"))
        assertNull(d.toChange())
        assertEquals(0xB, d.nibble(4))
        assertTrue(d.set(5, 0x1))
        assertEquals("0804B10102", CodingChange.hex(d.value))
        assertEquals(setOf(5), d.changed)
        assertEquals("726 DE39 0804B00102 0804B10102", d.recipeLine())
        val c = d.toChange()!!
        assertEquals("0804B10102", CodingChange.hex(c.applyTo(bytes("0804B00102")).getOrThrow()))
        assertTrue(c.applyTo(bytes("0804B00103")).isFailure)
        d.reset()
        assertTrue(d.changed.isEmpty())
    }

    @Test fun checksumByteOfSixByteLineIsLocked() {
        val d = BlockDraft("726", 0xDE39, bytes("0804B0010277"))
        assertFalse(d.set(10, 1))
        assertFalse(d.set(11, 1))
        assertFalse(d.set(12, 1))
        assertFalse(d.set(0, 16))
        assertTrue(d.set(9, 0xF))
        assertEquals("0101", BlockDraft.binary(5))
    }
}
