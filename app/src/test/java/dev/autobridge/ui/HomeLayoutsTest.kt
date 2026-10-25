package dev.autobridge.ui

import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.HomeLayout
import dev.autobridge.design.IconStyle
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperTarget
import dev.autobridge.ui.PhoneHomeLayout.Item
import dev.autobridge.ui.home.HomeWallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The redesign's three Home layouts, as data (see [PhoneHomeLayout.Item]). */
class HomeLayoutsTest {

    @Test fun everyQuickLaunchTileHasAnItem() {
        assertEquals(
            PhoneHomeLayout.Tile.entries.size,
            PhoneHomeLayout.Tile.entries.map(PhoneHomeLayout::itemFor).toSet().size
        )
    }

    @Test fun classicAndCompactShowEveryItemInTheDesignOrder() {
        assertEquals(
            listOf(Item.BROWSER, Item.MUSIC, Item.RADIO, Item.TV, Item.YOUTUBE, Item.MIRROR, Item.MY_CAR, Item.DISPLAYS, Item.MORE),
            PhoneHomeLayout.allItems(displaysAvailable = true)
        )
        assertFalse(Item.DISPLAYS in PhoneHomeLayout.allItems(displaysAvailable = false))
    }

    @Test fun dashboardCardsAreRadioTvMyCarDisplays() {
        assertEquals(listOf(Item.RADIO, Item.TV, Item.MY_CAR, Item.DISPLAYS), PhoneHomeLayout.dashboardCards(true))
        assertEquals(listOf(Item.BROWSER, Item.YOUTUBE, Item.MIRROR, Item.MUSIC, Item.MORE), PhoneHomeLayout.dashboardQuickLaunch(true))
    }

    /** Without the Duo screen Mirror fills the fourth card, and is not shown twice. */
    @Test fun dashboardWithoutDisplaysMovesMirrorIntoTheCards() {
        val cards = PhoneHomeLayout.dashboardCards(false)
        val quick = PhoneHomeLayout.dashboardQuickLaunch(false)
        assertEquals(Item.MIRROR, cards.last())
        assertFalse(Item.MIRROR in quick)
        assertTrue((cards + quick).groupBy { it }.values.all { it.size == 1 })
    }

    /** Every place stays reachable in each layout (Dashboard spreads it over cards and the row). */
    @Test fun noLayoutDropsAPlace() {
        for (displays in listOf(true, false)) {
            val all = PhoneHomeLayout.allItems(displays).toSet()
            assertEquals(all, (PhoneHomeLayout.dashboardCards(displays) + PhoneHomeLayout.dashboardQuickLaunch(displays)).toSet())
        }
    }

    @Test fun gridDropsToTwoColumnsFrom130PercentText() {
        assertEquals(3, PhoneHomeLayout.gridColumns(1f))
        assertEquals(3, PhoneHomeLayout.gridColumns(1.15f))
        assertEquals(2, PhoneHomeLayout.gridColumns(1.3f))
        // Configuration.fontScale can arrive as 1.2999999.
        assertEquals(2, PhoneHomeLayout.gridColumns(1.2999999f))
        assertEquals(2, PhoneHomeLayout.gridColumns(1.5f))
        assertEquals(96, PhoneHomeLayout.tileHeightDp(1.5f))
        assertEquals(108, PhoneHomeLayout.tileHeightDp(1f))
    }

    @Test fun miniPlayerHidesOnlyOnDashboard() {
        assertFalse(PhoneHomeLayout.showsMiniPlayer(HomeLayout.DASHBOARD))
        assertTrue(PhoneHomeLayout.showsMiniPlayer(HomeLayout.CLASSIC))
        assertTrue(PhoneHomeLayout.showsMiniPlayer(HomeLayout.COMPACT))
    }

    @Test fun chipColorsFollowTheIconStyle() {
        val dark = ThemeResolver.resolve(AppearanceSpec.DEFAULT)
        val light = ThemeResolver.resolve(AppearanceSpec(mode = ThemeMode.LIGHT))

        val tinted = PhoneHomeLayout.chipColors(Item.RADIO, IconStyle.TINTED, dark)
        assertEquals(Item.RADIO.hueDark, tinted.glyph)
        assertEquals(ThemeResolver.withAlpha(Item.RADIO.hueDark, 0x29), tinted.fill)
        assertEquals(Item.RADIO.hueLight, PhoneHomeLayout.chipColors(Item.RADIO, IconStyle.TINTED, light).glyph)

        val accent = PhoneHomeLayout.chipColors(Item.TV, IconStyle.ACCENT, light)
        assertEquals(light.accent, accent.fill)
        assertEquals(light.onAccent, accent.glyph)

        val mono = PhoneHomeLayout.chipColors(Item.TV, IconStyle.MONO, dark)
        assertEquals(dark.raised, mono.fill)
        assertEquals(dark.text, mono.glyph)
    }

    @Test fun wallpaperIsSkippedWhenAimedAtTheCarOnly() {
        assertEquals(Wallpaper.GLOW, HomeWallpaper.forPhone(AppearanceSpec(wallpaperTarget = WallpaperTarget.PHONE), false))
        assertEquals(Wallpaper.GLOW, HomeWallpaper.forPhone(AppearanceSpec(wallpaperTarget = WallpaperTarget.BOTH), false))
        assertEquals(Wallpaper.NONE, HomeWallpaper.forPhone(AppearanceSpec(wallpaperTarget = WallpaperTarget.CAR), false))
    }

    @Test fun missingCustomPhotoFallsBackToNone() {
        val custom = AppearanceSpec(wallpaper = Wallpaper.CUSTOM)
        assertEquals(Wallpaper.NONE, HomeWallpaper.forPhone(custom, customExists = false))
        assertEquals(Wallpaper.CUSTOM, HomeWallpaper.forPhone(custom, customExists = true))
    }

    @Test fun theLightThemeUsesItsOwnWallpaperWhenTheyAreSeparate() {
        val spec = AppearanceSpec(wallpaper = Wallpaper.IMAGE_AURORA)
            .withSeparateWallpapers(true, editingLight = false)
            .let { it.withWallpaperLook(true, it.wallpaperLook(true).copy(wallpaper = Wallpaper.IMAGE_DUNES, dim = 5, blur = 6)) }
        assertEquals(Wallpaper.IMAGE_AURORA, HomeWallpaper.forPhone(spec, false, light = false))
        val light = HomeWallpaper.lookForPhone(spec, customExists = false, light = true)
        assertEquals(Wallpaper.IMAGE_DUNES, light.wallpaper)
        assertEquals(5, light.dim)
        assertEquals(6, light.blur)
    }

    @Test fun theLookKeepsItsSettingsEvenWhenTheWallpaperFallsBack() {
        val spec = AppearanceSpec(wallpaper = Wallpaper.CUSTOM, wallpaperDim = 40, wallpaperBlur = 10)
        val look = HomeWallpaper.lookForPhone(spec, customExists = false, light = false)
        assertEquals(Wallpaper.NONE, look.wallpaper)
        assertEquals(40, look.dim)
        assertEquals(Wallpaper.NONE, HomeWallpaper.lookForPhone(AppearanceSpec(wallpaperTarget = WallpaperTarget.CAR), true, false).wallpaper)
    }
}
