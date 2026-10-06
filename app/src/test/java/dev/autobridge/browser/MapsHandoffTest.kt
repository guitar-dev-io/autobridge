package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapsHandoffTest {

    @Test
    fun routeEndsAtItsLastStop() {
        assertEquals(
            "Central World",
            MapsHandoff.destinationFromPage(
                "https://www.google.com/maps/dir/Siam+Paragon/Central+World/@13.74,100.53,15z/data=!4m2"
            )
        )
    }

    @Test
    fun placeAndSearchPages() {
        assertEquals(
            "Siam Paragon",
            MapsHandoff.destinationFromPage("https://www.google.com/maps/place/Siam+Paragon/@13.74,100.53,17z")
        )
        assertEquals("ปั๊ม ปตท.", MapsHandoff.destinationFromPage("https://www.google.com/maps/search/%E0%B8%9B%E0%B8%B1%E0%B9%8A%E0%B8%A1+%E0%B8%9B%E0%B8%95%E0%B8%97."))
    }

    @Test
    fun apiDestinationParameter() {
        assertEquals(
            "13.7,100.5",
            MapsHandoff.destinationFromPage("https://www.google.com/maps/dir/?api=1&destination=13.7%2C100.5")
        )
    }

    @Test
    fun startScreenAndOtherSitesHaveNoDestination() {
        assertNull(MapsHandoff.destinationFromPage("https://www.google.com/maps"))
        assertNull(MapsHandoff.destinationFromPage("https://www.google.com/maps/@13.74,100.53,15z"))
        assertNull(MapsHandoff.destinationFromPage("https://m.youtube.com/watch?v=x"))
        assertNull(MapsHandoff.destinationFromPage(null))
    }

    @Test
    fun appLinks() {
        assertEquals("Central World", MapsHandoff.destinationFromLink("google.navigation:q=Central+World"))
        assertEquals("13.7,100.5", MapsHandoff.destinationFromLink("geo:0,0?q=13.7,100.5"))
        assertEquals(
            "Central World",
            MapsHandoff.destinationFromLink(
                "intent://maps/dir/Siam/Central+World#Intent;scheme=https;package=com.google.android.apps.maps;end"
            )
        )
        assertEquals(
            "Central World",
            MapsHandoff.destinationFromLink(
                "intent:?q=Central+World#Intent;scheme=google.navigation;package=com.google.android.apps.maps;end"
            )
        )
    }

    @Test
    fun otherAppsAreNotHandedOff() {
        assertNull(
            MapsHandoff.destinationFromLink("intent://x#Intent;scheme=https;package=com.example.evil;end")
        )
        assertNull(MapsHandoff.destinationFromLink("https://www.google.com/maps/place/X"))
    }

    @Test
    fun navigationUriEncodesTheDestination() {
        assertEquals("google.navigation:q=Central+World", MapsHandoff.navigationUri("Central World"))
    }
}
