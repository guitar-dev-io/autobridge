package dev.autobridge.remotestream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading an Annex-B buffer well enough to know whether a decoder can be started from it.
 *
 * Getting this wrong is not a visible bug, it is a black screen: the decoder is configured from
 * the first access unit that carries an SPS, so a missed parameter set means nothing ever
 * decodes and a false positive means `configure` throws.
 */
class H264NalTest {

    private fun nal(type: Int, vararg payload: Byte) =
        byteArrayOf(0, 0, 0, 1, type.toByte()) + payload

    @Test
    fun `a typical keyframe access unit is recognised`() {
        // SPS | PPS | IDR concatenated, which is what an encoder emits at a keyframe.
        val unit = nal(7, 0x42) + nal(8, 0x01) + nal(5, 0x88.toByte())
        assertEquals(listOf(7, 8, 5), H264Nal.types(unit))
        assertTrue(H264Nal.containsParameterSet(unit))
        assertTrue(H264Nal.isKeyFrame(unit))
    }

    @Test
    fun `an access unit delimiter in front does not hide the parameter set`() {
        val unit = nal(9, 0x10) + nal(7, 0x42) + nal(5, 0x88.toByte())
        assertTrue(H264Nal.containsParameterSet(unit))
    }

    @Test
    fun `a plain inter frame carries no parameter set and is not a keyframe`() {
        val unit = nal(1, 0x9A.toByte())
        assertFalse(H264Nal.containsParameterSet(unit))
        assertFalse(H264Nal.isKeyFrame(unit))
        assertEquals(listOf(1), H264Nal.types(unit))
    }

    @Test
    fun `the three-byte start code is recognised too`() {
        val unit = byteArrayOf(0, 0, 1, 7, 0x42) + byteArrayOf(0, 0, 1, 5, 0x11)
        assertEquals(listOf(7, 5), H264Nal.types(unit))
    }

    @Test
    fun `an empty or junk buffer yields nothing rather than throwing`() {
        assertEquals(emptyList<Int>(), H264Nal.types(ByteArray(0)))
        assertEquals(emptyList<Int>(), H264Nal.types(byteArrayOf(9, 9, 9, 9, 9)))
        assertFalse(H264Nal.containsParameterSet(ByteArray(0)))
    }

    @Test
    fun `the forbidden and reference bits are masked off the type`() {
        // The NAL header is F(1) | NRI(2) | type(5); 0x67 is a reference SPS and must read as 7,
        // not as 0x67.
        assertEquals(listOf(7), H264Nal.types(byteArrayOf(0, 0, 0, 1, 0x67)))
        assertEquals(listOf(5), H264Nal.types(byteArrayOf(0, 0, 0, 1, 0x65)))
    }
}
