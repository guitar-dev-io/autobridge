package dev.autobridge.car

import dev.autobridge.iptv.RadioCategory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A host refuses a TabTemplate with more than four tabs ("There cannot be more than 4 tabs added,
 * found 5") by throwing from build(), which took the whole app down when Radio opened on the car.
 */
class CarRadioHomeTabsTest {
    @Test fun noMoreThanFourTabs() = assertTrue(CarRadioHomeScreen.TABS.size <= 4)

    @Test fun everyCategoryIsStillReachable() {
        // The one without a tab is the Podcasts cell inside In Country.
        val missing = RadioCategory.values().toSet() - CarRadioHomeScreen.TABS.toSet()
        assertTrue("Without a tab: $missing", missing == setOf(RadioCategory.PODCASTS))
    }
}
