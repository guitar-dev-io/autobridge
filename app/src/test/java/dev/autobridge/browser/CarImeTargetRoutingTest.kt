package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IME route's target routing — the single [routeImeEdit] result the renderer dispatches on
 * (finding 3) — plus the two lifecycle predicates ([isKeyboardOpen], [shouldCloseImeOnInsets]).
 * Because the renderer executes this mapper's result rather than re-deriving the routing inline,
 * these cases guard the live path: FIELD mirrors live, ADDRESS commits only on Go, backspace-to-empty
 * still clears the field. Modeled on [CarKeyboardMirrorTest].
 */
class CarImeTargetRoutingTest {

    @Test
    fun `field edit mirrors the trimmed buffer live`() {
        assertEquals(
            CarImeRoute.MirrorLive("netflix"),
            routeImeEdit(CarSurfaceKeyboard.Target.FIELD, "  netflix  ", ImeKeyKind.EDIT)
        )
    }

    @Test
    fun `field backspace to empty mirrors the empty string so the page field clears`() {
        assertEquals(
            CarImeRoute.MirrorLive(""),
            routeImeEdit(CarSurfaceKeyboard.Target.FIELD, "", ImeKeyKind.EDIT)
        )
    }

    @Test
    fun `address edit never mirrors`() {
        assertEquals(
            CarImeRoute.Ignore,
            routeImeEdit(CarSurfaceKeyboard.Target.ADDRESS, "example.com", ImeKeyKind.EDIT)
        )
    }

    @Test
    fun `address go with text resolves and loads the trimmed address`() {
        assertEquals(
            CarImeRoute.CommitAddress("example.com"),
            routeImeEdit(CarSurfaceKeyboard.Target.ADDRESS, "  example.com  ", ImeKeyKind.GO)
        )
    }

    @Test
    fun `address go on a blank buffer does nothing`() {
        assertEquals(
            CarImeRoute.Ignore,
            routeImeEdit(CarSurfaceKeyboard.Target.ADDRESS, "   ", ImeKeyKind.GO)
        )
    }

    @Test
    fun `field go submits the trimmed field`() {
        assertEquals(
            CarImeRoute.CommitFieldSubmit("hello"),
            routeImeEdit(CarSurfaceKeyboard.Target.FIELD, "  hello  ", ImeKeyKind.GO)
        )
    }

    // ---- lifecycle predicates (finding 1) ----

    @Test
    fun `keyboardOpen is the OR of the two keyboards`() {
        assertTrue(isKeyboardOpen(hasCanvas = true, hasImeSession = false))
        assertTrue(isKeyboardOpen(hasCanvas = false, hasImeSession = true))
        assertTrue(isKeyboardOpen(hasCanvas = true, hasImeSession = true))
        assertFalse(isKeyboardOpen(hasCanvas = false, hasImeSession = false))
    }

    @Test
    fun `a session loses its IME insets means close`() {
        assertTrue(shouldCloseImeOnInsets(imeSessionActive = true, imeVisible = false))
    }

    @Test
    fun `a session with the IME still visible stays open`() {
        assertFalse(shouldCloseImeOnInsets(imeSessionActive = true, imeVisible = true))
    }

    @Test
    fun `no session means nothing to close`() {
        assertFalse(shouldCloseImeOnInsets(imeSessionActive = false, imeVisible = false))
    }
}
