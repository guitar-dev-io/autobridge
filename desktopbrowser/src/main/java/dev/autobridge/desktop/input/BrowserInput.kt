package dev.autobridge.desktop.input

import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.display.X11Connection
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Input into the X display, in display pixels. Everything goes through one thread so events reach
 * the server in the order the user made them and the UI thread never waits on a socket.
 *
 * The browser sees a mouse and a keyboard (XTEST), which every web page handles. Scrolling is
 * wheel clicks: one click per [PIXELS_PER_NOTCH] display pixels of finger travel, with the
 * remainder carried over so slow drags still scroll.
 */
class BrowserInput(private val x: X11Connection) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "desktop-input").apply { isDaemon = true } }
    private val keyboard by lazy { X11Keyboard(x) }
    private var carryX = 0f
    private var carryY = 0f
    @Volatile var lastEventAt = 0L
        private set

    fun moveTo(dx: Int, dy: Int) = post { x.fakeInput(X11Connection.MOTION_NOTIFY, 0, dx, dy) }

    fun click(dx: Int, dy: Int, button: Int = BUTTON_LEFT) = post {
        x.fakeInput(X11Connection.MOTION_NOTIFY, 0, dx, dy)
        x.fakeInput(X11Connection.BUTTON_PRESS, button)
        x.fakeInput(X11Connection.BUTTON_RELEASE, button)
    }

    fun press(dx: Int, dy: Int, button: Int = BUTTON_LEFT) = post {
        x.fakeInput(X11Connection.MOTION_NOTIFY, 0, dx, dy)
        x.fakeInput(X11Connection.BUTTON_PRESS, button)
    }

    fun release(dx: Int, dy: Int, button: Int = BUTTON_LEFT) = post {
        x.fakeInput(X11Connection.MOTION_NOTIFY, 0, dx, dy)
        x.fakeInput(X11Connection.BUTTON_RELEASE, button)
    }

    /**
     * Scrolls by a content distance in display pixels at ([atX], [atY]). Positive [dy] moves the
     * page content up (like dragging a finger upward / wheel down).
     */
    fun scroll(atX: Int, atY: Int, dx: Float, dy: Float) = post {
        x.fakeInput(X11Connection.MOTION_NOTIFY, 0, atX, atY)
        carryY += dy
        carryX += dx
        val notchesY = (carryY / PIXELS_PER_NOTCH).toInt()
        val notchesX = (carryX / PIXELS_PER_NOTCH).toInt()
        carryY -= notchesY * PIXELS_PER_NOTCH
        carryX -= notchesX * PIXELS_PER_NOTCH
        repeat(abs(notchesY).coerceAtMost(MAX_NOTCHES)) { wheel(if (notchesY > 0) BUTTON_WHEEL_DOWN else BUTTON_WHEEL_UP) }
        repeat(abs(notchesX).coerceAtMost(MAX_NOTCHES)) { wheel(if (notchesX > 0) BUTTON_WHEEL_RIGHT else BUTTON_WHEEL_LEFT) }
    }

    /** Page zoom with Ctrl+wheel, one step per ~10% of pinch. */
    fun zoom(atX: Int, atY: Int, factor: Float) = post {
        val steps = ((factor - 1f) * 10f).roundToInt().coerceIn(-5, 5)
        if (steps == 0) return@post
        x.fakeInput(X11Connection.MOTION_NOTIFY, 0, atX, atY)
        keyboardChord(Keysym.CONTROL_L) {
            repeat(abs(steps)) { wheel(if (steps > 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN) }
        }
    }

    fun typeText(text: String) = post { keyboard.typeText(text) }

    fun key(keysym: Int, vararg modifiers: Int) = post { keyboard.tapKeysym(keysym, *modifiers) }

    fun back() = key(Keysym.LEFT, Keysym.ALT_L)
    fun forward() = key(Keysym.RIGHT, Keysym.ALT_L)
    fun reload() = key(Keysym.F5)
    fun newTab() = key('t'.code, Keysym.CONTROL_L)
    fun focusAddressBar() = key('l'.code, Keysym.CONTROL_L)

    fun shutdown() = executor.shutdownNow()

    private fun wheel(button: Int) {
        x.fakeInput(X11Connection.BUTTON_PRESS, button)
        x.fakeInput(X11Connection.BUTTON_RELEASE, button)
    }

    private fun keyboardChord(modifierKeysym: Int, body: () -> Unit) {
        val mapping = x.keyboardMapping()
        val keycode = mapping.indexOfFirst { it.isNotEmpty() && it[0] == modifierKeysym }.takeIf { it >= 0 }?.plus(x.setup.minKeycode)
        keycode?.let { x.fakeInput(X11Connection.KEY_PRESS, it) }
        try { body() } finally { keycode?.let { x.fakeInput(X11Connection.KEY_RELEASE, it) } }
    }

    private fun post(block: () -> Unit) {
        lastEventAt = System.currentTimeMillis()
        if (executor.isShutdown) return
        executor.execute {
            try {
                if (x.isOpen) block()
            } catch (e: Exception) {
                DesktopLog.w("input dropped", e)
            }
        }
    }

    companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_MIDDLE = 2
        const val BUTTON_RIGHT = 3
        const val BUTTON_WHEEL_UP = 4
        const val BUTTON_WHEEL_DOWN = 5
        const val BUTTON_WHEEL_LEFT = 6
        const val BUTTON_WHEEL_RIGHT = 7
        /** Chromium on X11 scrolls ~53 px per wheel click at scale 1; 40 keeps a drag ~1:1 with smooth scrolling. */
        const val PIXELS_PER_NOTCH = 40f
        private const val MAX_NOTCHES = 30
    }
}
