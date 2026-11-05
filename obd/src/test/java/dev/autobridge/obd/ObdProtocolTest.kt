package dev.autobridge.obd

import dev.autobridge.obd.dtc.DtcCode
import dev.autobridge.obd.dtc.DtcDatabase
import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.elm.ObdFailure
import dev.autobridge.obd.elm.ObdReply
import dev.autobridge.obd.obd2.DtcKind
import dev.autobridge.obd.obd2.Obd2Client
import dev.autobridge.obd.obd2.ObdResult
import dev.autobridge.obd.obd2.Pids
import org.junit.Assert.*
import org.junit.Test

class ObdProtocolTest {

    private fun client(extra: Map<String, String> = emptyMap()): Obd2Client {
        val session = ElmSession(FakeTransport.canCar(extra))
        session.connect()
        return Obd2Client(session)
    }

    @Test fun connectReadsAdapterAndProtocol() {
        val session = ElmSession(FakeTransport.canCar())
        val info = session.connect()
        assertEquals("ELM327 v1.5", info.identity)
        assertTrue(info.protocol.contains("CAN"))
        assertTrue(info.isCan)
        assertEquals("12.4V", info.voltage)
    }

    @Test fun parseFailures() {
        assertEquals(ObdFailure.NoData, (ElmSession.parse("NO DATA\r") as ObdReply.Failed).failure)
        assertEquals(ObdFailure.NotConnected, (ElmSession.parse("SEARCHING...\rUNABLE TO CONNECT\r") as ObdReply.Failed).failure)
        assertEquals(ObdFailure.Unsupported, (ElmSession.parse("?\r") as ObdReply.Failed).failure)
        assertEquals(ObdFailure.BusError, (ElmSession.parse("CAN ERROR\r") as ObdReply.Failed).failure)
    }

    @Test fun parseMultiFrameDropsLengthLine() {
        val r = ElmSession.parse("014\r0:490201574D4A\r1:3333334D4D5A\r2:5A5A5A5A5A5A\r") as ObdReply.Data
        assertEquals(1, r.payloads.size)
        assertEquals(0x49.toByte(), r.first[0])
        assertEquals(18, r.first.size)
    }

    @Test fun supportedPidsFollowsBitmapChain() {
        // 0100 -> PIDs 01,03,04,05,... ; last bit set so 0120 is asked next; that one ends the chain.
        val c = client(mapOf("0120" to "412080000001\r", "0140" to "NO DATA\r"))
        val pids = c.supportedPids()
        assertTrue(0x01 in pids && 0x04 in pids && 0x0D in pids)
        assertTrue(0x21 in pids)
    }

    @Test fun livePidFormulas() {
        val c = client(mapOf("010C" to "410C1AF8\r", "010D" to "410D3C\r", "0105" to "41057B\r", "0142" to "41423A98\r"))
        assertEquals(1726.0, (c.readPid(Pids.RPM) as ObdResult.Ok).value.value, 0.001)
        assertEquals(60.0, (c.readPid(Pids.SPEED) as ObdResult.Ok).value.value, 0.001)
        assertEquals(83.0, (c.readPid(Pids.COOLANT) as ObdResult.Ok).value.value, 0.001)
        assertEquals("15.00 V", (c.readPid(0x42) as ObdResult.Ok).value.text)
    }

    @Test fun readPidReportsNoData() {
        val c = client(mapOf("010C" to "NO DATA\r"))
        assertEquals(ObdResult.Err(ObdFailure.NoData), c.readPid(Pids.RPM))
    }

    @Test fun dtcDecoding() {
        assertEquals("P0133", DtcCode.decode(0x01, 0x33).code)
        assertEquals("C0035", DtcCode.decode(0x40, 0x35).code)
        assertEquals("B1234", DtcCode.decode(0x92, 0x34).code)
        assertEquals("U0100", DtcCode.decode(0xC1, 0x00).code)
        assertTrue(DtcCode.decode(0x01, 0x33).isGeneric)
        assertFalse(DtcCode.decode(0x11, 0x33).isGeneric)
    }

