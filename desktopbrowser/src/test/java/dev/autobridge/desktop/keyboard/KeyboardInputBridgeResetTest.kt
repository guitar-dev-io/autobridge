package dev.autobridge.desktop.keyboard

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KeyboardInputBridgeResetTest {
    private var savedDispatcher: ((Runnable) -> Unit)? = null
    private val registered = ArrayList<() -> Unit>()

    @Before fun runOnTheCallingThread() {
        savedDispatcher = KeyboardInputBridge.mainDispatcher
        KeyboardInputBridge.mainDispatcher = { it.run() }
    }

    @After fun cleanUp() {
        registered.forEach { KeyboardInputBridge.removeResetListener(it) }
        savedDispatcher?.let { KeyboardInputBridge.mainDispatcher = it }
    }

    private fun listener(counter: IntArray): () -> Unit {
        val l: () -> Unit = { counter[0] += 1 }
        registered.add(l)
        KeyboardInputBridge.addResetListener(l)
        return l
    }

    @Test fun oneResetNotifiesEachListenerOnce() {
        val a = IntArray(1)
        val b = IntArray(1)
        listener(a)
        listener(b)
        KeyboardInputBridge.fireReset()
        assertEquals(1, a[0])
        assertEquals(1, b[0])
    }

    @Test fun removedListenerIsNotCalled() {
        val a = IntArray(1)
        val b = IntArray(1)
        listener(a)
        val lb = listener(b)
        KeyboardInputBridge.removeResetListener(lb)
        KeyboardInputBridge.fireReset()
        assertEquals(1, a[0])
        assertEquals(0, b[0])
    }

    @Test fun addingTheSameListenerTwiceRegistersItOnce() {
        val a = IntArray(1)
        val la = listener(a)
        KeyboardInputBridge.addResetListener(la)
        KeyboardInputBridge.fireReset()
        assertEquals(1, a[0])
    }

    @Test fun pasteRefusalReachesRegisteredListenersOnly() {
        val a = IntArray(1)
        val l: () -> Unit = { a[0] += 1 }
        KeyboardInputBridge.addPasteRefusedListener(l)
        KeyboardInputBridge.firePasteRefused()
        KeyboardInputBridge.removePasteRefusedListener(l)
        KeyboardInputBridge.firePasteRefused()
        assertEquals(1, a[0])
    }

    @Test fun twoSessionReplacementsGiveExactlyTwoCalls() {
        val a = IntArray(1)
        listener(a)
        // DesktopEngine fires once per _session.value assignment (open, stop).
        KeyboardInputBridge.fireReset()
        KeyboardInputBridge.fireReset()
        assertEquals(2, a[0])
    }
}
