package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarKeyboardGesturesTest {
    private val tray = Box(0f, 400f, 800f, 600f)
    private companion object {
        const val SLOP = 40f
    }

    @Test
    fun `double tap zoom in inside the tray is a double tap`() {
        assertTrue(CarKeyboardGestures.isKeyboardDoubleTap(100f, 500f, 2.0f, tray))
    }

    @Test
    fun `double tap zoom out inside the tray is a double tap`() {
        assertTrue(CarKeyboardGestures.isKeyboardDoubleTap(100f, 500f, 0.5f, tray))
    }

    @Test
    fun `pinch step inside the tray is not a double tap`() {
        assertFalse(CarKeyboardGestures.isKeyboardDoubleTap(100f, 500f, 1.04f, tray))
    }

    @Test
    fun `double tap outside the tray is not a keyboard double tap`() {
        assertFalse(CarKeyboardGestures.isKeyboardDoubleTap(100f, 100f, 2.0f, tray))
    }

    @Test
    fun `missing focus is not a double tap`() {
        assertFalse(CarKeyboardGestures.isKeyboardDoubleTap(-1f, -1f, 2.0f, tray))
    }

    @Test
    fun `non finite factor is not a double tap`() {
        assertFalse(CarKeyboardGestures.isKeyboardDoubleTap(100f, 500f, Float.NaN, tray))
    }
    @Test
    fun `double tap on the tray bottom edge is a double tap`() {
        assertTrue(CarKeyboardGestures.isKeyboardDoubleTap(100f, 600f, 2.0f, tray))
    }
    @Test
    fun `tray centre is in the keyboard area`() {
        assertTrue(CarKeyboardGestures.inKeyboardArea(400f, 500f, tray))
    }
    @Test
    fun `tray top edge is in the keyboard area`() {
        assertTrue(CarKeyboardGestures.inKeyboardArea(400f, 400f, tray))
    }
    @Test
    fun `just above the tray is not in the keyboard area`() {
        assertFalse(CarKeyboardGestures.inKeyboardArea(400f, 399.5f, tray))
    }
    @Test
    fun `tray right and bottom edges are in the keyboard area`() {
        assertTrue(CarKeyboardGestures.inKeyboardArea(800f, 500f, tray))
        assertTrue(CarKeyboardGestures.inKeyboardArea(400f, 600f, tray))
    }
    @Test
    fun `margin below and beside the tray is in the keyboard area`() {
        assertTrue(CarKeyboardGestures.inKeyboardArea(400f, 640f, tray))
        assertTrue(CarKeyboardGestures.inKeyboardArea(820f, 500f, tray))
    }
    @Test
    fun `far above the tray is not in the keyboard area`() {
        assertFalse(CarKeyboardGestures.inKeyboardArea(100f, 100f, tray))
    }
    @Test
    fun `closed keyboard has no keyboard area`() {
        assertFalse(CarKeyboardGestures.inKeyboardArea(400f, 500f, null))
    }
    @Test
    fun `lone host double tap types two`() {
        val filter = CarKeyboardTapFilter()
        assertEquals(2, filter.onDoubleTap(1_000L, 100f, 500f, SLOP))
    }
    @Test
    fun `click then double tap on the same key types two in total`() {
        val filter = CarKeyboardTapFilter()
        val total = filter.onClick(1_000L, 100f, 500f, SLOP) + filter.onDoubleTap(1_250L, 104f, 498f, SLOP)
        assertEquals(2, total)
    }
    @Test
    fun `double tap then echoed click on the same key types two in total`() {
        val filter = CarKeyboardTapFilter()
        val total = filter.onDoubleTap(1_000L, 100f, 500f, SLOP) + filter.onClick(1_060L, 101f, 500f, SLOP)
        assertEquals(2, total)
    }
    @Test
    fun `click, double tap and echoed click never type more than two`() {
        val filter = CarKeyboardTapFilter()
        val total = filter.onClick(1_000L, 100f, 500f, SLOP) +
            filter.onDoubleTap(1_200L, 100f, 500f, SLOP) +
            filter.onClick(1_260L, 100f, 500f, SLOP)
        assertEquals(2, total)
    }
    @Test
    fun `double tap on another key after a click types two`() {
        val filter = CarKeyboardTapFilter()
        assertEquals(1, filter.onClick(1_000L, 100f, 500f, SLOP))
        assertEquals(2, filter.onDoubleTap(1_200L, 300f, 500f, SLOP))
    }
    @Test
    fun `double tap long after a click types two`() {
        val filter = CarKeyboardTapFilter()
        filter.onClick(1_000L, 100f, 500f, SLOP)
        assertEquals(2, filter.onDoubleTap(1_000L + CarKeyboardTapFilter.PAIR_MS + 1, 100f, 500f, SLOP))
    }
    @Test
    fun `a later tap after a double tap still types`() {
        val filter = CarKeyboardTapFilter()
        filter.onDoubleTap(1_000L, 100f, 500f, SLOP)
        assertEquals(1, filter.onClick(1_000L + CarKeyboardTapFilter.ECHO_MS + 1, 100f, 500f, SLOP))
        assertEquals(1, filter.onClick(1_400L, 100f, 500f, SLOP))
    }
    @Test
    fun `a click is paired with only one double tap`() {
        val filter = CarKeyboardTapFilter()
        filter.onClick(1_000L, 100f, 500f, SLOP)
        assertEquals(1, filter.onDoubleTap(1_200L, 100f, 500f, SLOP))
        assertEquals(2, filter.onDoubleTap(1_390L, 100f, 500f, SLOP))
    }
    @Test
    fun `plain clicks always type`() {
        val filter = CarKeyboardTapFilter()
        assertEquals(1, filter.onClick(1_000L, 100f, 500f, SLOP))
        assertEquals(1, filter.onClick(1_050L, 100f, 500f, SLOP))
    }
    @Test
    fun `non finite or missing points are not in the keyboard area`() {
        assertFalse(CarKeyboardGestures.inKeyboardArea(Float.NaN, 500f, tray))
        assertFalse(CarKeyboardGestures.inKeyboardArea(400f, Float.NaN, tray))
        assertFalse(CarKeyboardGestures.inKeyboardArea(-1f, -1f, tray))
    }
}
