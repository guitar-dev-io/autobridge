package dev.autobridge.duoscreen.system

import dev.autobridge.duoscreen.system.DuoScreenRootProtocol.Command
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The root helper's line protocol: what the app writes is what the helper reads back. */
class DuoScreenRootProtocolTest {
    private fun roundTrip(command: Command): Command? =
        DuoScreenRootProtocol.decode(DuoScreenRootProtocol.encode(command))

    @Test
    fun launchRoundTripsWithAndWithoutComponent() {
        val explicit = Command.Launch(7, "com.spotify.music", "com.spotify.music/.MainActivity", false)
        assertEquals(explicit, roundTrip(explicit))
        val implicit = Command.Launch(12, "dev.autobridge", null, true)
        assertEquals(implicit, roundTrip(implicit))
    }

    @Test
    fun queriesRoundTrip() {
        assertEquals(Command.Ping, roundTrip(Command.Ping))
        assertEquals(Command.Tasks(3), roundTrip(Command.Tasks(3)))
        assertEquals(Command.Ime(4), roundTrip(Command.Ime(4)))
    }

    @Test
    fun eventBytesSurviveOnOneLine() {
        val bytes = ByteArray(300) { it.toByte() }
        val line = DuoScreenRootProtocol.encode(Command.Motion(5, bytes))
        assertTrue("one request is one line", '\n' !in line)
        val motion = DuoScreenRootProtocol.decode(line) as Command.Motion
        assertEquals(5, motion.displayId)
        assertArrayEquals(bytes, motion.parcel)
        val key = roundTrip(Command.Key(9, bytes)) as Command.Key
        assertEquals(9, key.displayId)
        assertArrayEquals(bytes, key.parcel)
    }

    @Test
    fun malformedRequestsDecodeToNull() {
        assertNull(DuoScreenRootProtocol.decode(""))
        assertNull(DuoScreenRootProtocol.decode("REBOOT"))
        assertNull(DuoScreenRootProtocol.decode("TASKS x"))
        assertNull(DuoScreenRootProtocol.decode("LAUNCH 1 0"))
        assertNull(DuoScreenRootProtocol.decode("MOTION 1 not*base64"))
    }

    @Test
    fun repliesAreToldApartFromOtherOutput() {
        assertEquals("1", DuoScreenRootProtocol.replyBody(DuoScreenRootProtocol.reply(true)))
        assertEquals("0", DuoScreenRootProtocol.replyBody(DuoScreenRootProtocol.reply(false)))
        assertEquals("?", DuoScreenRootProtocol.replyBody(DuoScreenRootProtocol.reply(null)))
        assertNull(DuoScreenRootProtocol.replyBody("WARNING: linker: something"))
        assertEquals(true, DuoScreenRootProtocol.parseResult("1"))
        assertEquals(false, DuoScreenRootProtocol.parseResult("0"))
        assertNull(DuoScreenRootProtocol.parseResult("?"))
        assertNull(DuoScreenRootProtocol.parseResult(null))
    }
}
