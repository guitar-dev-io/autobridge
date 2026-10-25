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
            listOf(Item.STREAMING, Item.BROWSER, Item.MUSIC, Item.RADIO, Item.TV, Item.YOUTUBE, Item.MIRROR, Item.MY_CAR, Item.DISPLAYS, Item.MORE),
            PhoneHomeLayout.allItems(displaysAvailable = true)
        )
        assertFalse(Item.DISPLAYS in PhoneHomeLayout.allItems(displaysAvailable = false))
    }

    @Test fun dashboardCardsAreStreamingRadioTvMyCar() {
        for (displays in listOf(true, false)) {
            assertEquals(listOf(Item.STREAMING, Item.RADIO, Item.TV, Item.MY_CAR), PhoneHomeLayout.dashboardCards(displays))
        }
        assertEquals(
            listOf(Item.BROWSER, Item.YOUTUBE, Item.MIRROR, Item.DISPLAYS, Item.MUSIC, Item.MORE),
            PhoneHomeLayout.dashboardQuickLaunch(true)
        )
    }

    /** Without the Duo screen the quick row simply has no Displays entry, and nothing is shown twice. */
    @Test fun dashboardWithoutDisplaysHasNoDisplaysEntry() {
        val cards = PhoneHomeLayout.dashboardCards(false)
        val quick = PhoneHomeLayout.dashboardQuickLaunch(false)
        assertEquals(listOf(Item.BROWSER, Item.YOUTUBE, Item.MIRROR, Item.MUSIC, Item.MORE), quick)
        assertFalse(Item.DISPLAYS in cards + quick)
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

    @Test fun dimIsBlackOnDarkWhiteOnLightAndCapped() {
        assertEquals(0, HomeWallpaper.dimColor(Wallpaper.NONE, 50, light = false))
        assertEquals(0x33000000, HomeWallpaper.dimColor(Wallpaper.GLOW, 20, light = false))
        assertEquals(0x33FFFFFF, HomeWallpaper.dimColor(Wallpaper.GLOW, 20, light = true))
        assertEquals(HomeWallpaper.dimColor(Wallpaper.GLOW, 70, false), HomeWallpaper.dimColor(Wallpaper.GLOW, 95, false))
    }
}
