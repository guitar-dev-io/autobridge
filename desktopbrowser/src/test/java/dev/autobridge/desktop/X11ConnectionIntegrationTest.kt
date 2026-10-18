package dev.autobridge.desktop

import dev.autobridge.desktop.display.X11Connection
import dev.autobridge.desktop.input.Keysym
import dev.autobridge.desktop.input.X11Keyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives a real Xvfb (with a real browser on it) through the same client code the phone uses.
 * Runs only when pointed at one, e.g. the arm64 Debian container from TEST-RESULTS.md:
 * `X11_TEST_PORT=6001 ./gradlew :desktopbrowser:testDebugUnitTest --tests '*X11ConnectionIntegrationTest*'`
 */
class X11ConnectionIntegrationTest {
    private val port = System.getenv("X11_TEST_PORT")?.toIntOrNull()

    private fun connect(): X11Connection {
        val socket = Socket("127.0.0.1", port!!)
        return X11Connection.open(socket.getInputStream(), socket.getOutputStream(), socket)
    }

    @Test fun handshakeExtensionsPointerKeyboardAndDamage() {
        assumeTrue(port != null)
        val x = connect()
        try {
            println("X server: ${x.setup}")
            assertTrue(x.setup.width > 0 && x.setup.height > 0)
            assertTrue(x.queryExtension("XTEST") != null)
            assertTrue(x.queryExtension("DAMAGE") != null)

            x.fakeInput(X11Connection.MOTION_NOTIFY, 0, 123, 45)
            x.sync()
            assertEquals(123 to 45, x.queryPointer())

            x.onError = { code, major, minor -> println("X error $code major=$major minor=$minor") }
            println("DAMAGE=${x.queryExtension("DAMAGE")}")
            val damaged = CountDownLatch(1)
            val damage = x.createRootDamage()!!
            x.onEvent = { code, _ -> println("event $code"); if (x.isDamageEvent(code)) damaged.countDown() }
            // The software cursor does not raise DAMAGE; a browser repaint does. Reloading the
            // page makes the browser redraw it.
            X11Keyboard(x).tapKeysym(Keysym.F5)
            assertTrue("no DamageNotify", damaged.await(10, TimeUnit.SECONDS))
            x.subtractDamage(damage)

            val mapping = x.keyboardMapping()
            assertEquals(x.setup.maxKeycode - x.setup.minKeycode + 1, mapping.size)
            assertTrue(mapping.any { it.isNotEmpty() && it[0] == 'a'.code })
        } finally {
            x.close()
        }
    }

    /**
     * Types into the browser's address bar, including a Thai word (needs keycode remapping), and
     * presses Enter. Check the result in the framebuffer dump (TEST-RESULTS.md).
     */
    @Test fun typesIntoTheBrowser() {
        assumeTrue(port != null && System.getenv("X11_TEST_TYPE") != null)
        val x = connect()
        try {
            val keyboard = X11Keyboard(x)
            x.fakeInput(X11Connection.MOTION_NOTIFY, 0, 640, 400)
            x.fakeInput(X11Connection.BUTTON_PRESS, 1)
            x.fakeInput(X11Connection.BUTTON_RELEASE, 1)
            keyboard.tapKeysym('l'.code, Keysym.CONTROL_L)
            keyboard.typeText(System.getenv("X11_TEST_TYPE")!!)
            keyboard.tapKeysym(Keysym.RETURN)
            x.sync()
        } finally {
            x.close()
        }
    }
}
