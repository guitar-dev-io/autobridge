package dev.autobridge.desktop.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlRailLayoutTest {
    @Test fun keepsSizeWhenTextFits() {
        assertEquals(16f, fittedTextSize(80f, 100f, 16f), 0.001f)
    }

    @Test fun shrinksToFit() {
        assertEquals(14.4f, fittedTextSize(100f, 90f, 16f), 0.001f)
    }

    @Test fun neverShrinksBelowEightyPercent() {
        assertEquals(12.8f, fittedTextSize(100f, 50f, 16f), 0.001f)
    }
}
