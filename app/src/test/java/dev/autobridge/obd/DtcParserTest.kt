package dev.autobridge.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class DtcParserTest {
    @Test fun twoBytesBecomeACode() {
        assertEquals("P0133", DtcParser.decode(0x01, 0x33))
        assertEquals("P0300", DtcParser.decode(0x03, 0x00))
        assertEquals("C0035", DtcParser.decode(0x40, 0x35))
        assertEquals("B2234", DtcParser.decode(0xA2, 0x34))
        assertEquals("U0100", DtcParser.decode(0xC1, 0x00))
        assertEquals("P1A2F", DtcParser.decode(0x1A, 0x2F))
    }

    @Test fun aCanFrameStartsWithACountAndIsPadded() {
        val raw = "43 02 01 33 03 00 00\r\r"
        assertEquals(listOf("P0133", "P0300"), DtcParser.parse(raw, 0x43, hasCount = true))
    }

    @Test fun noCodesIsAnEmptyList() {
        assertEquals(emptyList<String>(), DtcParser.parse("43 00 00 00 00 00 00\r", 0x43, true))
        assertEquals(emptyList<String>(), DtcParser.parse("NO DATA\r", 0x43, true))
        assertEquals(emptyList<String>(), DtcParser.parse("", 0x43, true))
    }

    @Test fun olderBusesHaveNoCount() {
        val raw = "43 01 33 03 00 00 00\r"
        assertEquals(listOf("P0133", "P0300"), DtcParser.parse(raw, 0x43, hasCount = false))
    }

    @Test fun aMultiFrameAnswerIsJoinedAcrossItsNumberedLines() {
        val raw = "00A\r0: 43 04 01 33 03\r1: 00 02 34 04 56\r"
        assertEquals(listOf("P0133", "P0300", "P0234", "P0456"), DtcParser.parse(raw, 0x43, hasCount = true))
    }

    @Test fun severalControlUnitsMergeAndRepeatsAreDroppedOnce() {
        val raw = "43 01 01 33 00 00 00\r43 02 01 33 07 01 00\r"
        assertEquals(listOf("P0133", "P0701"), DtcParser.parse(raw, 0x43, true))
    }

    @Test fun theServiceByteDecidesWhichAnswerIsRead() {
        val raw = "47 01 02 34 00 00 00\r"
        assertEquals(emptyList<String>(), DtcParser.parse(raw, 0x43, true))
        assertEquals(listOf("P0234"), DtcParser.parse(raw, 0x47, true))
    }

    @Test fun searchingLinesAndEchoAreIgnored() {
        val raw = "03\rSEARCHING...\r43 01 01 33 00 00 00\r"
        assertEquals(listOf("P0133"), DtcParser.parse(raw, 0x43, true))
    }

    @Test fun codesKnowTheirSystemAndWhetherTheyAreGeneric() {
        assertEquals(DtcSystem.POWERTRAIN, Dtc("P0133", false).system)
        assertEquals(DtcSystem.NETWORK, Dtc("U0100", true).system)
        assertEquals(true, Dtc("P0133", false).generic)
        assertEquals(false, Dtc("P1A2F", false).generic)
    }
}
