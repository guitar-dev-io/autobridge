package dev.autobridge.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redesign's colour rules, checked without a device: the default is the AutoBridge dark look,
 * each mode picks the right surfaces, the accent swaps to its light-mode shade, card styles change
 * only fill and edge, and Auto schedules wrap past midnight.
 */
class ThemeResolverTest {
    private val defaults = ThemeResolver.resolve(AppearanceSpec.DEFAULT)

    @Test fun defaultIsTheAutoBridgeDarkLook() {
        assertFalse(defaults.isLight)
        assertEquals(0xFF10141B.toInt(), defaults.background)
        assertEquals(0xFF1A202B.toInt(), defaults.surface)
        assertEquals(0xFF67A9FF.toInt(), defaults.accent)
        assertEquals(0xFFF5F7FB.toInt(), defaults.text)
        assertEquals(0xFFA4B1C3.toInt(), defaults.textSecondary)
    }

    @Test fun lightModeUsesLightSurfacesAndTheDarkerAccent() {
        val p = ThemeResolver.resolve(AppearanceSpec(mode = ThemeMode.LIGHT))
        assertTrue(p.isLight)
        assertEquals(0xFFF2F4F8.toInt(), p.background)
        assertEquals(AccentColor.SKY.onLight, p.accent)
        assertEquals(0xFFFFFFFF.toInt(), p.onAccent)
    }

    @Test fun amoledIsTrueBlackWhateverThePreset() {
        val p = ThemeResolver.resolve(AppearanceSpec(mode = ThemeMode.AMOLED, preset = ThemePreset.EMBER))
        assertEquals(0xFF000000.toInt(), p.background)
    }

    @Test fun presetChangesDarkSurfacesOnly() {
        val p = ThemeResolver.resolve(AppearanceSpec(preset = ThemePreset.OCEAN))
        assertEquals(ThemePreset.OCEAN.background, p.background)
        assertEquals(defaults.text, p.text)
    }

    @Test fun systemModeFollowsAndroidNightMode() {
        val spec = AppearanceSpec(mode = ThemeMode.SYSTEM)
        assertTrue(ThemeResolver.resolve(spec, systemDark = false).isLight)
        assertFalse(ThemeResolver.resolve(spec, systemDark = true).isLight)
    }

    @Test fun autoScheduleWrapsPastMidnight() {
        val spec = AppearanceSpec(mode = ThemeMode.AUTO, autoDarkFrom = 18 * 60 + 30, autoDarkTo = 6 * 60 + 30)
        assertFalse(ThemeResolver.resolve(spec, minuteOfDay = 23 * 60).isLight)
        assertFalse(ThemeResolver.resolve(spec, minuteOfDay = 3 * 60).isLight)
        assertTrue(ThemeResolver.resolve(spec, minuteOfDay = 12 * 60).isLight)
        assertTrue(ThemeResolver.resolve(spec, minuteOfDay = 6 * 60 + 30).isLight)
    }

    @Test fun cardStylesChangeFillAndEdge() {
        assertEquals(defaults.surface, defaults.cardEdge)
        val outlined = ThemeResolver.resolve(AppearanceSpec(cardStyle = CardStyle.OUTLINED))
        assertEquals(0, outlined.cardFill)
        assertEquals(outlined.hairline, outlined.cardEdge)
        val glass = ThemeResolver.resolve(AppearanceSpec(cardStyle = CardStyle.GLASS))
        assertEquals(0xB8, glass.cardFill ushr 24)
    }

    @Test fun carKeepsDefaultsWhenSameLookIsOff() {
        val spec = AppearanceSpec(mode = ThemeMode.LIGHT, accent = AccentColor.ROSE, carSameLook = false)
        assertEquals(defaults, ThemeResolver.resolveCar(spec))
        assertTrue(ThemeResolver.resolveCar(spec.copy(carSameLook = true)).isLight)
    }

    @Test fun accentSoftIsTheAccentAtLowAlpha() {
        assertEquals(0x26, defaults.accentSoft ushr 24)
        assertEquals(defaults.accent and 0xFFFFFF, defaults.accentSoft and 0xFFFFFF)
    }
}

