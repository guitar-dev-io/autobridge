package dev.autobridge.ui.appearance

import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.Corners
import dev.autobridge.design.IconStyle
import dev.autobridge.design.TextStep
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.WallpaperTarget
import dev.autobridge.ui.appearance.AppearanceModel.Category
import dev.autobridge.ui.appearance.AppearanceModel.Page
import dev.autobridge.ui.appearance.AppearanceModel.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceModelTest {

    @Test fun everySubPageGoesBackToTheMainPageWhichLeaves() {
        assertNull(AppearanceModel.parent(Page.MAIN))
        Page.entries.filter { it != Page.MAIN }.forEach { assertEquals(Page.MAIN, AppearanceModel.parent(it)) }
    }

    @Test fun defaultSizesAreTheBaseSizes() {
        val rows = AppearanceModel.sizeRows(AppearanceSpec.DEFAULT, systemScale = 1f)
        assertEquals(TextStyle.entries.size, rows.size)
        rows.forEach {
            assertEquals(it.phoneBase, it.phone)
            assertEquals(it.carBase, it.car)
        }
    }

    @Test fun largerTextGrowsBodyFullyAndDisplayByHalf() {
        val rows = AppearanceModel.sizeRows(AppearanceSpec(textStep = TextStep.LARGER), systemScale = 1f)
            .associateBy { it.style }
        // Body 15 x 1.3 = 19.5; Display 24 is between 15 and 30, weight 0.7: 24 x 1.21 = 29.
        assertEquals(20, rows.getValue(TextStyle.BODY).phone)
        assertEquals(29, rows.getValue(TextStyle.DISPLAY).phone)
        // Car body 20 sits at weight 0.83: 20 x 1.25 = 25.
        assertEquals(25, rows.getValue(TextStyle.BODY).car)
    }

    @Test fun carTextStopsAt130Percent() {
        val max = AppearanceModel.sizeRows(AppearanceSpec(textStep = TextStep.MAX), 1f).associateBy { it.style }
        val larger = AppearanceModel.sizeRows(AppearanceSpec(textStep = TextStep.LARGER), 1f).associateBy { it.style }
        TextStyle.entries.forEach { assertEquals(larger.getValue(it).car, max.getValue(it).car) }
        assertTrue(max.getValue(TextStyle.BODY).phone > larger.getValue(TextStyle.BODY).phone)
    }

    @Test fun carKeepsDefaultSizesWhenItDoesNotShareTheLook() {
        val rows = AppearanceModel.sizeRows(AppearanceSpec(textStep = TextStep.HUGE, carSameLook = false), 1f)
        rows.forEach { assertEquals(it.carBase, it.car) }
    }

    @Test fun followAndroidUsesTheSystemScale() {
        val spec = AppearanceSpec(followSystemText = true, textStep = TextStep.SMALL)
        val body = AppearanceModel.sizeRows(spec, systemScale = 1.3f).first { it.style == TextStyle.BODY }
        assertEquals(20, body.phone)
        assertTrue(AppearanceModel.showsLargeTextNote(spec, 1.3f))
    }

    @Test fun largeTextNoteShowsFrom130Percent() {
        assertFalse(AppearanceModel.showsLargeTextNote(AppearanceSpec(textStep = TextStep.LARGE), 1f))
        assertTrue(AppearanceModel.showsLargeTextNote(AppearanceSpec(textStep = TextStep.LARGER), 1f))
        assertTrue(AppearanceModel.showsLargeTextNote(AppearanceSpec(textStep = TextStep.MAX), 1f))
    }

    @Test fun iconChipsFollowTheStyle() {
        val dark = ThemeResolver.resolve(AppearanceSpec.DEFAULT)
        assertEquals(AppearanceModel.ChipColors(dark.accent, dark.onAccent), AppearanceModel.chip(IconStyle.ACCENT, Category.RADIO, dark))
        assertEquals(AppearanceModel.ChipColors(dark.raised, dark.text), AppearanceModel.chip(IconStyle.MONO, Category.RADIO, dark))
        val tinted = AppearanceModel.chip(IconStyle.TINTED, Category.RADIO, dark)
        assertEquals(Category.RADIO.onDark, tinted.glyph)
        assertEquals(0x29, tinted.fill ushr 24)

        val light = ThemeResolver.resolve(AppearanceSpec(mode = ThemeMode.LIGHT))
        val lightTinted = AppearanceModel.chip(IconStyle.TINTED, Category.TV, light)
        assertEquals(Category.TV.onLight, lightTinted.glyph)
        assertEquals(0x1F, lightTinted.fill ushr 24)
    }

    @Test fun smallRadiiShrinkWithTheCorners() {
        val soft = AppearanceModel.smallRadii(Corners.SOFT)
        val sharp = AppearanceModel.smallRadii(Corners.SHARP)
        assertTrue(soft.mini > sharp.mini && soft.chipSmall > sharp.chipSmall && soft.miniChip > sharp.miniChip)
    }

    @Test fun dimSliderStepsByFiveUpTo70() {
        assertEquals(14, AppearanceModel.DIM_STEPS)
        assertEquals(0, AppearanceModel.progressToDim(0))
        assertEquals(70, AppearanceModel.progressToDim(14))
        assertEquals(70, AppearanceModel.progressToDim(99))
        assertEquals(4, AppearanceModel.dimToProgress(20))
        // A stored value off the 5-step grid lands on the nearest step.
        assertEquals(4, AppearanceModel.dimToProgress(22))
        assertEquals(5, AppearanceModel.dimToProgress(23))
        (0..AppearanceModel.DIM_STEPS).forEach { assertEquals(it, AppearanceModel.dimToProgress(AppearanceModel.progressToDim(it))) }
    }

    @Test fun wallpaperTargets() {
        assertTrue(AppearanceModel.wallpaperOnPhone(AppearanceSpec(wallpaperTarget = WallpaperTarget.BOTH)))
        assertTrue(AppearanceModel.wallpaperOnCar(AppearanceSpec(wallpaperTarget = WallpaperTarget.BOTH)))
        assertFalse(AppearanceModel.wallpaperOnPhone(AppearanceSpec(wallpaperTarget = WallpaperTarget.CAR)))
        assertFalse(AppearanceModel.wallpaperOnCar(AppearanceSpec(wallpaperTarget = WallpaperTarget.PHONE)))
    }

    @Test fun carSpecIsTheDefaultWhenNotShared() {
        val spec = AppearanceSpec(mode = ThemeMode.LIGHT, carSameLook = false)
        assertEquals(AppearanceSpec.DEFAULT, AppearanceModel.carSpec(spec))
        val shared = spec.copy(carSameLook = true)
        assertEquals(shared, AppearanceModel.carSpec(shared))
    }

    @Test fun photosShrinkToTheLongSideAndNeverGrow() {
        assertEquals(1600 to 1200, AppearanceModel.fitWithin(4000, 3000))
        assertEquals(900 to 1600, AppearanceModel.fitWithin(2700, 4800))
        assertEquals(800 to 600, AppearanceModel.fitWithin(800, 600))
        assertEquals(1600 to 1, AppearanceModel.fitWithin(16000, 2))
    }

    @Test fun clockLabels() {
        assertEquals("18:30", AppearanceModel.clock(18 * 60 + 30))
        assertEquals("06:30", AppearanceModel.clock(6 * 60 + 30))
        assertEquals("00:00", AppearanceModel.clock(0))
        assertEquals("23:59", AppearanceModel.clock(24 * 60 - 1))
        assertEquals("00:05", AppearanceModel.clock(24 * 60 + 5))
    }

    @Test fun blurZoomAndAngleSlidersCoverTheirRangesInSteps() {
        assertEquals(12, AppearanceModel.BLUR_STEPS)
        assertEquals(0, AppearanceModel.progressToBlur(0))
        assertEquals(24, AppearanceModel.progressToBlur(99))
        assertEquals(6, AppearanceModel.blurToProgress(12))
        assertEquals(100, AppearanceModel.progressToZoom(0))
        assertEquals(300, AppearanceModel.progressToZoom(AppearanceModel.ZOOM_STEPS))
        assertEquals(20, AppearanceModel.zoomToProgress(200))
        assertEquals(24, AppearanceModel.ANGLE_STEPS)
        assertEquals(165, AppearanceModel.progressToAngle(AppearanceModel.angleToProgress(160)))
        assertEquals(360, AppearanceModel.progressToAngle(99))
    }

    @Test fun thePageEditsTheThemeOnScreenUnlessTheWallpapersAreSeparate() {
        val one = AppearanceSpec()
        assertTrue(AppearanceModel.editedLight(one, shownLight = true, chosenLight = false))
        assertFalse(AppearanceModel.editedLight(one, shownLight = false, chosenLight = true))
        val two = one.withSeparateWallpapers(true, editingLight = false)
        assertTrue(AppearanceModel.editedLight(two, shownLight = false, chosenLight = true))
        assertFalse(AppearanceModel.editedLight(two, shownLight = true, chosenLight = false))
        assertTrue(AppearanceModel.editedLight(two, shownLight = true, chosenLight = null))
    }

    @Test fun theCropEditorFollowsTheScreenTheWallpaperIsOn() {
        assertEquals(WallDevice.PHONE, AppearanceModel.cropDevice(AppearanceSpec(wallpaperTarget = WallpaperTarget.PHONE), pickedCar = true))
        assertEquals(WallDevice.CAR, AppearanceModel.cropDevice(AppearanceSpec(wallpaperTarget = WallpaperTarget.CAR), pickedCar = false))
        assertEquals(WallDevice.CAR, AppearanceModel.cropDevice(AppearanceSpec(wallpaperTarget = WallpaperTarget.BOTH), pickedCar = true))
        assertEquals(WallDevice.PHONE, AppearanceModel.cropDevice(AppearanceSpec(wallpaperTarget = WallpaperTarget.BOTH), pickedCar = false))
    }
}
