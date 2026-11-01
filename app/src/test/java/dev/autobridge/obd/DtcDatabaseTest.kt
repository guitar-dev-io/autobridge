package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DtcDatabaseTest {
    @Test fun linesBecomeCodesAndTheHeaderAndJunkAreSkipped() {
        val map = DtcDatabase.parse(
            "CODE,DESCRIPTION\nP0133,O2 Sensor Circuit Slow Response\n\nnot a line\nP0000,No Fault\nu0100, Lost Communication With ECM/PCM A \n"
        )
        assertEquals("O2 Sensor Circuit Slow Response", map["P0133"])
        assertEquals("Lost Communication With ECM/PCM A", map["U0100"])
        assertNull(map["P0000"])
        assertEquals(2, map.size)
    }

    @Test fun aDescriptionKeepsItsOwnCommas() {
        assertEquals("A, B and C", DtcDatabase.parse("P0100,A, B and C")["P0100"])
    }

    @Test fun theShippedListLoadsAndCoversTheCommonCodes() {
        val file = listOf("src/main/assets/dtc/ford_dtc_codes.csv", "app/src/main/assets/dtc/ford_dtc_codes.csv")
            .map(::File).first { it.exists() }
        val map = DtcDatabase.parse(file.readText())
        assertTrue("expected about 700 codes, got ${map.size}", map.size > 600)
        for (code in listOf("P0133", "P0300", "P0401", "P0420", "U0100")) {
            assertTrue("$code is missing", map.containsKey(code))
        }
        assertTrue(map.keys.all { it.length == 5 })
    }
}
