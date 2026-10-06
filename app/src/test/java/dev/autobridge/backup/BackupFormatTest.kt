package dev.autobridge.backup

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupFormatTest {
    private val sample = Backup(ev = true, fuel = """[{"id":1,"liters":30.5}]""", maintenance = "[]", trips = """[{"id":2}]""")

    @Test fun aBackupReadsBackAsItWasWritten() {
        val back = BackupFormat.read(BackupFormat.write(sample))!!
        assertEquals(true, back.ev)
        // org.json does not promise key order, so compare what each side parses to.
        assertEquals(JSONArray(sample.fuel).toString(), back.fuel)
        assertEquals("[]", back.maintenance)
        assertEquals(JSONArray(sample.trips).toString(), back.trips)
    }

    @Test fun anOlderBackupWithoutExpensesStillReads() {
        val back = BackupFormat.read("""{"app":"autobridge","version":1,"ev":false,"fuel":[],"maintenance":[],"trips":[]}""")!!
        assertEquals("[]", back.expenses)
    }

    @Test fun expensesRoundTrip() {
        val withExpenses = sample.copy(expenses = """[{"id":3,"baht":250.0}]""")
        assertEquals(JSONArray(withExpenses.expenses).toString(), BackupFormat.read(BackupFormat.write(withExpenses))!!.expenses)
    }

    @Test fun somethingElseIsNotABackup() {
        assertNull(BackupFormat.read("not json"))
        assertNull(BackupFormat.read("""{"app":"other","version":1,"fuel":[],"maintenance":[],"trips":[]}"""))
        assertNull(BackupFormat.read("""{"app":"autobridge","version":1,"fuel":[]}"""))
    }

    @Test fun aNewerFormatIsRefusedRatherThanHalfRead() {
        assertNull(BackupFormat.read("""{"app":"autobridge","version":99,"fuel":[],"maintenance":[],"trips":[]}"""))
    }
}
