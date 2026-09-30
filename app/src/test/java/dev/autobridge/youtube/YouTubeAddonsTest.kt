package dev.autobridge.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL shapes and SponsorBlock arithmetic, covered without a WebView or the network. */
class YouTubeAddonsTest {

    @Test
    fun `watch links on every front end resolve to the same id`() {
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=4",
            "https://youtube.com/watch?app=desktop&v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?t=42",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1",
            "https://www.youtube.com/live/dQw4w9WgXcQ"
        ).forEach { url ->
            assertEquals(url, "dQw4w9WgXcQ", YouTubeUrls.videoId(url))
        }
    }

    @Test
    fun `pages that are not a single video have no id`() {
        assertNull(YouTubeUrls.videoId("https://www.youtube.com/"))
        assertNull(YouTubeUrls.videoId("https://www.youtube.com/results?search_query=cats"))
        assertNull(YouTubeUrls.videoId("https://www.youtube.com/@channel"))
        // An id is exactly 11 characters of the id alphabet; a truncated one is not a video.
        assertNull(YouTubeUrls.videoId("https://www.youtube.com/watch?v=short"))
    }

    @Test
    fun `other sites are never treated as youtube`() {
        assertNull(YouTubeUrls.videoId("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeUrls.videoId("https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ"))
        assertTrue(YouTubeUrls.isYouTube("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(YouTubeUrls.isYouTubeMusic("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `service hosts under the youtube domain are not watch pages`() {
        // v.youtube.com is not a front end the add-ons can drive: there is no player on it to
        // skip a segment in or set a quality on. Matching the host by suffix would pull it in.
        assertTrue(!YouTubeUrls.isYouTube("https://v.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeUrls.videoId("https://v.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeUrls.videoId("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"))
    }

    @Test
    fun `the hash prefix is the documented sha256 head, not the id`() {
        // Four hex characters is what the API expects, and it must not contain the id itself.
        val prefix = SponsorBlock.hashPrefix("dQw4w9WgXcQ")
        assertEquals(4, prefix.length)
        assertTrue(prefix.all { it.isDigit() || it in 'a'..'f' })
        assertEquals(prefix, SponsorBlock.hashPrefix("dQw4w9WgXcQ"))
    }

    @Test
    fun `only the requested video and the enabled categories survive parsing`() {
        val body = """
            [
              {"videoID":"aaaaaaaaaaa","segments":[
                {"category":"sponsor","actionType":"skip","segment":[0.0,10.0]}
              ]},
              {"videoID":"dQw4w9WgXcQ","segments":[
                {"category":"sponsor","actionType":"skip","segment":[12.5,30.0]},
                {"category":"intro","actionType":"skip","segment":[0.0,5.0]},
                {"category":"sponsor","actionType":"mute","segment":[40.0,50.0]},
                {"category":"unknown_future","actionType":"skip","segment":[60.0,70.0]},
                {"category":"selfpromo","actionType":"skip","segment":[80.0,80.4]}
              ]}
            ]
        """.trimIndent()

        val segments = SponsorBlock.parse(body, "dQw4w9WgXcQ", setOf(SponsorCategory.SPONSOR))

        // The other video, the disabled category, the muted segment, the category this build does
        // not know, and the sub-second segment are all dropped.
        assertEquals(1, segments.size)
        assertEquals(SponsorCategory.SPONSOR, segments.single().category)
        assertEquals(12.5, segments.single().start, 0.001)
        assertEquals(30.0, segments.single().end, 0.001)
    }

    @Test
    fun `a malformed response yields nothing rather than throwing`() {
        assertTrue(SponsorBlock.parse("not json", "dQw4w9WgXcQ", setOf(SponsorCategory.SPONSOR)).isEmpty())
        assertTrue(SponsorBlock.parse("[]", "dQw4w9WgXcQ", setOf(SponsorCategory.SPONSOR)).isEmpty())
    }

    @Test
    fun `nothing is requested while the feature is off`() {
        val body = """[{"videoID":"dQw4w9WgXcQ","segments":[
            {"category":"sponsor","actionType":"skip","segment":[1.0,9.0]}]}]"""
        assertTrue(SponsorBlock.parse(body, "dQw4w9WgXcQ", emptySet()).isEmpty())
    }

    @Test
    fun `overlapping segments merge so a skip cannot land inside the next one`() {
        val merged = SponsorBlock.merge(
            listOf(
                SponsorSegment(SponsorCategory.SPONSOR, 30.0, 45.0),
                SponsorSegment(SponsorCategory.INTRO, 0.0, 10.0),
                SponsorSegment(SponsorCategory.SELF_PROMO, 40.0, 60.0)
            )
        )
        assertEquals(2, merged.size)
        assertEquals(0.0, merged[0].start, 0.001)
        assertEquals(10.0, merged[0].end, 0.001)
        assertEquals(30.0, merged[1].start, 0.001)
        assertEquals(60.0, merged[1].end, 0.001)
    }

    @Test
    fun `the skip target is the end of the segment the position is inside`() {
        val segments = listOf(
            SponsorSegment(SponsorCategory.SPONSOR, 10.0, 20.0),
            SponsorSegment(SponsorCategory.OUTRO, 100.0, 120.0)
        )
        assertNull(SponsorBlock.skipTarget(segments, 9.9))
        assertEquals(20.0, SponsorBlock.skipTarget(segments, 10.0)!!, 0.001)
        assertEquals(20.0, SponsorBlock.skipTarget(segments, 19.0)!!, 0.001)
        // Just before the end counts as done: seeking there would re-enter the same segment.
        assertNull(SponsorBlock.skipTarget(segments, 19.9))
        assertEquals(120.0, SponsorBlock.skipTarget(segments, 119.0)!!, 0.001)
    }

    @Test
    fun `the injected script carries the segments and nothing from the page`() {
        val script = SponsorBlock.script(
            "dQw4w9WgXcQ",
            listOf(SponsorSegment(SponsorCategory.SPONSOR, 12.5, 30.0))
        )
        assertTrue(script.contains("[12.5,30.0]"))
        assertTrue(script.contains("__abSponsorSegments"))
        // No native object is handed to the page, on either surface.
        assertTrue(!script.contains("JavascriptInterface"))
        assertTrue(!script.contains("Android."))
    }
}