    @Test fun canStoredCodesSkipCountByte() {
        val c = client(mapOf("03" to "430201330171\r"))
        val codes = (c.readDtcs(DtcKind.STORED) as ObdResult.Ok).value.map { it.code }
        assertEquals(listOf("P0133", "P0171"), codes)
    }

    @Test fun canWithNoCodesAndNoDataAreBothEmpty() {
        assertTrue((client(mapOf("03" to "4300\r")).readDtcs(DtcKind.STORED) as ObdResult.Ok).value.isEmpty())
        assertTrue((client(mapOf("07" to "NO DATA\r")).readDtcs(DtcKind.PENDING) as ObdResult.Ok).value.isEmpty())
    }

    @Test fun clearNeedsAcknowledgement() {
        assertTrue(client(mapOf("04" to "44\r")).clearDtcs() is ObdResult.Ok)
        assertTrue(client(mapOf("04" to "NO DATA\r")).clearDtcs() is ObdResult.Err)
    }

    @Test fun readinessAndMil() {
        // A=0x82: MIL on, 2 codes. B=0x07 spark, all continuous supported+complete. C/D: catalyst+evap supported, evap incomplete.
        val r = (client(mapOf("0101" to "410182070505\r")).readiness() as ObdResult.Ok).value
        assertTrue(r.milOn)
        assertEquals(2, r.codeCount)
        assertTrue(r.monitors.first { it.name == "Misfire" }.let { it.supported && it.complete })
    }

    @Test fun readinessDecodesIncomplete() {
        val r = Obd2Client.decodeReadiness(byteArrayOf(0x00, 0x07, 0x05, 0x04))!!
        val catalyst = r.monitors.first { it.name == "Catalyst" }
        val evap = r.monitors.first { it.name == "Evaporative" }
        assertTrue(catalyst.supported && catalyst.complete)
        assertTrue(evap.supported && !evap.complete)
        assertFalse(r.monitors.first { it.name == "Secondary air" }.supported)
    }

    @Test fun vinFromReassembledCanMessage() {
        val c = client(mapOf("0902" to "014\r0:490201574D4A\r1:3333334D4D5A\r2:5A5A5A5A5A5A\r"))
        // 17 chars after the 3 header bytes: "WMJ333MMZZZZZZ" is only 14 here, so no VIN.
        assertTrue(c.vin() is ObdResult.Err)
        val full = client(mapOf("0902" to "014\r0:4902013141\r1:47434D3832363333\r2:41313132333435\r"))
        assertEquals("1AGCM82633A112345", (full.vin() as ObdResult.Ok).value)
    }

    @Test fun descriptionsNeverBlank() {
        assertTrue(DtcDatabase.describe(DtcCode.decode(0x01, 0x33), thai = false).contains("O2"))
        assertTrue(DtcDatabase.describe(DtcCode.decode(0x01, 0x33), thai = true).isNotBlank())
        assertTrue(DtcDatabase.describe(DtcCode.decode(0x1F, 0xFF), thai = false).contains("manufacturer"))
    }
}

class ObdLogTest {
    private fun v(id: Int, value: Double) = dev.autobridge.obd.obd2.PidValue(dev.autobridge.obd.obd2.Pids.find(id)!!, value)

    @Test fun csvHasOneColumnPerPidAndLeavesGapsEmpty() {
        val log = dev.autobridge.obd.log.ObdLog()
        log.add(100, mapOf(0x0C to v(0x0C, 1726.0), 0x0D to v(0x0D, 60.0)))
        log.add(200, mapOf(0x0D to v(0x0D, 61.5)))
        val lines = log.csv().trim().lines()
        assertEquals("time_ms,Engine speed (rpm),Vehicle speed (km/h)", lines[0])
        assertEquals("100,1726,60", lines[1])
        assertEquals("200,,61.500", lines[2])
    }

    @Test fun oldestRowsDropAtTheCap() {
        val log = dev.autobridge.obd.log.ObdLog(maxRows = 2)
        for (i in 1..4) log.add(i.toLong(), mapOf(0x0D to v(0x0D, i.toDouble())))
        assertEquals(2, log.size)
        assertTrue(log.csv().contains("\n3,3\n4,4"))
    }
}

