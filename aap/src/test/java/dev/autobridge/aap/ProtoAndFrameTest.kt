package dev.autobridge.aap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class ProtoAndFrameTest {
    @Test
    fun varintsRoundTripIncludingNegativeAndLarge() {
        val bytes = ProtoWriter().uint(1, 300).int(2, -1).int(3, Long.MAX_VALUE).string(4, "ไทย").bool(5, true).toByteArray()
        val f = ProtoReader.fields(bytes)
        assertEquals(300, f.first(1)!!.int)
        assertEquals(-1L, f.first(2)!!.varint)
        assertEquals(Long.MAX_VALUE, f.first(3)!!.varint)
        assertEquals("ไทย", f.first(4)!!.string)
        assertEquals(true, f.first(5)!!.bool)
    }

    @Test
    fun knownEncoding() {
        // Field 1 = 150 is the textbook example: 08 96 01.
        assertArrayEquals(byteArrayOf(0x08, 0x96.toByte(), 0x01), ProtoWriter().uint(1, 150).toByteArray())
    }

    @Test
    fun packedAndUnpackedRepeatedReadAlike() {
        val unpacked = ProtoWriter().uint(1, 3).uint(1, 4).uint(1, 300).toByteArray()
        val packed = ProtoWriter().bytes(1, byteArrayOf(3, 4, 0xAC.toByte(), 0x02)).toByteArray()
        assertEquals(listOf(3L, 4L, 300L), ProtoReader.fields(unpacked).varints(1))
        assertEquals(listOf(3L, 4L, 300L), ProtoReader.fields(packed).varints(1))
    }

    @Test
    fun firstFrameCarriesTotalSize() {
        val encoded = FrameIo.encode(3, AapFrame.FLAG_FIRST or AapFrame.FLAG_ENCRYPTED, byteArrayOf(1, 2, 3), 70000)
        assertArrayEquals(byteArrayOf(3, 9, 0, 3, 0, 1, 0x11, 0x70, 1, 2, 3), encoded)
        val frame = FrameIo.read(ByteArrayInputStream(encoded))
        assertEquals(70000, frame.totalSize)
        assertEquals(AapFrame.FLAG_FIRST, frame.position)
        assertEquals(true, frame.encrypted)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.payload)
    }

    @Test
    fun bulkFrameHasShortSize() {
        val encoded = FrameIo.encode(0, AapFrame.FLAG_BULK, byteArrayOf(0, 1, 0, 1), 4)
        assertArrayEquals(byteArrayOf(0, 3, 0, 4, 0, 1, 0, 1), encoded)
    }

    @Test
    fun videoConfigSizes() {
        val c = AapVideoConfig(0, resolution = 2, fps = 2, marginWidth = 0, marginHeight = 80, dpi = 160)
        assertEquals(1280, c.width)
        assertEquals(640, c.visibleHeight)
        assertEquals(60, c.framesPerSecond)
    }

    @Test
    fun inputEventWithTwoPointers() {
        val body = ProtoWriter().uint(1, 5L).message(
            3,
            ProtoWriter()
                .message(1, ProtoWriter().uint(1, 10).uint(2, 20).uint(3, 0))
                .message(1, ProtoWriter().uint(1, 30).uint(2, 40).uint(3, 1))
                .uint(2, 1).uint(3, AapTouch.POINTER_DOWN)
        ).toByteArray()
        val e = AapMessages.parseInputEvent(body)
        assertEquals(AapTouch.POINTER_DOWN, e.touch!!.action)
        assertEquals(1, e.touch!!.actionIndex)
        assertEquals(listOf(AapTouch.Pointer(0, 10, 20), AapTouch.Pointer(1, 30, 40)), e.touch!!.pointers)
    }
}
