package dev.autobridge.desktop.keyboard

import dev.autobridge.desktop.display.X11Connection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TextCursorTest {
    private val opaque = 0xff000000.toInt()

    /** A [size]² image with the given rectangles drawn opaque. */
    private fun image(size: Int, vararg rects: IntArray): IntArray {
        val px = IntArray(size * size)
        for (r in rects) for (y in r[1]..r[3]) for (x in r[0]..r[2]) px[y * size + x] = opaque
        return px
    }

    @Test
    fun themedIBeamInASquareImageIsText() {
        // Stem x 11..12, y 3..20, serifs at top and bottom: drawn part 9×18, hotspot in the middle.
        val px = image(24, intArrayOf(11, 3, 12, 20), intArrayOf(8, 3, 15, 4), intArrayOf(8, 19, 15, 20))
        assertTrue(TextCursor.isText(24, 24, 12, 12, px))
    }

    @Test
    fun coreFontXtermGlyphIsText() {
        val px = IntArray(9 * 16) { opaque }
        assertTrue(TextCursor.isText(9, 16, 4, 8, px))
    }

    @Test
    fun arrowWithHotspotAtTheTipIsNotText() {
        val px = image(24, intArrayOf(1, 1, 4, 18), intArrayOf(5, 6, 11, 14))
        assertFalse(TextCursor.isText(24, 24, 1, 1, px))
    }

    @Test
    fun handWithHotspotAtTheFingertipIsNotText() {
        val px = image(24, intArrayOf(9, 1, 12, 21))
        assertFalse(TextCursor.isText(24, 24, 10, 1, px))
    }

    @Test
    fun squareCrosshairIsNotText() {
        val px = image(24, intArrayOf(11, 2, 12, 21), intArrayOf(2, 11, 21, 12))
        assertFalse(TextCursor.isText(24, 24, 12, 12, px))
    }

    @Test
    fun emptyOrTruncatedImageIsNotText() {
        assertFalse(TextCursor.isText(24, 24, 12, 12, IntArray(24 * 24)))
        assertFalse(TextCursor.isText(24, 24, 12, 12, IntArray(10) { opaque }))
        assertFalse(TextCursor.isText(0, 0, 0, 0, IntArray(0)))
    }

    @Test
    fun parsesGetCursorImageReply() {
        val pixels = intArrayOf(1, 2, 3, 4, 5, 6)
        val reply = ByteBuffer.allocate(32 + pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1); put(0); putShort(7); putInt(pixels.size)
            putShort(100); putShort(200) // x, y
            putShort(2); putShort(3) // width, height
            putShort(1); putShort(2) // xhot, yhot
            putInt(42) // cursor serial
            position(32)
            pixels.forEach { putInt(it) }
        }.array()
        val image = X11Connection.parseCursorImage(reply)
        assertEquals(2, image.width)
        assertEquals(3, image.height)
        assertEquals(1, image.xhot)
        assertEquals(2, image.yhot)
        assertArrayEquals(pixels, image.pixels)
    }
}
