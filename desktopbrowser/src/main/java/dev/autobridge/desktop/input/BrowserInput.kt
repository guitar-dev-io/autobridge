package dev.autobridge.desktop.input

import dev.autobridge.desktop.DesktopLog
import dev.autobridge.desktop.display.X11Connection
import dev.autobridge.desktop.keyboard.ClipboardSync
import dev.autobridge.desktop.keyboard.KeyboardInputBridge
import dev.autobridge.desktop.keyboard.X11Clipboard
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
class BrowserInput(private val x: X11Connection, private val clipboard: X11Clipboard? = null) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "desktop-input").apply { isDaemon = true } }
    private val keys by lazy { X11Keyboard(x) }

    /** Clipboard bridge to Android; its CLIPBOARD ownership runs on this input thread. */
    @Volatile var clipboardSync: ClipboardSync? = null
        set(value) {
            field = value
            value?.post = ::postInput
        }

    /** All keyboard input (text, composition, keys, shortcuts, paste), in order with clicks. */
    val keyboard: KeyboardInputBridge = KeyboardInputBridge({ keys }, ::post, x, { clipboard }, { clipboardSync })
    private var carryX = 0f
    private var carryY = 0f
    @Volatile var lastEventAt = 0L
        private set

    /** Where the X pointer is, in display pixels; mouse (trackpad) mode moves it relatively. */
    @Volatile var cursorX = 0f
        private set
    @Volatile var cursorY = 0f
        private set

    fun moveTo(dx: Int, dy: Int) {
        cursorX = dx.toFloat(); cursorY = dy.toFloat()
        post { x.fakeInput(X11Connection.MOTION_NOTIFY, 0, dx, dy) }
    }

    /** Trackpad move, clamped to the visible window. */
    /**
     * Touch sensitivity from settings: multiplies scroll distance and trackpad movement. 1 is
     * finger-for-pixel; above 1 the page and the pointer move further than the finger.
     */
    @Volatile var sensitivity = 1f

    /** Button down / up where the pointer is: the two halves of a mouse click-drag. */
    fun pressAtCursor(button: Int = BUTTON_LEFT) = press(cursorX.toInt(), cursorY.toInt(), button)
    fun releaseAtCursor(button: Int = BUTTON_LEFT) = release(cursorX.toInt(), cursorY.toInt(), button)

    fun moveBy(dx: Float, dy: Float, maxX: Int, maxY: Int) {
        cursorX = (cursorX + dx * sensitivity).coerceIn(0f, (maxX - 1).toFloat())
        cursorY = (cursorY + dy * sensitivity).coerceIn(0f, (maxY - 1).toFloat())
        val tx = cursorX.toInt()
        val ty = cursorY.toInt()
        post { x.fakeInput(X11Connection.MOTION_NOTIFY, 0, tx, ty) }
    }

    fun clickAtCursor(button: Int = BUTTON_LEFT) = click(cursorX.toInt(), cursorY.toInt(), button)

    fun click(dx: Int, dy: Int, button: Int = BUTTON_LEFT) {
        post {
            cursorX = dx.toFloat(); cursorY = dy.toFloat()
            x.fakeInput(X11Connection.MOTION_NOTIFY, 0, dx, dy)
            x.fakeInput(X11Connection.BUTTON_PRESS, button)
            x.fakeInput(X11Connection.BUTTON_RELEASE, button)
        }
        keyboard.noteFocusMayChange()
    }

    fun press(dx: Int, dy: Int, button: Int = BUTTON_LEFT) {
        post {
            x.fakeInput(X11Connection.MOTION_NOTIFY, 0, dx, dy)
            x.fakeInput(X11Connection.BUTTON_PRESS, button)
        }
        keyboard.noteFocusMayChange()
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
        carryY += dy * sensitivity
        carryX += dx * sensitivity
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

    /** Hides / shows the X cursor, ordered with clicks and pointer moves. Touch hides it; mouse shows it. */
    fun hideCursor() = post { x.hideCursor() }
    fun showCursor() = post { x.showCursor() }

    fun typeText(text: String) = keyboard.commitText(text)

    fun key(keysym: Int, vararg modifiers: Int) = keyboard.key(keysym, *modifiers)

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

    /** Runs [block] on the input thread, in order with every other input. */
    internal fun postInput(block: () -> Unit) = post(block)

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
