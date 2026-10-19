package dev.autobridge.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarStartPageTest {
    private val texts = CarStartPage.Texts(
        title = "AutoBridge Browser",
        subtitle = "Quick access",
        searchHint = "Search or enter address",
        go = "Go",
        quickLinks = "Quick Links",
        supportTitle = "Buy me a snack",
        supportMessage = "Thanks",
        supportAction = "Support the developer",
        desktop = "Open browser",
    )

    @Test fun theStartPageHasTheSearchFormSupportCardAndQuickLinks() {
        val link = CarStartPage.QuickLink("YouTube Music", "https://music.youtube.com/", "music.youtube.com", "YM", 0xE65A3B)
        val html = CarStartPage.startHtml(texts, listOf(link), desktopAvailable = false)
        assertTrue(html.contains("<form class=\"search\" action=\"https://${CarStartPage.HOST}/autobridge/go\""))
        assertTrue(html.contains("name=\"q\""))
        assertTrue(html.contains("Buy me a snack"))
        assertTrue(html.contains("href=\"https://music.youtube.com/\""))
        assertTrue(html.contains("background:#E65A3B"))
        assertFalse(html.contains("/autobridge/desktop"))
    }

    @Test fun theDesktopBrowserLinkAppearsOnlyWhenOneCanOpen() {
        val html = CarStartPage.startHtml(texts, emptyList(), desktopAvailable = true)
        assertTrue(html.contains("href=\"https://${CarStartPage.HOST}/autobridge/desktop\""))
        assertTrue(CarStartPage.isDesktopLink("https://${CarStartPage.HOST}/autobridge/desktop"))
        // No saved sites: no empty "Quick Links" heading.
        assertFalse(html.contains("Quick Links"))
    }

    @Test fun savedTitlesCannotBreakOutOfTheMarkup() {
        val link = CarStartPage.QuickLink("<script>x</script>", "https://a.example/?a=1&b=\"2\"", "a.example", "S", 0)
        val html = CarStartPage.startHtml(texts, listOf(link), desktopAvailable = false)
        assertFalse(html.contains("<script>x"))
        assertTrue(html.contains("&lt;script&gt;x&lt;/script&gt;"))
        assertTrue(html.contains("href=\"https://a.example/?a=1&amp;b=&quot;2&quot;\""))
    }

    @Test fun aSearchRedirectQuotesItsTarget() {
        val html = CarStartPage.redirectHtml("https://www.google.com/search?q=\"</script>")
        // Only the page's own closing tag: the query's "</script>" is escaped inside the string.
        assertEquals(1, Regex("</script>").findAll(html).count())
        assertTrue(html.contains("location.replace("))
    }

    @Test fun theDesktopPageSearchesFromThePageAndLinksBySiblingName() {
        val html = CarStartPage.startHtml(texts, emptyList(), desktopAvailable = false,
            searchPrefix = "https://www.google.com/search?q=", supportHref = "support.html")
        assertTrue(html.contains("onsubmit=\"return go(this)\""))
        assertTrue(html.contains("encodeURIComponent(q)"))
        assertTrue(html.contains("href=\"support.html\""))
        val support = CarStartPage.supportHtml(texts, emptyList(), backHref = "index.html")
        assertTrue(support.contains("href=\"index.html\""))
    }

    @Test fun onlyThePagesOwnAddressesAreInternal() {
        assertTrue(CarStartPage.isStartPage(CarStartPage.URL))
        assertTrue(CarStartPage.isStartPage(CarStartPage.URL + "?x=1"))
        assertTrue(CarStartPage.isInternal("https://${CarStartPage.HOST}/autobridge/support"))
        assertFalse(CarStartPage.isInternal("https://www.google.com/"))
        assertFalse(CarStartPage.isInternal(null))
        assertEquals("&amp;&lt;&gt;&quot;&#39;", CarStartPage.esc("&<>\"'"))
    }
}
