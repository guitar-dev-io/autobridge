package dev.autobridge.browser

import dev.autobridge.design.AccentColor
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.CarLook
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car browser's chrome and keyboard follow the car's Appearance: the scheme is built from the
 * car palette (the default look when the car-same-look switch is off), the keyboard's Go key is
 * the accent with its onAccent label, and every key label stays readable in light mode too.
 */
class CarBrowserLookTest {
    private fun carPalette(spec: AppearanceSpec) = ThemeResolver.resolveCar(spec)

    @Test fun carSchemeIsBuiltFromTheCarPalette() {
        val spec = AppearanceSpec(mode = ThemeMode.LIGHT, accent = AccentColor.TEAL)
        val chrome = BrowserTheme.car(carPalette(spec))
        assertEquals(AccentColor.TEAL.onLight, chrome.accent)
        assertEquals(carPalette(spec).background, chrome.background)
        assertEquals(carPalette(spec).text, chrome.textPrimary)
    }

    @Test fun carSchemeKeepsTheDefaultLookWhenTheCarSwitchIsOff() {
        val spec = AppearanceSpec(mode = ThemeMode.LIGHT, accent = AccentColor.ROSE, carSameLook = false)
        val chrome = BrowserTheme.car(carPalette(spec))
        assertEquals(AccentColor.SKY.onDark, chrome.accent)
        assertEquals(CarLook.DEFAULT.background, chrome.background)
    }

    @Test fun carSchemeIsBuiltOncePerPalette() {
        val palette = carPalette(AppearanceSpec(accent = AccentColor.VIOLET))
        assertSame(BrowserTheme.car(palette), BrowserTheme.car(palette))
    }

    @Test fun keyboardGoKeyIsTheAccentWithItsLabelColour() {
        val chrome = BrowserTheme.car(carPalette(AppearanceSpec(accent = AccentColor.AMBER)))
        val keys = BrowserKeyboardStyle.Colors.fromChrome(chrome)
        val (cap, ink) = keys.colours(BrowserKey.Go, ShiftState.OFF)
        assertEquals(AccentColor.AMBER.onDark, cap)
        assertEquals(chrome.onPrimary, ink)
        assertEquals(chrome.primary, keys.cursor)
    }

    @Test fun darkKeyboardKeepsTheFixedColours() {
        val dark = BrowserKeyboardStyle.Colors.DARK
        assertEquals(BrowserKeyboardStyle.GO_CAP, dark.colours(BrowserKey.Go, ShiftState.OFF).first)
        assertEquals(BrowserKeyboardStyle.TRAY, dark.tray)
        assertEquals(BrowserKeyboardStyle.GO_PRESSED, dark.pressedCap(BrowserKey.Go))
    }

    @Test fun keyLabelsAreReadableInEveryModeAndAccent() {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.AMOLED)) for (accent in AccentColor.values()) {
            val keys = BrowserKeyboardStyle.Colors.fromChrome(BrowserTheme.car(carPalette(AppearanceSpec(mode = mode, accent = accent))))
            val where = "$mode $accent"
            assertTrue("letter $where", CarLook.contrast(keys.ink, keys.letterCap) >= 4.5)
            assertTrue("function $where", CarLook.contrast(keys.onFunctionCap, keys.functionCap) >= 4.5)
            assertTrue("go $where", CarLook.contrast(keys.onGoCap, keys.goCap) >= 3.0)
            assertTrue("shift $where", CarLook.contrast(keys.shiftOnInk, keys.shiftOnCap) >= 4.5)
            assertTrue("field $where", CarLook.contrast(keys.ink, keys.field) >= 4.5)
        }
    }
}
