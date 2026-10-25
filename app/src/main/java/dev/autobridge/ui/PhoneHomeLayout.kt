package dev.autobridge.ui

import androidx.annotation.StringRes
import dev.autobridge.R
import dev.autobridge.design.HomeLayout
import dev.autobridge.design.IconStyle
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.library.HomeSection
import dev.autobridge.remote.CommandHistoryStore
import dev.autobridge.remote.CommandType

/**
 * What the phone Home shows, as data. Home is a launcher now, not a grid of every feature:
 * six Quick Launch tiles, with the rest of [HomeSection.phoneSections] grouped behind them.
 *
 * The car keeps its own full grid ([HomeSection.carSections]); nothing here changes it.
 * Pure Kotlin so "every phone section still has a way in" is a unit test, not a hope.
 */
object PhoneHomeLayout {

    /** The six Quick Launch tiles, in order. Titles are string resources so Home follows the
     *  selected app language, same as every other tile/label on the page. */
    enum class Tile(@StringRes val titleRes: Int) {
        BROWSER(R.string.home_tile_browser),
        YOUTUBE(R.string.home_tile_youtube),
        MUSIC(R.string.home_tile_music),
        RADIO(R.string.home_tile_radio),
        TV(R.string.home_tile_tv),
        MORE(R.string.home_tile_more)
    }

    /**
     * Tiles that open a single section directly. Radio and TV are a tile each, so the radio is one
     * tap from Home; Favorites, which those two used to share a row with, is under More.
     */
    val directSection: Map<Tile, HomeSection> = mapOf(
        Tile.BROWSER to HomeSection.WEB,
        Tile.YOUTUBE to HomeSection.YOUTUBE,
        Tile.RADIO to HomeSection.RADIO,
        Tile.TV to HomeSection.TV
    )

    /** Tiles that open a short chooser page of related sections. */
    val musicSections: List<HomeSection> = listOf(HomeSection.YOUTUBE_MUSIC, HomeSection.PLAYLISTS)

    /**
     * Everything else the phone offers, behind More: local media (Folders, Gallery), Streaming
     * ( and other video sites), saved channels & pages, Weather, Mirror. Derived
     * rather than listed, so a section added to [HomeSection] later lands here instead of
     * disappearing from the phone.
     */
    val moreSections: List<HomeSection>
        get() = HomeSection.phoneSections.filterNot {
            it in directSection.values || it in musicSections
        }

    /** Every section reachable from Home, for the coverage test. */
    val reachableSections: Set<HomeSection>
        get() = (directSection.values + musicSections + moreSections).toSet()

    /** One "Recent" chip under Send to Car: what was sent, re-sendable as the same command. */
    data class RecentSend(val label: String, val type: CommandType, val payload: String)

    private val SENDABLE = setOf(
        CommandType.OPEN_URL,
        CommandType.SEARCH_WEB,
        CommandType.SEND_TEXT_TO_SCREEN,
        CommandType.OPEN_AGENT
    )

    /**
     * Recent URLs / searches / text, newest first, from the one command history
     * ([CommandHistoryStore]); no second recents list is kept.
     */
    fun recentSends(entries: List<CommandHistoryStore.Entry>, limit: Int = 6): List<RecentSend> =
        entries.asSequence()
            .filter { it.type in SENDABLE && !it.payload.isNullOrBlank() }
            .distinctBy { it.payload!!.trim().lowercase() }
            .take(limit)
            .map { RecentSend(chipLabel(it.type, it.payload!!.trim()), it.type, it.payload.trim()) }
            .toList()

