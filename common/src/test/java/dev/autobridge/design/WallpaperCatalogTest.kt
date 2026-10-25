package dev.autobridge.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperCatalogTest {

    @Test fun everyTabOffersOnlyItsOwnKindAndNothingIsLeftOut() {
        val offered = WallpaperKind.entries.flatMap { kind ->
            WallpaperCatalog.inKind(kind).onEach { assertEquals(kind, it.kind) }
        }
        assertEquals(Wallpaper.entries.toSet(), offered.toSet())
        assertEquals(offered.size, offered.toSet().size)
    }

    @Test fun everyPlainColourHasATintForBothThemes() {
        Wallpaper.entries.filter { it.kind == WallpaperKind.SOLID && it != Wallpaper.NONE && it != Wallpaper.LINES }.forEach {
            assertNotNull(it.name, WallpaperCatalog.solidColor(it, light = false))
            assertNotNull(it.name, WallpaperCatalog.solidColor(it, light = true))
        }
        assertNull(WallpaperCatalog.solidColor(Wallpaper.GLOW, light = false))
    }

    @Test fun everyGradientButTheGlowHasARampThatTurns() {
        Wallpaper.entries.filter { it.kind == WallpaperKind.GRADIENT && it != Wallpaper.GLOW }.forEach {
            for (light in listOf(false, true)) {
                val ramp = WallpaperCatalog.ramp(it, light)!!
                assertEquals(it.name, ramp.colors.size, ramp.stops.size)
                assertTrue(it.name, ramp.stops.first() == 0f && ramp.stops.last() == 1f)
            }
            assertTrue(WallpaperCatalog.turns(it))
        }
        assertFalse(WallpaperCatalog.turns(Wallpaper.GLOW))
        assertEquals(180, WallpaperCatalog.defaultAngle(Wallpaper.NIGHT_ROAD))
    }

    @Test fun onlyImagesAndPhotosBlurAndCrop() {
        assertTrue(WallpaperCatalog.blurs(Wallpaper.IMAGE_AURORA))
        assertTrue(WallpaperCatalog.crops(Wallpaper.CUSTOM))
        assertFalse(WallpaperCatalog.blurs(Wallpaper.GRADIENT_OCEAN))
        assertFalse(WallpaperCatalog.crops(Wallpaper.SOLID))
    }

    @Test fun theCropShowsTheWholeImageWhenTheShapesMatch() {
        val s = WallpaperCatalog.source(1500f, 1000f, 300f, 200f, WallpaperCrop.FULL)
        assertEquals(WallpaperSource(0f, 0f, 1500f, 1000f), s)
    }

    @Test fun aTallScreenTakesAStripOfAWideImage() {
        // 3:2 image on a 390:844 phone: full height, 390/844 of it wide, centred.
        val s = WallpaperCatalog.source(1500f, 1000f, 390f, 844f, WallpaperCrop.FULL)
        assertEquals(1000f, s.height, 0.01f)
        assertEquals(1000f * 390f / 844f, s.width, 0.01f)
        assertEquals((1500f - s.width) / 2f, s.left, 0.01f)
        assertEquals(0f, s.top, 0.01f)
    }

    @Test fun zoomShrinksTheSourceAndThePositionMovesIt() {
        val full = WallpaperCatalog.source(1500f, 1000f, 390f, 844f, WallpaperCrop.FULL)
        val zoomed = WallpaperCatalog.source(1500f, 1000f, 390f, 844f, WallpaperCrop(200, 0, 100))
        assertEquals(full.width / 2f, zoomed.width, 0.01f)
        assertEquals(full.height / 2f, zoomed.height, 0.01f)
        assertEquals(0f, zoomed.left, 0.01f)
        assertEquals(1000f - zoomed.height, zoomed.top, 0.01f)
    }

    @Test fun theCropNeverLeavesTheImage() {
        for (zoom in listOf(100, 150, 300)) for (x in listOf(0, 50, 100)) for (y in listOf(0, 50, 100)) {
            val s = WallpaperCatalog.source(1200f, 900f, 1020f, 604f, WallpaperCrop(zoom, x, y))
            assertTrue(s.left >= -0.01f && s.top >= -0.01f)
            assertTrue(s.left + s.width <= 1200.01f && s.top + s.height <= 900.01f)
        }
    }

    @Test fun outOfRangeCropsAreHeldToTheirRange() {
        assertEquals(WallpaperCrop(300, 100, 0), WallpaperCrop(900, 140, -3).clamped())
    }

    @Test fun theOutlineIsFractionsOfTheWholePicture() {
        val o = WallpaperCatalog.outline(1.5f, 390f / 844f, WallpaperCrop.FULL)
        assertEquals(1f, o.height, 0.001f)
        assertTrue(o.width < 0.4f)
        assertTrue(WallpaperCatalog.canPanX(1.5f, 390f / 844f, WallpaperCrop.FULL))
        assertFalse(WallpaperCatalog.canPanY(1.5f, 390f / 844f, WallpaperCrop.FULL))
    }

    @Test fun dimmingHelpsTheTextOnBothThemes() {
        val bright = WallpaperCatalog.luminance(Wallpaper.IMAGE_DUNES, light = false, backgroundLuminance = 0.01)
        assertTrue(WallpaperCatalog.contrast(bright, 0, light = false) < WallpaperCatalog.contrast(bright, 60, light = false))
        val dark = WallpaperCatalog.luminance(Wallpaper.IMAGE_AURORA, light = true, backgroundLuminance = 0.9)
        assertTrue(WallpaperCatalog.contrast(dark, 0, light = true) < WallpaperCatalog.contrast(dark, 70, light = true))
    }

    @Test fun legibilityBandsFollowTheUsualThresholds() {
        assertEquals(WallpaperCatalog.Legibility.EXCELLENT, WallpaperCatalog.legibility(7.0))
        assertEquals(WallpaperCatalog.Legibility.GOOD, WallpaperCatalog.legibility(4.5))
        assertEquals(WallpaperCatalog.Legibility.FAIR, WallpaperCatalog.legibility(3.2))
        assertEquals(WallpaperCatalog.Legibility.LOW, WallpaperCatalog.legibility(1.4))
        // A dark default wallpaper under white text is comfortably readable.
        val dark = WallpaperCatalog.luminance(Wallpaper.NIGHT_ROAD, light = false, backgroundLuminance = 0.01)
        assertTrue(WallpaperCatalog.contrast(dark, 20, light = false) >= 7.0)
    }
}

