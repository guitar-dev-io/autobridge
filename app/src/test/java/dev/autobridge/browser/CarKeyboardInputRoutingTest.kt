package dev.autobridge.browser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing against the real tray geometry the car keyboard draws (752x300 area at 24,88). */
class CarKeyboardInputRoutingTest {
    private val geometry = BrowserKeyboardGeometry.create(
        Box(24f, 88f, 776f, 388f), 1f,
        BrowserKeyboardLayouts.rows(BrowserKeyboardMode.URL, CarKeyboardLanguage.LATIN, false),
    )
    private val tray = geometry.tray

    private fun inArea(x: Float, y: Float) = CarKeyboardGestures.inKeyboardArea(x, y, tray)

    @Test
    fun `gap between two keys hits no key but is keyboard area`() {
        val first = geometry.keys[0].second
        val second = geometry.keys[1].second
        val x = (first.right + second.left) / 2f
        val y = first.centerY
        assertNull(geometry.keyAt(x, y))
        assertTrue(inArea(x, y))
    }

    @Test
    fun `tray padding hits no key but is keyboard area`() {
        val x = tray.left + 1f
        val y = tray.top + 1f
        assertNull(geometry.keyAt(x, y))
        assertFalse(geometry.field.contains(x, y))
        assertFalse(geometry.hide.contains(x, y))
        assertTrue(inArea(x, y))
    }

    @Test
    fun `field, hide and clear are keyboard area`() {
        assertTrue(inArea(geometry.field.centerX, geometry.field.centerY))
        assertTrue(inArea(geometry.hide.centerX, geometry.hide.centerY))
        assertTrue(inArea(geometry.clear.centerX, geometry.clear.centerY))
    }

    @Test
    fun `tray bottom right corner is keyboard area`() {
        assertTrue(inArea(tray.right, tray.bottom))
    }

    @Test
    fun `page above the tray is not keyboard area`() {
        assertFalse(inArea(tray.centerX, tray.top - 1f))
    }

    @Test
    fun `page receives everything while the keyboard is closed`() {
        CarSurfaceGesture.values().forEach { gesture ->
            assertTrue(CarKeyboardGestures.pageReceives(gesture, null, null, null))
            assertTrue(CarKeyboardGestures.pageReceives(gesture, 100f, 100f, null))
        }
    }

    @Test
    fun `page receives nothing while the keyboard is open`() {
        val inside = tray.centerY
        val above = tray.top - 50f
        assertFalse(CarKeyboardGestures.pageReceives(CarSurfaceGesture.TAP, tray.centerX, inside, tray))
        assertFalse(CarKeyboardGestures.pageReceives(CarSurfaceGesture.TAP, tray.centerX, above, tray))
        assertFalse(CarKeyboardGestures.pageReceives(CarSurfaceGesture.SCROLL, null, null, tray))
        assertFalse(CarKeyboardGestures.pageReceives(CarSurfaceGesture.FLING, null, null, tray))
        assertFalse(CarKeyboardGestures.pageReceives(CarSurfaceGesture.SCALE, tray.centerX, inside, tray))
        assertFalse(CarKeyboardGestures.pageReceives(CarSurfaceGesture.SCALE, tray.centerX, above, tray))
    }
}
