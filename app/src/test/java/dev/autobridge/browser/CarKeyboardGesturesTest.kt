package dev.autobridge.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarKeyboardGesturesTest {
    private val tray = Box(0f, 400f, 800f, 600f)

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
    fun `non finite or missing points are not in the keyboard area`() {
        assertFalse(CarKeyboardGestures.inKeyboardArea(Float.NaN, 500f, tray))
        assertFalse(CarKeyboardGestures.inKeyboardArea(400f, Float.NaN, tray))
        assertFalse(CarKeyboardGestures.inKeyboardArea(-1f, -1f, tray))
    }
}