class WallpaperLooksTest {

    @Test fun oneWallpaperServesBothThemesByDefault() {
        val spec = AppearanceSpec(wallpaper = Wallpaper.IMAGE_BLUE_HOUR, wallpaperDim = 30)
        assertFalse(spec.separateWallpapers)
        assertEquals(spec.wallpaperLook(light = false), spec.wallpaperLook(light = true))
        assertEquals(Wallpaper.IMAGE_BLUE_HOUR, spec.wallpaperLook(light = true).wallpaper)
    }

    @Test fun turningSeparateOnStartsBothFromWhatIsShowing() {
        val spec = AppearanceSpec(wallpaper = Wallpaper.GRADIENT_OCEAN, wallpaperAngle = 90).withSeparateWallpapers(true, editingLight = false)
        assertTrue(spec.separateWallpapers)
        assertEquals(spec.wallpaperLook(false), spec.wallpaperLook(true))
    }

    @Test fun editingTheLightSlotLeavesTheDarkOneAlone() {
        val separate = AppearanceSpec(wallpaper = Wallpaper.IMAGE_AURORA).withSeparateWallpapers(true, editingLight = false)
        val edited = separate.withWallpaperLook(true, separate.wallpaperLook(true).copy(wallpaper = Wallpaper.IMAGE_DUNES, blur = 8))
        assertEquals(Wallpaper.IMAGE_AURORA, edited.wallpaper)
        assertEquals(Wallpaper.IMAGE_AURORA, edited.wallpaperLook(false).wallpaper)
        assertEquals(Wallpaper.IMAGE_DUNES, edited.wallpaperLook(true).wallpaper)
        assertEquals(8, edited.wallpaperLook(true).blur)
        assertEquals(0, edited.wallpaperLook(false).blur)
    }

