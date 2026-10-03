package dev.autobridge.remotestream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

/**
 * RFC 6455 framing.
 *
 * Written by hand rather than taken from a library (see [WebSocketFrame] for why), which makes
 * these tests the thing standing between the receiver and a host that silently refuses to talk
 * to it. The three length encodings and the masking rule are where a hand-rolled implementation
 * goes wrong, so each gets a case.
 */
class WebSocketFrameTest {

    private val mask = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    /** Builds an unmasked server frame, which is what [WebSocketFrame.readFrame] expects. */
    private fun serverFrame(opcode: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
        val header = ArrayList<Byte>()
        header.add(((if (fin) 0x80 else 0x00) or opcode).toByte())
        when {
            payload.size < 126 -> header.add(payload.size.toByte())
            payload.size <= 0xFFFF -> {
                header.add(126)
                header.add((payload.size ushr 8).toByte())
                header.add(payload.size.toByte())
            }
            else -> {
                header.add(127)
                repeat(4) { header.add(0) }
                header.add((payload.size ushr 24).toByte())
                header.add((payload.size ushr 16).toByte())
                header.add((payload.size ushr 8).toByte())
                header.add(payload.size.toByte())
            }
        }
        return header.toByteArray() + payload
    }

    @Test
    fun `a short client frame is masked and flagged final`() {
        val encoded = WebSocketFrame.encode(WebSocketFrame.OP_TEXT, "hi".toByteArray(), mask)
        assertEquals(0x81.toByte(), encoded[0])           // FIN + text
        assertEquals((0x80 or 2).toByte(), encoded[1])    // MASK + length 2
        assertArrayEquals(mask, encoded.copyOfRange(2, 6))
        // Unmasking the payload must give the original back.
        val payload = encoded.copyOfRange(6, encoded.size)
        val unmasked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        assertEquals("hi", String(unmasked))
    }

    @Test
    fun `a medium payload uses the 16-bit length`() {
        val encoded = WebSocketFrame.encode(WebSocketFrame.OP_BINARY, ByteArray(300), mask)
        assertEquals((0x80 or 126).toByte(), encoded[1])
        assertEquals(1, encoded[2].toInt())    // 300 ushr 8
        assertEquals(44, encoded[3].toInt())   // 300 and 0xFF
    }

    @Test
    fun `a large payload uses the 64-bit length with a zero high word`() {
        val encoded = WebSocketFrame.encode(WebSocketFrame.OP_BINARY, ByteArray(70_000), mask)
        assertEquals((0x80 or 127).toByte(), encoded[1])
        // Bytes 2..5 are the high half of the 64-bit length and must be zero.
        (2..5).forEach { assertEquals(0, encoded[it].toInt()) }
        val length = (encoded[6].toInt() and 0xFF shl 24) or
            (encoded[7].toInt() and 0xFF shl 16) or
            (encoded[8].toInt() and 0xFF shl 8) or
            (encoded[9].toInt() and 0xFF)
        assertEquals(70_000, length)
    }

    @Test
    fun `a server text frame is read back`() {
        val input = ByteArrayInputStream(serverFrame(WebSocketFrame.OP_TEXT, "status".toByteArray()))
        val frame = WebSocketFrame.readFrame(input)
        assertEquals(WebSocketFrame.OP_TEXT, frame.opcode)
        assertTrue(frame.fin)
        assertEquals("status", String(frame.payload))
    }

    @Test
    fun `a binary frame with a 16-bit length is read back`() {
        val payload = ByteArray(500) { (it % 251).toByte() }
        val frame = WebSocketFrame.readFrame(ByteArrayInputStream(serverFrame(WebSocketFrame.OP_BINARY, payload)))
        assertEquals(WebSocketFrame.OP_BINARY, frame.opcode)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `a binary frame with a 64-bit length is read back`() {
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val frame = WebSocketFrame.readFrame(ByteArrayInputStream(serverFrame(WebSocketFrame.OP_BINARY, payload)))
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun `a non-final frame reports itself as such`() {
        val frame = WebSocketFrame.readFrame(
            ByteArrayInputStream(serverFrame(WebSocketFrame.OP_BINARY, byteArrayOf(1, 2), fin = false))
        )
        assertTrue(!frame.fin)
    }

    @Test(expected = EOFException::class)
    fun `a truncated frame ends the stream rather than hanging`() {
        // Two header bytes promising ten payload bytes, with none following: this is a dropped
        // connection, and it has to surface as EOF so the receiver reconnects.
        WebSocketFrame.readFrame(ByteArrayInputStream(byteArrayOf(0x82.toByte(), 0x0A)))
    }

    @Test(expected = IllegalStateException::class)
    fun `a masked server frame is refused`() {
        WebSocketFrame.readFrame(ByteArrayInputStream(byteArrayOf(0x81.toByte(), 0x82.toByte(), 1, 2, 3, 4, 9, 9)))
    }
}
