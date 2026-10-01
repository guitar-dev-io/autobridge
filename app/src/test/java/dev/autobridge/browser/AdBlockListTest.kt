package dev.autobridge.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the blocker drops and — the part that breaks pages when it is wrong — what it does not. */
class AdBlockListTest {

    @Test
    fun `known ad and tracker hosts are blocked`() {
        listOf(
            "https://doubleclick.net/pixel",
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://www.googletagservices.com/tag/js/gpt.js",
            "https://www.google-analytics.com/collect?v=1",
            "https://ib.adnxs.com/ttj?id=123",
            "https://s.amazon-adsystem.com/iu3",
            "https://cdn.taboola.com/libtrc/loader.js",
            "https://sb.scorecardresearch.com/beacon.js"
        ).forEach { url -> assertTrue(url, AdBlockList.blocks(url)) }
    }

    @Test
    fun `a rule matches subdomains but never a host that merely ends with the same letters`() {
        assertTrue(AdBlockList.blocks("https://ads.g.doubleclick.net/x"))
        // The whole point of parsing the host out: these three are different sites.
        assertFalse(AdBlockList.blocks("https://notdoubleclick.net/x"))
        assertFalse(AdBlockList.blocks("https://mydoubleclick.net/x"))
        assertFalse(AdBlockList.blocks("https://doubleclick.net.evil.example/x"))
    }

    @Test
    fun `a url that only mentions an ad host in its query is not blocked`() {
        // A substring test over the whole URL would drop this, taking the page with it.
        assertFalse(AdBlockList.blocks("https://example.com/article?utm_source=doubleclick.net"))
        assertFalse(AdBlockList.blocks("https://example.com/?r=https%3A%2F%2Fgoogle-analytics.com"))
    }

    @Test
    fun `the hosts playback and sign-in need are never blocked`() {
        listOf(
            // The streams themselves. Ads come down this host too; blocking it means no video.
            "https://rr3---sn-4g5e6nez.googlevideo.com/videoplayback?expire=1",
            "https://accounts.google.com/signin/v2/identifier",
            "https://apis.google.com/js/api.js",
            "https://www.gstatic.com/og/_/js/k=og.qtm.en_US.js",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            "https://yt3.ggpht.com/ytc/avatar.jpg",
            "https://connect.facebook.net/en_US/sdk.js"
        ).forEach { url -> assertFalse(url, AdBlockList.blocks(url)) }
    }

    @Test
    fun `youtube ad endpoints are blocked by path and the player api is not`() {
        assertTrue(AdBlockList.blocks("https://www.youtube.com/pagead/interaction/?ai=x"))
        assertTrue(AdBlockList.blocks("https://www.youtube.com/api/stats/ads?ns=yt"))
        assertTrue(AdBlockList.blocks("https://www.youtube.com/ptracking?video_id=abc"))
        assertTrue(AdBlockList.blocks("https://www.youtube.com/get_midroll_info?video_id=abc"))
        assertTrue(AdBlockList.blocks("https://m.youtube.com/pagead/viewthroughconversion/1"))

        // The player response carries the stream URLs: an empty reply here is a blank video.
        assertFalse(AdBlockList.blocks("https://www.youtube.com/youtubei/v1/player?key=x"))
        assertFalse(AdBlockList.blocks("https://www.youtube.com/youtubei/v1/browse?key=x"))
        assertFalse(AdBlockList.blocks("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(AdBlockList.blocks("https://www.youtube.com/s/player/abc/base.js"))
        // A video whose own title or id happens to contain an ad path is still a watch page.
        assertFalse(AdBlockList.blocks("https://www.youtube.com/results?search_query=/pagead/"))
    }

    @Test
    fun `a path rule does not leak onto other hosts`() {
        // /ptracking is only an ad endpoint on YouTube; elsewhere it is just a path.
        assertFalse(AdBlockList.blocks("https://example.com/ptracking"))
        assertFalse(AdBlockList.blocks("https://example.com/pagead/banner.png"))
    }

    @Test
    fun `malformed and scheme-less urls are left alone rather than guessed at`() {
        assertFalse(AdBlockList.blocks(""))
        assertFalse(AdBlockList.blocks("doubleclick.net/x"))
        assertFalse(AdBlockList.blocks("about:blank"))
        assertFalse(AdBlockList.blocks("data:text/html,<p>hi"))
    }
}
