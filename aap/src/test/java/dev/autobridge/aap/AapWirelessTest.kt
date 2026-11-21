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
}