class UdsScannerTest {
    @Test fun findsOnlyModulesThatAnswerAndAlwaysResetsAddressing() {
        val replies = HashMap<String, String>()
        for (i in 0..7) {
            replies["ATSH7E$i"] = "OK\r"
            replies["ATCRA7E%X".format(i + 8)] = "OK\r"
        }
        for (did in listOf("F190", "F187", "F189", "F191", "F18C")) {
            replies["22$did"] = "NO DATA\r"
        }
        replies["ATSH7DF"] = "OK\r"; replies["ATAR"] = "OK\r"
        val transport = object : dev.autobridge.obd.transport.ObdTransport {
            private val base = FakeTransport.canCar(replies)
            private var addressed = ""
            override fun open() = base.open()
            override fun write(command: String) { if (command.startsWith("ATSH")) addressed = command.removePrefix("ATSH"); base.write(command) }
            override fun readUntilPrompt(timeoutMs: Long): String {
                val last = base.sent.last()
                // Only the module at 7E0 answers, with a VIN and a part number.
                if (addressed == "7E0" && last == "22F190") return "62F190315A4741\r"
                if (addressed == "7E0" && last == "22F187") return "62F1873334313030\r"
                return base.readUntilPrompt(timeoutMs)
            }
            override fun close() = base.close()
            val sent get() = base.sent
        }
        val session = ElmSession(transport)
        session.connect()
        val found = dev.autobridge.obd.uds.UdsScanner(session).scan()
        assertEquals(1, found.size)
        assertEquals("7E0", found[0].requestId)
        assertEquals("1ZGA", found[0].values["VIN"])
        assertEquals("34100", found[0].values["Part number"])
        assertEquals("ATAR", transport.sent.last())
    }

    @Test fun renderFallsBackToHex() {
        assertEquals("01 02 AB", dev.autobridge.obd.uds.UdsScanner.render(byteArrayOf(1, 2, 0xAB.toByte())))
        assertEquals("ABC", dev.autobridge.obd.uds.UdsScanner.render("ABC\u0000\u0000".toByteArray()))
    }
}

class BleLayoutTest {
    private fun c(u: String, n: Boolean, w: Boolean, wn: Boolean = false) = dev.autobridge.obd.transport.BleChar(u, n, w, wn)
    private fun s(u: String, vararg ch: dev.autobridge.obd.transport.BleChar) = dev.autobridge.obd.transport.BleService(u, ch.toList())
    private val generic = s("00001800-0000-1000-8000-00805f9b34fb", c("00002a00-0000-1000-8000-00805f9b34fb", false, true))

    @Test fun fff0SplitsNotifyAndWrite() {
        val p = dev.autobridge.obd.transport.BleLayout.pick(listOf(generic, s("0000fff0-0000-1000-8000-00805f9b34fb",
            c("0000fff1-0000-1000-8000-00805f9b34fb", true, false), c("0000fff2-0000-1000-8000-00805f9b34fb", false, true, true))))!!
        assertEquals("0000fff1-0000-1000-8000-00805f9b34fb", p.notify)
        assertEquals("0000fff2-0000-1000-8000-00805f9b34fb", p.write)
    }

    @Test fun ffe1IsBothDirections() {
        val p = dev.autobridge.obd.transport.BleLayout.pick(listOf(s("0000ffe0-0000-1000-8000-00805f9b34fb",
            c("0000ffe1-0000-1000-8000-00805f9b34fb", true, true, true))))!!
        assertEquals(p.notify, p.write)
    }

    @Test fun unknownVendorServiceStillWorksAndSystemOnlyDoesNot() {
        assertNotNull(dev.autobridge.obd.transport.BleLayout.pick(listOf(s("12345678-0000-1000-8000-00805f9b34fb",
            c("a", true, false), c("b", false, true)))))
        assertNull(dev.autobridge.obd.transport.BleLayout.pick(listOf(generic)))
    }
}
