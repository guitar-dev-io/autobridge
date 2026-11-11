package dev.autobridge.obd

import dev.autobridge.obd.elm.ElmSession
import dev.autobridge.obd.elm.ObdFailure
import dev.autobridge.obd.elm.ObdReply
import org.junit.Assert.*
import org.junit.Test

class ObdProtocolTest {

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

class UdsInspectorTest {
    private fun inspector(extra: Map<String, String>): dev.autobridge.obd.uds.UdsInspector {
        val s = ElmSession(FakeTransport.canCar(extra)); s.connect()
        return dev.autobridge.obd.uds.UdsInspector(s)
    }

    @Test fun didAnswersAreClassified() {
        val i = inspector(mapOf("22DE01" to "62DE01AABB\r", "22DE02" to "7F2233\r", "22DE03" to "NO DATA\r"))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), (i.readDid(0xDE01) as dev.autobridge.obd.uds.UdsAnswer.Positive).data)
        assertEquals(0x33, (i.readDid(0xDE02) as dev.autobridge.obd.uds.UdsAnswer.Negative).code)
        assertTrue(i.readDid(0xDE03) is dev.autobridge.obd.uds.UdsAnswer.Silent)
    }

    @Test fun probeSeesLockedModuleAndReturnsToDefaultSession() {
        val t = FakeTransport.canCar(mapOf("1003" to "5003\r", "2701" to "67011234\r", "1001" to "5001\r"))
        val s = ElmSession(t); s.connect()
        val p = dev.autobridge.obd.uds.UdsInspector(s).probe()
        assertEquals(dev.autobridge.obd.uds.Access.ACCEPTED, p.extendedSession)
        assertEquals(dev.autobridge.obd.uds.Access.ACCEPTED, p.security)
        assertEquals(2, p.seedLength)
        assertFalse(p.alreadyUnlocked)
        assertEquals("1001", t.sent.last())
    }

    @Test fun probeReportsRefusalsAndZeroSeed() {
        val refused = inspector(mapOf("1003" to "7F1022\r", "2701" to "7F2712\r", "1001" to "NO DATA\r")).probe()
        assertEquals(dev.autobridge.obd.uds.Access.REFUSED, refused.extendedSession)
        assertEquals("conditions not correct", refused.extendedRefusal)
        assertEquals("sub-function not supported", refused.securityRefusal)
        assertTrue(inspector(mapOf("1003" to "5003\r", "2701" to "67010000\r", "1001" to "5001\r")).probe().alreadyUnlocked)
    }

    @Test fun backupKeepsExistingAndProtectedDidsOnly() {
        val i = inspector(mapOf("22F180" to "62F18001\r", "22F181" to "7F2231\r", "22F182" to "7F2233\r", "22F183" to "NO DATA\r"))
        val dump = i.backup(0xF180..0xF183)
        assertEquals(listOf(0xF180, 0xF182), dump.map { it.did })
        assertEquals("security access denied", dump[1].refusal)
        val csv = dev.autobridge.obd.uds.UdsInspector.csv("VIN1", dev.autobridge.obd.uds.EcuInfo("726", "72E", emptyMap(), "BCM"), dump)
        assertTrue(csv.contains("F180,01,"))
        assertTrue(csv.contains("F182,,security access denied"))
    }
}
