package dev.autobridge.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarThumbnailsTest {

    @Test
    fun `a watch page resolves to that video's own still`() {
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
            CarThumbnails.url("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun `shares and shorts resolve the same way`() {
        val expected = "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg"
        assertEquals(expected, CarThumbnails.url("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(expected, CarThumbnails.url("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun `a page that is not one video has no still to point at`() {
        assertNull(CarThumbnails.url("https://m.youtube.com"))
        assertNull(CarThumbnails.url("https://www.youtube.com/results?search_query=lofi"))
    }

    @Test
    fun `other hosts publish nothing this app can address`() {
        assertNull(CarThumbnails.url("https://example.com/video.mp4"))
        assertNull(CarThumbnails.url(""))
    }
}
