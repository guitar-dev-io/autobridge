package dev.autobridge.ui

import dev.autobridge.R
import dev.autobridge.library.HomeSection
import dev.autobridge.remote.CommandHistoryStore
import dev.autobridge.remote.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHomeLayoutTest {

    @Test fun quickLaunchIsTheSixTilesFromTheMock() {
        assertEquals(
            listOf(
                R.string.home_tile_browser,
                R.string.home_tile_youtube,
                R.string.home_tile_music,
                R.string.home_tile_tv_radio,
                R.string.home_tile_favorites,
                R.string.home_tile_more
            ),
            PhoneHomeLayout.Tile.entries.map { it.titleRes }
        )
    }

    /** Nothing the old phone grid offered may lose its entry point on the new Home. */
    @Test fun everyPhoneSectionIsStillReachableFromHome() {
        val missing = HomeSection.phoneSections.toSet() - PhoneHomeLayout.reachableSections
        assertTrue("Unreachable from Home: $missing", missing.isEmpty())
    }

    @Test fun groupsMergeTheExpectedSections() {
        assertEquals(listOf(HomeSection.YOUTUBE_MUSIC, HomeSection.PLAYLISTS), PhoneHomeLayout.musicSections)
        assertEquals(listOf(HomeSection.TV, HomeSection.RADIO), PhoneHomeLayout.tvRadioSections)
        val more = PhoneHomeLayout.moreSections
        listOf(HomeSection.FOLDERS, HomeSection.GALLERY, HomeSection.MIRROR, HomeSection.WEATHER).forEach {
            assertTrue("$it should be under More", it in more)
        }
        assertTrue(PhoneHomeLayout.reachableSections.none { it == HomeSection.SETTINGS || it == HomeSection.REMOTE })
    }

    @Test fun noSectionAppearsInTwoPlaces() {
        val all = PhoneHomeLayout.directSection.values.toList() + PhoneHomeLayout.musicSections +
            PhoneHomeLayout.tvRadioSections + PhoneHomeLayout.moreSections
        assertEquals(all.size, all.toSet().size)
    }

    private fun entry(type: CommandType, payload: String?, ts: Long) =
        CommandHistoryStore.Entry("id$ts", type, payload, "label", true, ts)

    @Test fun recentSendsComeFromHistoryNewestFirstAndDeduplicated() {
        val history = listOf(
            entry(CommandType.OPEN_URL, "https://www.youtube.com/watch?v=1", 5),
            entry(CommandType.RELOAD, null, 4),
            entry(CommandType.SEARCH_WEB, "thai food", 3),
            entry(CommandType.OPEN_URL, "https://www.youtube.com/watch?v=1", 2),
            entry(CommandType.SEND_TEXT_TO_SCREEN, "hello car", 1)
        )
        val recents = PhoneHomeLayout.recentSends(history)
        assertEquals(listOf("youtube.com", "thai food", "hello car"), recents.map { it.label })
        assertEquals(CommandType.OPEN_URL, recents.first().type)
        assertEquals("https://www.youtube.com/watch?v=1", recents.first().payload)
    }

    @Test fun recentSendsAreCapped() {
        val history = (1..20).map { entry(CommandType.SEARCH_WEB, "q$it", it.toLong()) }
        assertEquals(6, PhoneHomeLayout.recentSends(history).size)
    }
}