    private fun chipLabel(type: CommandType, payload: String): String {
        if (type == CommandType.OPEN_URL) {
            val host = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)").find(payload)?.groupValues?.get(1)
            if (host != null) return host.removePrefix("www.").removePrefix("m.")
        }
        return if (payload.length > 24) payload.take(23) + "…" else payload
    }

    /**
     * Every place Home's three layouts (Settings > Appearance > Home layout) can show, in the
     * design's order. The six [Tile]s plus the three the redesign puts on Home: Mirror, My Car and
     * Displays (the Duo / Multi-app screen, only when that build has it). [hueDark] / [hueLight] are
     * the section colour an icon chip carries in the Tinted icon style.
     */
    enum class Item(val hueDark: Int, val hueLight: Int) {
        STREAMING(0xFFFF8A8A.toInt(), 0xFFB3282E.toInt()),
        BROWSER(0xFF70ACFF.toInt(), 0xFF1F63C2.toInt()),
        MUSIC(0xFFAF80F8.toInt(), 0xFF6A3FC0.toInt()),
        RADIO(0xFFFFC46B.toInt(), 0xFF8F5800.toInt()),
        TV(0xFFFF7A7A.toInt(), 0xFFB3282E.toInt()),
        YOUTUBE(0xFFF0848F.toInt(), 0xFFA8304A.toInt()),
        MIRROR(0xFF7CE1CB.toInt(), 0xFF0D7563.toInt()),
        MY_CAR(0xFF86E3AF.toInt(), 0xFF1E7A45.toInt()),
        DISPLAYS(0xFF9DC3F7.toInt(), 0xFF2F62A8.toInt()),
        MORE(0xFFD8E3F7.toInt(), 0xFF4A5566.toInt());

        fun hue(light: Boolean): Int = if (light) hueLight else hueDark
    }

    fun itemFor(tile: Tile): Item = when (tile) {
        Tile.BROWSER -> Item.BROWSER
        Tile.YOUTUBE -> Item.YOUTUBE
        Tile.MUSIC -> Item.MUSIC
        Tile.RADIO -> Item.RADIO
        Tile.TV -> Item.TV
        Tile.MORE -> Item.MORE
    }

    /** Classic grid and Compact: every item, Displays only when the build has the Duo screen. */
    fun allItems(displaysAvailable: Boolean): List<Item> =
        Item.entries.filter { it != Item.DISPLAYS || displaysAvailable }

    /**
     * Dashboard's 2x2 cards: Streaming leads (it starts Netflix and the other video services),
     * then Radio, TV and My Car. Mirror and Displays (the Duo screen) are in [dashboardQuickLaunch]
     * and the Android Auto card above the grid, so no card is spent on them.
     */
    @Suppress("UNUSED_PARAMETER")
    fun dashboardCards(displaysAvailable: Boolean): List<Item> =
        listOf(Item.STREAMING, Item.RADIO, Item.TV, Item.MY_CAR)

    /** Dashboard's quick-launch row under the cards: what the cards do not already open. */
    fun dashboardQuickLaunch(displaysAvailable: Boolean): List<Item> =
        buildList {
            add(Item.BROWSER)
            add(Item.YOUTUBE)
            add(Item.MIRROR)
            if (displaysAvailable) add(Item.DISPLAYS)
            add(Item.MUSIC)
            add(Item.MORE)
        }

    /** The Android font scale from which the grid drops to two columns and labels truncate. */
    const val LARGE_TEXT_SCALE = 1.3f

    /** Large text: [scale] is the effective font scale ([android.content.res.Configuration.fontScale]). */
    fun isLargeText(scale: Float): Boolean = scale >= LARGE_TEXT_SCALE - 0.005f

    fun gridColumns(scale: Float): Int = if (isLargeText(scale)) 2 else 3

    /** Classic tile height in dp: a little shorter when two columns make each tile wider. */
    fun tileHeightDp(scale: Float): Int = if (isLargeText(scale)) 96 else 108

    /** Whether [layout] shows the bottom mini player; Dashboard's hero card is the player there. */
    fun showsMiniPlayer(layout: HomeLayout): Boolean = layout != HomeLayout.DASHBOARD

    /** An icon chip's fill and glyph colour, as ARGB. */
    data class ChipColors(val fill: Int, val glyph: Int)

    /**
     * Settings > Appearance > Icon style: Tinted keeps each section's hue (a soft fill of it under
     * the hue itself), Accent fills every chip with the accent, Mono uses the raised surface.
     */
    fun chipColors(item: Item, style: IconStyle, palette: ThemePalette): ChipColors = when (style) {
        IconStyle.TINTED -> {
            val hue = item.hue(palette.isLight)
            ChipColors(ThemeResolver.withAlpha(hue, if (palette.isLight) 0x1F else 0x29), hue)
        }
        IconStyle.ACCENT -> ChipColors(palette.accent, palette.onAccent)
        IconStyle.MONO -> ChipColors(palette.raised, palette.text)
    }
}
