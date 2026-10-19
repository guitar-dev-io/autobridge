package dev.autobridge.car

import dev.autobridge.iptv.RadioCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The car radio grid draws its own tabs (it used to be a TabTemplate, which a host caps at four),
 * so every category, Podcasts included, is a tab of its own again, in the radio design's order.
 */
class CarRadioHomeTabsTest {
    @Test fun everyCategoryHasATab() =
        assertEquals(RadioCategory.values().toSet(), CarRadioHomeScreen.TABS.toSet())

    @Test fun tabsFollowTheDesignOrder() = assertEquals(
        listOf(
            RadioCategory.IN_COUNTRY, RadioCategory.LOCAL, RadioCategory.WORLD,
            RadioCategory.PODCASTS, RadioCategory.FAVORITES,
        ),
        CarRadioHomeScreen.TABS,
    )
}
