package dev.autobridge.desktop.input

import dev.autobridge.design.AccentColor
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.CarLook
import dev.autobridge.design.DesignTokens
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop browser's rail on the car follows the car look; the phone keeps the fixed rail. */
class RailColorsTest {
    private fun colors(spec: AppearanceSpec) = RailColors.of(CarLook.of(spec, ThemeResolver.resolveCar(spec), 1f))

    @Test fun fixedIsTheDesktopRail() {
        assertEquals(DesignTokens.DesktopRail.SELECTED, RailColors.FIXED.selected)
        assertEquals(DesignTokens.DesktopRail.PANEL_FILL, RailColors.FIXED.panelFill)
    }

    @Test fun selectedIsTheAccentWithItsLabelColour() {
        val c = colors(AppearanceSpec(accent = AccentColor.ROSE))
        assertEquals(AccentColor.ROSE.onDark, c.selected)
        assertEquals(ThemeResolver.resolve(AppearanceSpec()).onAccent, c.onSelected)
    }

    @Test fun labelsAreReadableInEveryModeAndAccent() {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.AMOLED)) for (accent in AccentColor.values()) {
            val c = colors(AppearanceSpec(mode = mode, accent = accent))
            val where = "$mode $accent"
            assertTrue("button $where", CarLook.contrast(c.ink, c.buttonFill) >= 4.5)
            assertTrue("selected $where", CarLook.contrast(c.onSelected, c.selected) >= 3.0)
            assertTrue("outlined $where", CarLook.contrast(c.ink, c.outlinedFill) >= 4.5)
        }
    }
}
