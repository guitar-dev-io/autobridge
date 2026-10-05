package dev.autobridge.ui

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

    /** The six Quick Launch tiles, in order. */
    enum class Tile(val title: String) {
        BROWSER("Browser"),
        YOUTUBE("YouTube"),
        MUSIC("Music"),
        TV_RADIO("TV / Radio"),
        FAVORITES("Favorites"),
        MORE("More")
    }

    /** Tiles that open a single section directly. */
    val directSection: Map<Tile, HomeSection> = mapOf(
        Tile.BROWSER to HomeSection.WEB,
        Tile.YOUTUBE to HomeSection.YOUTUBE
    )

    /** Tiles that open a short chooser page of related sections. */
    val musicSections: List<HomeSection> = listOf(HomeSection.YOUTUBE_MUSIC, HomeSection.PLAYLISTS)
    val tvRadioSections: List<HomeSection> = listOf(HomeSection.TV, HomeSection.RADIO)

    /**
     * Everything else the phone offers, behind More: local media (Folders, Gallery), Streaming
     * ( and other video sites), saved channels & pages, Weather, Mirror. Derived
     * rather than listed, so a section added to [HomeSection] later lands here instead of
     * disappearing from the phone.
     */
    val moreSections: List<HomeSection>
        get() = HomeSection.phoneSections.filterNot {
            it in directSection.values || it in musicSections || it in tvRadioSections
        }

    /** Every section reachable from Home, for the coverage test. */
    val reachableSections: Set<HomeSection>
        get() = (directSection.values + musicSections + tvRadioSections + moreSections).toSet()

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
}
