package dev.autobridge.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class AapWirelessTest {
    @Test
    fun startUpExchange() {
        val car = ByteArrayOutputStream()
        AapWireless.write(car, AapWireless.WIFI_START_REQUEST, ProtoWriter().string(1, "192.168.43.1").int(2, 5288).toByteArray())
        AapWireless.write(
            car, AapWireless.WIFI_INFO_RESPONSE,
            ProtoWriter().string(1, "CarAP").string(2, "secret12").string(3, "aa:bb:cc:dd:ee:ff")
                .int(4, AapWireless.SECURITY_WPA2_PERSONAL).int(5, 0).toByteArray()
        )
        val phoneOut = ByteArrayOutputStream()
        val fake = object : AapTransport {
            override val input: InputStream = ByteArrayInputStream(ByteArray(0))
            override val output: OutputStream = ByteArrayOutputStream()
            override val description = "tcp"
            override fun close() = Unit
        }
        var seen: Pair<AapWireless.Endpoint, AapWireless.WifiInfo>? = null
        val result = AapWireless.startUp(ByteArrayInputStream(car.toByteArray()), phoneOut, {}) { endpoint, info ->
            seen = endpoint to info
            fake
        }
        assertSame(fake, result)
        assertEquals(AapWireless.Endpoint("192.168.43.1", 5288), seen!!.first)
        assertEquals("CarAP", seen!!.second.ssid)
        assertEquals("secret12", seen!!.second.key)

        val replies = ByteArrayInputStream(phoneOut.toByteArray())
        assertEquals(AapWireless.WIFI_INFO_REQUEST, AapWireless.read(replies).id)
        val start = AapWireless.read(replies)
        assertEquals(AapWireless.WIFI_START_RESPONSE, start.id)
        assertEquals(5288, ProtoReader.fields(start.body).first(2)?.int)
        assertEquals(AapWireless.WIFI_CONNECT_STATUS, AapWireless.read(replies).id)
    }

    private fun fakeTransport() = object : AapTransport {
        override val input: InputStream = ByteArrayInputStream(ByteArray(0))
        override val output: OutputStream = ByteArrayOutputStream()
        override val description = "tcp"
        override fun close() = Unit
    }

    /** Version first (as the Ford did), with a ping in between, then the usual start request. */
    @Test
    fun versionRequestIsAnsweredBeforeStart() {
        val car = ByteArrayOutputStream()
        val headUnitInfo = ProtoWriter().string(1, "Ford").string(2, "Ranger").string(3, "2024")
        AapWireless.write(car, AapWireless.WIFI_VERSION_REQUEST, ProtoWriter().uint(1, 1).uint(2, 7).message(4, headUnitInfo).toByteArray())
        AapWireless.write(car, AapWireless.WIFI_PING_REQUEST, ProtoWriter().int(1, 42L).toByteArray())
        AapWireless.write(car, AapWireless.WIFI_START_REQUEST, ProtoWriter().string(1, "10.0.0.1").int(2, 5277).toByteArray())
        AapWireless.write(car, AapWireless.WIFI_INFO_RESPONSE, ProtoWriter().string(1, "SYNC").string(2, "pw123456").string(3, "").int(4, 8).toByteArray())
        val phoneOut = ByteArrayOutputStream()
        val log = StringBuilder()
        var seen: AapWireless.Endpoint? = null
        AapWireless.startUp(ByteArrayInputStream(car.toByteArray()), phoneOut, { log.append(it).append('\n') }, serial = "S1", deviceId = "D1") { e, _ ->
            seen = e
            fakeTransport()
        }
        assertEquals(AapWireless.Endpoint("10.0.0.1", 5277), seen)
        assert(log.contains("Ford Ranger 2024")) { log.toString() }

        val replies = ByteArrayInputStream(phoneOut.toByteArray())
        val version = AapWireless.read(replies)
        assertEquals(AapWireless.WIFI_VERSION_RESPONSE, version.id)
        val vf = ProtoReader.fields(version.body)
        assertEquals(1, vf.first(1)?.int)
        assertEquals(7, vf.first(2)?.int)
        assertEquals("S1", vf.first(3)?.string)
        assertEquals(0, vf.first(4)?.int)
        assertEquals("D1", ProtoReader.fields(vf.first(6)!!.bytes!!).first(1)?.string)
        val pong = AapWireless.read(replies)
        assertEquals(AapWireless.WIFI_PING_RESPONSE, pong.id)
        assertEquals(42L, ProtoReader.fields(pong.body).first(1)?.varint)
        assertEquals(AapWireless.WIFI_INFO_REQUEST, AapWireless.read(replies).id)
        assertEquals(AapWireless.WIFI_START_RESPONSE, AapWireless.read(replies).id)
    }

    /** A revision that puts the address in the version request and sends no WifiStartRequest. */
    @Test
    fun endpointInsideVersionRequest() {
        val car = ByteArrayOutputStream()
        AapWireless.write(
            car, AapWireless.WIFI_VERSION_REQUEST,
            ProtoWriter().uint(1, 1).uint(2, 4).message(5, ProtoWriter().string(1, "192.168.1.1").int(2, 30515)).toByteArray(),
        )
        AapWireless.write(car, AapWireless.WIFI_INFO_RESPONSE, ProtoWriter().string(1, "CarAP").string(2, "k").string(3, "").int(4, 8).toByteArray())
        val phoneOut = ByteArrayOutputStream()
        var seen: AapWireless.Endpoint? = null
        AapWireless.startUp(ByteArrayInputStream(car.toByteArray()), phoneOut, {}) { e, _ -> seen = e; fakeTransport() }
        assertEquals(AapWireless.Endpoint("192.168.1.1", 30515), seen)
        val replies = ByteArrayInputStream(phoneOut.toByteArray())
        assertEquals(AapWireless.WIFI_VERSION_RESPONSE, AapWireless.read(replies).id)
        assertEquals(AapWireless.WIFI_INFO_REQUEST, AapWireless.read(replies).id)
    }
}
