package dev.autobridge.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParserTest {
    private fun value(raw: String, pid: Pid): Float {
        val reply = ObdParser.extract(raw, pid.request, pid.reply, pid.bytes)
        assertTrue("expected a value from '$raw' but got $reply", reply is Reply.Value)
        return pid.decode((reply as Reply.Value).data)
    }

    @Test fun rpmIsAQuarterOfTheRawWord() {
        assertEquals(1250f, value("41 0C 13 88 \r\r", ObdPids.RPM), 0.01f)
        assertEquals(1250f, value("410C1388\r", ObdPids.RPM), 0.01f)
    }

    @Test fun speedAndCoolant() {
        assertEquals(65f, value("41 0D 41", ObdPids.SPEED), 0f)
        assertEquals(85f, value("41 05 7D", ObdPids.COOLANT), 0f)
    }

    @Test fun echoAndSearchingLinesAreIgnored() {
        val raw = "010C\rSEARCHING...\r41 0C 0B B8\r"
        assertEquals(750f, value(raw, ObdPids.RPM), 0.01f)
    }

    @Test fun leadingJunkBeforeTheReplyIsSkipped() {
        assertEquals(750f, value("0:41 0C 0B B8", ObdPids.RPM), 0.01f)
    }

    @Test fun batteryFromTheStandardPid() {
        assertEquals(14.2f, value("41 42 37 78", ObdPids.BATTERY_STANDARD), 0.001f)
    }

    @Test fun fordT5AtfFormula() {
        // (A*256+B)*5/72-18 with 0x0510 = 1296 gives 72 degrees.
        val atf = ObdPids.atf(Transmission.T5).first()
        assertEquals(72f, value("62 16 74 05 10", atf), 0.01f)
    }

    @Test fun fordT6AtfFormula() {
        val atf = ObdPids.atf(Transmission.T6).first()
        // 0x05A0 = 1440 / 16 = 90
        assertEquals(90f, value("621E1C05A0", atf), 0.01f)
    }

    @Test fun atfIsAskedOfTheTransmissionFirstThenTheEngine() {
        assertEquals(listOf("7E1", "7E0"), ObdPids.atf(Transmission.T5).map { it.header })
    }

    @Test fun noDataAndNegativeResponses() {
        assertTrue(ObdParser.extract("NO DATA\r", "010C", "410C", 2) is Reply.NoData)
        assertTrue(ObdParser.extract("7F 22 31", "221674", "621674", 2) is Reply.NoData)
        assertTrue(ObdParser.extract("?", "010C", "410C", 2) is Reply.NoData)
    }

    @Test fun adapterErrorsAreFailures() {
        assertTrue(ObdParser.extract("UNABLE TO CONNECT\r", "010C", "410C", 2) is Reply.Failure)
        assertTrue(ObdParser.extract("CAN ERROR", "010C", "410C", 2) is Reply.Failure)
    }

    @Test fun aShortReplyIsNotAValue() {
        assertTrue(ObdParser.extract("41 0C 13", "010C", "410C", 2) !is Reply.Value)
    }

    @Test fun rawBytesAreKept() {
        val reply = ObdParser.extract("41 0C 13 88", "010C", "410C", 2) as Reply.Value
        assertArrayEquals(intArrayOf(0x13, 0x88), reply.data)
    }
}