class TextScaleCurveTest {
    @Test fun bodyTextGrowsByTheFullScale() = assertEquals(30f, TextScaleCurve.scaled(15f, 2f), 0.01f)

    @Test fun displayTextGrowsByHalf() = assertEquals(45f, TextScaleCurve.scaled(30f, 2f), 0.01f)

    @Test fun inBetweenIsLinear() = assertEquals(40.8f, TextScaleCurve.scaled(24f, 2f), 0.01f)

    @Test fun defaultScaleChangesNothing() = assertEquals(17f, TextScaleCurve.scaled(17f, 1f), 0.001f)

    @Test fun followSystemUsesAndroidsScale() {
        val spec = AppearanceSpec(followSystemText = true, textStep = TextStep.SMALL)
        assertEquals(1.3f, TextScaleCurve.effectiveScale(spec, 1.3f), 0.001f)
        assertEquals(0.85f, TextScaleCurve.effectiveScale(spec.copy(followSystemText = false), 1.3f), 0.001f)
    }

    @Test fun carStopsAtTheCap() {
        assertEquals(1.3f, TextScaleCurve.carScale(AppearanceSpec(textStep = TextStep.MAX), 1f), 0.001f)
        assertEquals(1.15f, TextScaleCurve.carScale(AppearanceSpec(textStep = TextStep.LARGE), 1f), 0.001f)
        assertEquals(1f, TextScaleCurve.carScale(AppearanceSpec(textStep = TextStep.MAX, carSameLook = false), 1f), 0.001f)
    }
}

class AppearanceCodecTest {
    @Test fun roundTripsEveryField() {
        val spec = AppearanceSpec(
            mode = ThemeMode.AUTO, preset = ThemePreset.DUSK, accent = AccentColor.LIME, font = FontChoice.IBM_PLEX, thai = ThaiStyle.LOOPED,
            textStep = TextStep.HUGE, followSystemText = true, homeLayout = HomeLayout.COMPACT, wallpaper = Wallpaper.CUSTOM,
            wallpaperTarget = WallpaperTarget.CAR, wallpaperDim = 45, blurBehindCards = false, iconStyle = IconStyle.MONO,
            cardStyle = CardStyle.GLASS, corners = Corners.SHARP, showTileLabels = false, carSameLook = false,
            autoDarkFrom = 20 * 60, autoDarkTo = 5 * 60
        )
        assertEquals(spec, AppearanceCodec.decode(AppearanceCodec.encode(spec)))
    }

    @Test fun anUnknownThaiStyleFallsBackToTheSystem() =
        assertEquals(ThaiStyle.SYSTEM, AppearanceCodec.decode(mapOf("thai" to "CALLIGRAPHY")).thai)

    @Test fun emptyMapIsTheDefault() = assertEquals(AppearanceSpec.DEFAULT, AppearanceCodec.decode(emptyMap()))

    @Test fun unknownValuesFallBackPerField() {
        val decoded = AppearanceCodec.decode(
            mapOf("mode" to "NEON", "accent" to "ROSE", "wallpaper_dim" to "999", "auto_dark_from" to "-4", "blur" to "maybe")
        )
        assertEquals(ThemeMode.DARK, decoded.mode)
        assertEquals(AccentColor.ROSE, decoded.accent)
        assertEquals(AppearanceSpec.MAX_DIM, decoded.wallpaperDim)
        assertEquals(AppearanceSpec.DEFAULT.autoDarkFrom, decoded.autoDarkFrom)
        assertTrue(decoded.blurBehindCards)
    }
}

class ScriptTrackingTest {
    @Test fun latinLabelsKeepTheirTracking() = assertEquals(0.14f, ScriptTracking.letterSpacing("QUICK LAUNCH", 0.14f), 0f)

    @Test fun thaiLabelsGetNone() = assertEquals(0f, ScriptTracking.letterSpacing("ธีม", 0.14f), 0f)

    @Test fun mixedThaiAndLatinGetsNone() = assertFalse(ScriptTracking.allowsTracking("ใช้รูปลักษณ์นี้บน Android Auto"))

    @Test fun numbersAndSymbolsKeepTracking() = assertTrue(ScriptTracking.allowsTracking("93.0 FM · 100%"))
}
