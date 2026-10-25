package dev.autobridge.duoscreen.chrome

import dev.autobridge.design.AccentColor
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.CarLook
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Duo Screen's chrome follows the car look, and its text stays readable on what it sits on. */
class DuoChromeColorsTest {
    private fun colors(spec: AppearanceSpec) = DuoChromeColors.of(CarLook.of(spec, ThemeResolver.resolveCar(spec), 1f))

    @Test fun accentAndPageFollowTheLook() {
        val c = colors(AppearanceSpec(accent = AccentColor.TEAL))
        assertEquals(AccentColor.TEAL.onDark, c.accent)
        assertEquals(CarLook.DEFAULT.background, c.surface)
    }

    @Test fun lightModeVeilsPanesInLightAndWritesInDark() {
        val c = colors(AppearanceSpec(mode = ThemeMode.LIGHT))
        assertEquals(0xF2F4F8, c.dim and 0x00FFFFFF)
        assertEquals(0xFF10141B.toInt(), c.text)
    }

    @Test fun textIsReadableOnItsGroundInEveryModeAndAccent() {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.AMOLED)) for (accent in AccentColor.values()) {
            val c = colors(AppearanceSpec(mode = mode, accent = accent))
            val where = "$mode $accent"
            assertTrue("button $where", CarLook.contrast(c.icon, c.button) >= 4.5)
            assertTrue("done $where", CarLook.contrast(c.onAccent, c.accent) >= 3.0)
            assertTrue("swap $where", CarLook.contrast(c.text, c.swap) >= 4.5)
            assertTrue("card button $where", CarLook.contrast(c.cardButtonText, c.cardButton) >= 4.5)
            assertTrue("chip $where", CarLook.contrast(c.chipTextSelected, c.chipSelected) >= 3.0)
        }
    }

    @Test fun carSwitchOffKeepsTheDefault() {
        assertEquals(colors(AppearanceSpec()), colors(AppearanceSpec(mode = ThemeMode.LIGHT, accent = AccentColor.ROSE, carSameLook = false)))
    }
}
