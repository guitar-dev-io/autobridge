package dev.autobridge.display

import dev.autobridge.display.RearDisplays.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RearDisplaysTest {

    private fun display(
        id: Int,
        name: String,
        presentation: Boolean = true,
        private: Boolean = false,
        valid: Boolean = true,
        on: Boolean = true,
    ) = Facts(id, name, presentation, private, valid, on)

    @Test fun anHdmiScreenIsTheRearScreen() {
        val hdmi = display(2, "HDMI Screen")
        assertEquals(hdmi, RearDisplays.pick(listOf(display(0, "Built-in Screen"), hdmi)))
    }

    @Test fun thePhonesOwnScreenIsNeverChosen() {
        assertNull(RearDisplays.pick(listOf(display(0, "Built-in Screen"))))
    }

    @Test fun autoBridgesOwnVirtualDisplaysAreSkipped() {
        val own = listOf(
            display(5, "AutoBridgeCarBrowser"),
            display(6, "AutoBridgeDuoPane1"),
            display(7, "AutoBridgeMirror"),
        )
        assertNull(RearDisplays.pick(own))
        val miracast = display(9, "Living room TV")
        assertEquals(miracast, RearDisplays.pick(own + miracast))
    }

    @Test fun privateAndNonPresentationDisplaysAreSkipped() {
        assertNull(
            RearDisplays.pick(
                listOf(
                    display(3, "Android Auto projection", private = true),
                    display(4, "Something else", presentation = false),
                    display(8, "Gone", valid = false),
                )
            )
        )
    }

    @Test fun aScreenThatIsOnWinsOverOneThatIsOff() {
        val off = display(2, "HDMI Screen", on = false)
        val on = display(3, "Overlay #1")
        assertEquals(on, RearDisplays.pick(listOf(off, on)))
        assertEquals(off, RearDisplays.pick(listOf(off)))
    }
}
