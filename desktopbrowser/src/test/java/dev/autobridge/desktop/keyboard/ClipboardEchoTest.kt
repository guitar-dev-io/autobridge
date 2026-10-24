package dev.autobridge.desktop.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ClipboardSync]'s echo suppression across a bridge paste (review finding 1). */
class ClipboardEchoTest {
    @Test fun echoOfTheLastSyncedTextIsSkipped() {
        val echo = ClipboardEcho()
        assertTrue(echo.changed("A")) // Chrome copied A -> Android
        assertFalse(echo.changed("A")) // the Android clip A coming back is an echo
        assertTrue(echo.changed("B"))
    }

    /** Chrome copy A, a long IME commit T pasted by the bridge, then Paste: A is owned again. */
    @Test fun aBridgePasteMakesTheNextAndroidPushOwnTheClipAgain() {
        val echo = ClipboardEcho()
        assertTrue(echo.changed("A")) // onGuestCopied("A"), the Android clip becomes A
        echo.forget() // KeyboardInputBridge.paste(T) owned CLIPBOARD: noteBridgeOwned()
        assertTrue(echo.changed("A")) // rail/Gboard Paste: pushAndroidClipboard owns A before Ctrl+V
        assertFalse(echo.changed("A"))
    }

    @Test fun withoutForgetThePushAfterABridgePasteWouldBeDropped() {
        val echo = ClipboardEcho()
        assertTrue(echo.changed("A"))
        // No forget: the guest holds T, but the push of A looks like an echo (the reviewed bug).
        assertFalse(echo.changed("A"))
    }
}