    @Test fun withoutSeparateWallpapersAnEditAppliesToBothThemes() {
        val edited = AppearanceSpec().withWallpaperLook(true, WallpaperLook(Wallpaper.SOLID_PLUM, dim = 10))
        assertEquals(Wallpaper.SOLID_PLUM, edited.wallpaperLook(false).wallpaper)
        assertEquals(Wallpaper.SOLID_PLUM, edited.wallpaperLook(true).wallpaper)
        assertNull(edited.lightWallpaper)
    }

    @Test fun turningSeparateOffKeepsTheOneYouWereEditing() {
        var spec = AppearanceSpec(wallpaper = Wallpaper.IMAGE_AURORA).withSeparateWallpapers(true, editingLight = false)
        spec = spec.withWallpaperLook(true, spec.wallpaperLook(true).copy(wallpaper = Wallpaper.IMAGE_DUNES))
        val keepLight = spec.withSeparateWallpapers(false, editingLight = true)
        assertEquals(Wallpaper.IMAGE_DUNES, keepLight.wallpaperLook(false).wallpaper)
        assertNull(keepLight.lightWallpaper)
        val keepDark = spec.withSeparateWallpapers(false, editingLight = false)
        assertEquals(Wallpaper.IMAGE_AURORA, keepDark.wallpaperLook(true).wallpaper)
    }

    @Test fun theNewFieldsRoundTripThroughTheCodec() {
        val spec = AppearanceSpec(
            wallpaper = Wallpaper.IMAGE_CITY_LIGHTS, wallpaperBlur = 12, wallpaperAngle = 45,
            phoneCrop = WallpaperCrop(180, 20, 70), carCrop = WallpaperCrop(250, 90, 5),
            lightWallpaper = WallpaperLook(Wallpaper.GRADIENT_MINT, 10, 0, 330, WallpaperCrop(120, 10, 10), WallpaperCrop.FULL)
        )
        assertEquals(spec, AppearanceCodec.decode(AppearanceCodec.encode(spec)))
    }

    @Test fun noLightSlotIsStoredWhenThereIsNone() {
        assertFalse(AppearanceCodec.encode(AppearanceSpec()).keys.any { it.startsWith("lw_") })
    }

    @Test fun brokenValuesFallBackPerField() {
        val d = AppearanceCodec.decode(mapOf("wallpaper_blur" to "99", "wallpaper_angle" to "x", "phone_crop" to "9,9", "car_crop" to "400,-5,50",
            "lw_wallpaper" to "NOT_A_WALLPAPER"))
        assertEquals(WallpaperCatalog.MAX_BLUR, d.wallpaperBlur)
        assertEquals(WallpaperCatalog.DEFAULT_ANGLE, d.wallpaperAngle)
        assertEquals(WallpaperCrop.FULL, d.phoneCrop)
        assertEquals(WallpaperCrop(300, 0, 50), d.carCrop)
        assertNull(d.lightWallpaper)
    }

    @Test fun anOldStoredWallpaperStillLoads() {
        val d = AppearanceCodec.decode(mapOf("wallpaper" to "NIGHT_ROAD", "wallpaper_dim" to "35"))
        assertEquals(Wallpaper.NIGHT_ROAD, d.wallpaper)
        assertEquals(35, d.wallpaperDim)
        assertEquals(WallpaperCrop.FULL, d.phoneCrop)
    }
}
