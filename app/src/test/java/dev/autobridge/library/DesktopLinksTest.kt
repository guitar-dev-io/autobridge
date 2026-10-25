package dev.autobridge.library

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which sites go to the desktop browser, and what happens where there is no desktop browser. */
class DesktopLinksTest {
    @After fun restore() { DesktopLinks.opener = null }

    private fun context(): Context = ContextWrapper(null)

    private fun url(title: String) = StreamingLinks.all.first { it.title == title }.url

    @Test fun theSitesThatMayNotPlayGoToTheDesktopBrowser() {
        listOf("Netflix", "Disney+", "Prime Video", "HBO Max", "Viu", "WeTV", "iQIYI").forEach {
            assertTrue("$it should go to the desktop browser", DesktopLinks.wantsDesktop(url(it)))
        }
    }

    @Test fun theSitesThatPlayStayInTheBuiltInBrowser() {
        listOf("YouTube", "TikTok", "Vimeo", "Twitch", "SoundCloud").forEach {
            assertFalse("$it should not", DesktopLinks.wantsDesktop(url(it)))
        }
    }

    @Test fun anAddressTheCatalogDoesNotKnowIsNotRerouted() {
        assertFalse(DesktopLinks.wantsDesktop("https://example.org/watch"))
    }

    @Test fun withoutAnOpenerNothingIsHandedOver() {
        val context = context()
        assertFalse(DesktopLinks.open(context, "https://www.netflix.com"))
    }

    @Test fun anOpenerIsGivenTheAddressAndSaysWhetherItTookIt() {
        val context = context()
        var got: String? = null
        DesktopLinks.opener = { _, u -> got = u; true }
        assertTrue(DesktopLinks.open(context, "https://www.netflix.com"))
        assertEquals("https://www.netflix.com", got)
        DesktopLinks.opener = { _, _ -> false }
        assertFalse(DesktopLinks.open(context, "https://www.netflix.com"))
    }
}
