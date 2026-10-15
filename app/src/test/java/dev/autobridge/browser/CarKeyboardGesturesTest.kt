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
}
