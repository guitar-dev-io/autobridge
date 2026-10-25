package dev.autobridge.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car look for the screens besides the car home: each role comes from the car palette, the
 * card style, corners and text size follow the phone unless "Use this look on Android Auto" is
 * off, text stops at the car cap, and every text role stays readable in every mode, preset and
 * accent (the light mode in particular, where a pale shade on white would vanish).
 */
class CarLookTest {
    private fun look(spec: AppearanceSpec, systemScale: Float = 1f) =
        CarLook.of(spec, ThemeResolver.resolveCar(spec), systemScale)

    @Test fun defaultIsTheAutoBridgeDarkLook() {
        val l = CarLook.DEFAULT
        assertFalse(l.isLight)
        assertEquals(0xFF10141B.toInt(), l.background)
        assertEquals(AccentColor.SKY.onDark, l.accent)
        assertEquals(CardStyle.ELEVATED, l.cardStyle)
        assertEquals(Corners.SOFT, l.corners)
        assertEquals(1f, l.textScale, 0f)
        assertFalse(l.hasCardEdge)
    }

    @Test fun rolesComeFromTheCarPalette() {
        val spec = AppearanceSpec(mode = ThemeMode.LIGHT, accent = AccentColor.ROSE)
        val l = look(spec)
        val p = ThemeResolver.resolveCar(spec)
        assertTrue(l.isLight)
        assertEquals(p.background, l.background)
        assertEquals(p.raised, l.control)
        assertEquals(p.text, l.text)
        assertEquals(AccentColor.ROSE.onLight, l.accent)
        assertEquals(p.onAccent, l.onAccent)
        assertEquals(p.danger, l.live)
    }

    @Test fun outlinedCardsAreTransparentWithAnEdgeAndStillShowAPress() {
        val l = look(AppearanceSpec(cardStyle = CardStyle.OUTLINED))
        assertEquals(0, l.card ushr 24)
        assertTrue(l.hasCardEdge)
        assertEquals(l.hairline, l.cardEdge)
        assertTrue((l.cardPressed ushr 24) > 0)
    }

    @Test fun glassCardsAreTranslucentAndKeepTheirAlphaWhenPressed() {
        val l = look(AppearanceSpec(cardStyle = CardStyle.GLASS))
        val alpha = l.card ushr 24
        assertTrue(alpha in 1..254)
        assertTrue(l.hasCardEdge)
        assertEquals(alpha, l.cardPressed ushr 24)
        assertNotEquals(l.card, l.cardPressed)
    }

    @Test fun sharpCornersShrinkEveryRadius() {
        val soft = look(AppearanceSpec(corners = Corners.SOFT))
        val sharp = look(AppearanceSpec(corners = Corners.SHARP))
        assertTrue(sharp.cardRadius < soft.cardRadius)
        assertTrue(sharp.tileRadius < soft.tileRadius)
        assertTrue(sharp.buttonRadius < soft.buttonRadius)
        assertTrue(sharp.chipRadius(52f) < soft.chipRadius(52f))
        assertEquals(Corners.SOFT.chip.toFloat(), soft.chipRadius(52f), 0.001f)
    }

    @Test fun carTextFollowsThePhoneUpToTheCap() {
        assertEquals(1.15f, look(AppearanceSpec(textStep = TextStep.LARGE)).textScale, 0.001f)
        assertEquals(TextScaleCurve.CAR_CAP, look(AppearanceSpec(textStep = TextStep.MAX)).textScale, 0.001f)
        assertEquals(TextScaleCurve.CAR_CAP, look(AppearanceSpec(followSystemText = true), systemScale = 2f).textScale, 0.001f)
        val l = look(AppearanceSpec(textStep = TextStep.HUGE))
        assertTrue(l.text(18f) > 18f)
        assertTrue(l.text(18f) <= 18f * TextScaleCurve.CAR_CAP + 0.001f)
    }

    @Test fun carSameLookOffKeepsTheDefaultLookWhateverThePhoneUses() {
        val phone = AppearanceSpec(
            mode = ThemeMode.LIGHT, accent = AccentColor.LIME, cardStyle = CardStyle.GLASS,
            corners = Corners.SHARP, textStep = TextStep.MAX, font = FontChoice.MANROPE, carSameLook = false
        )
        val l = look(phone)
        assertEquals(CarLook.DEFAULT.copy(palette = l.palette), l)
        assertEquals(CarLook.DEFAULT.palette, l.palette)
        assertEquals(1f, l.textScale, 0f)
    }

    @Test fun aChangeOnThePhoneIsADifferentLook() {
        val base = look(AppearanceSpec())
        assertNotEquals(base, look(AppearanceSpec(accent = AccentColor.AMBER)))
        assertNotEquals(base, look(AppearanceSpec(corners = Corners.SHARP)))
        assertNotEquals(base, look(AppearanceSpec(font = FontChoice.NOTO)))
        assertNotEquals(base, look(AppearanceSpec(textStep = TextStep.LARGER)))
        assertEquals(base, look(AppearanceSpec()))
    }

    @Test fun everyTextRoleIsReadableInEveryModePresetAndAccent() {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.AMOLED)) for (preset in ThemePreset.values()) for (accent in AccentColor.values()) {
            val l = look(AppearanceSpec(mode = mode, preset = preset, accent = accent))
            val where = "$mode $preset $accent"
            // Body text (WCAG AA 4.5:1) on the page, an opaque card and a raised control.
            for (ground in intArrayOf(l.background, l.surface, l.control)) {
                assertTrue("text $where", CarLook.contrast(l.text, ground) >= 4.5)
                assertTrue("secondary $where", CarLook.contrast(l.textSecondary, ground) >= 4.5)
            }
            assertTrue("tertiary $where", CarLook.contrast(l.textTertiary, l.background) >= 4.5)
            // Glyphs and bold labels on the accent and on the selected fill: large text, 3:1.
            assertTrue("onAccent $where", CarLook.contrast(l.onAccent, l.accent) >= 3.0)
            assertTrue("onAccent pressed $where", CarLook.contrast(l.onAccent, l.accentPressed) >= 3.0)
            assertTrue("selected $where", CarLook.contrast(l.selectedText, l.selectedFill) >= 3.0)
            assertTrue("live $where", CarLook.contrast(l.live, l.background) >= 3.0)
            assertTrue("disabled $where", CarLook.contrast(l.controlDisabled, l.control) >= 2.5)
            assertTrue("pressed text $where", CarLook.contrast(l.text, l.controlPressed) >= 4.5)
        }
    }

    @Test fun mixIsOpaqueAndHitsItsEnds() {
        assertEquals(0xFF000000.toInt(), CarLook.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), CarLook.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFF, CarLook.mix(0x00102030, 0x00405060, 0.5f) ushr 24)
    }
}
