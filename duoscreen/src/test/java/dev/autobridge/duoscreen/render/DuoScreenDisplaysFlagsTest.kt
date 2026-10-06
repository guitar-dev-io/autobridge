package dev.autobridge.duoscreen.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision this fix turns on, at the only level a JVM test can prove it: the flag set the
 * trusted (power-button-surviving) display is created with. The platform behavior those flags buy
 * — a display that keeps composing after a hardware power-off — can only be verified on a device,
 * and is recorded as such in the task's verification notes.
 */
class DuoScreenDisplaysFlagsTest {
    private val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private val FLAG_TRUSTED = 1 shl 10
    private val FLAG_OWN_DISPLAY_GROUP = 1 shl 11

    @Test fun trustedFlagsCarryTheOwnContentAndTouchBitsOfTheUntrustedPath() {
        assertTrue("own-content bit", DuoScreenDisplays.TRUSTED_FLAGS and FLAG_OWN_CONTENT_ONLY != 0)
        assertTrue("supports-touch bit", DuoScreenDisplays.TRUSTED_FLAGS and FLAG_SUPPORTS_TOUCH != 0)
    }

    @Test fun trustedFlagsAddTheTrustedAndOwnGroupBits() {
        assertTrue("trusted bit", DuoScreenDisplays.TRUSTED_FLAGS and FLAG_TRUSTED != 0)
        assertTrue("own-display-group bit", DuoScreenDisplays.TRUSTED_FLAGS and FLAG_OWN_DISPLAY_GROUP != 0)
    }

    @Test fun paneAppsEndWithTheirDisplay() {
        assertTrue("destroy-content bit", DuoScreenDisplays.TRUSTED_FLAGS and FLAG_DESTROY_CONTENT_ON_REMOVAL != 0)
    }

    @Test fun trustedFlagsAreExactlyThoseFiveBitsAndNoOther() {
        val expected = FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL or
            FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP
        assertEquals(expected, DuoScreenDisplays.TRUSTED_FLAGS)
    }
}
