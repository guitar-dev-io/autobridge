package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The live-mirror gating decision: which keystroke mirrors into the focused page field, and with
 * what text. This is the single rule the CarWebRenderer route reads, so the cases that are easy to
 * break silently — a layer/cursor key leaking an `input` event, the address bar leaking a URL,
 * backspace-to-empty failing to clear the field — are pinned here.
 */
class CarKeyboardMirrorTest {

    @Test
    fun `text mutation on a field mirrors the trimmed buffer`() {
        assertEquals(
            "netflix",
            CarKeyboardMirror.liveWrite(pageMutated = true, target = CarSurfaceKeyboard.Target.FIELD, bufferText = "netflix")
        )
    }

    @Test
    fun `backspace to empty on a field mirrors the empty string so the page field clears`() {
        assertEquals(
            "",
            CarKeyboardMirror.liveWrite(pageMutated = true, target = CarSurfaceKeyboard.Target.FIELD, bufferText = "")
        )
    }

    @Test
    fun `the buffer is trimmed before mirroring`() {
        assertEquals(
            "netflix",
            CarKeyboardMirror.liveWrite(pageMutated = true, target = CarSurfaceKeyboard.Target.FIELD, bufferText = "  netflix  ")
        )
    }

    @Test
    fun `a non-mutating key on a field never mirrors`() {
        // Shift / Language / cursor keys / a cursor-only field tap all report pageMutated = false,
        // so no page write and no spurious input event — parity with the projection route.
        assertNull(
            CarKeyboardMirror.liveWrite(pageMutated = false, target = CarSurfaceKeyboard.Target.FIELD, bufferText = "netflix")
        )
    }

    @Test
    fun `the address bar is never mirrored even on a text mutation`() {
        assertNull(
            CarKeyboardMirror.liveWrite(pageMutated = true, target = CarSurfaceKeyboard.Target.ADDRESS, bufferText = "example.com")
        )
    }

    @Test
    fun `a non-mutating key on the address bar never mirrors`() {
        assertNull(
            CarKeyboardMirror.liveWrite(pageMutated = false, target = CarSurfaceKeyboard.Target.ADDRESS, bufferText = "example.com")
        )
    }
}
