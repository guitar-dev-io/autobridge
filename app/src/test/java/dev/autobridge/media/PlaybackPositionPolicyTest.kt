package dev.autobridge.media

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPositionPolicyTest {
    private val hour = 60 * 60_000L

    @Test
    fun `only a finite, seekable item long enough to matter is remembered`() {
        assertTrue(PlaybackPositionPolicy.isRememberable(hour, isLive = false, isSeekable = true))
        assertFalse(PlaybackPositionPolicy.isRememberable(hour, isLive = true, isSeekable = true))
        assertFalse(PlaybackPositionPolicy.isRememberable(hour, isLive = false, isSeekable = false))
        assertFalse(PlaybackPositionPolicy.isRememberable(C.TIME_UNSET, isLive = false, isSeekable = true))
        assertFalse(PlaybackPositionPolicy.isRememberable(60_000L, isLive = false, isSeekable = true))
    }

    @Test
    fun `the opening seconds and the closing stretch are forgotten rather than saved`() {
        assertNull(PlaybackPositionPolicy.positionToSave(5_000L, hour))
        assertEquals(20 * 60_000L, PlaybackPositionPolicy.positionToSave(20 * 60_000L, hour))
        assertNull(PlaybackPositionPolicy.positionToSave(hour - 10_000L, hour))
    }

    @Test
    fun `resuming steps back a little and refuses a position the item no longer has`() {
        assertEquals(20 * 60_000L - PlaybackPositionPolicy.REWIND_MS, PlaybackPositionPolicy.resumeAt(20 * 60_000L, hour))
        // The file was replaced by a shorter one under the same URI.
        assertNull(PlaybackPositionPolicy.resumeAt(50 * 60_000L, 30 * 60_000L))
    }

    @Test
    fun `the oldest entries are dropped past the cap`() {
        val max = PlaybackPositionPolicy.MAX_ENTRIES
        val entries = (0 until max + 2).associate { "uri$it" to it.toLong() }
        assertEquals(listOf("uri0", "uri1"), PlaybackPositionPolicy.overflow(entries))
        assertEquals(emptyList<String>(), PlaybackPositionPolicy.overflow(entries.minus(listOf("uri0", "uri1"))))
    }

    @Test
    fun `stored values round-trip and junk reads as nothing`() {
        assertEquals(1234L to 99L, PlaybackPositionPolicy.decode(PlaybackPositionPolicy.encode(1234L, 99L)))
        assertNull(PlaybackPositionPolicy.decode(null))
        assertNull(PlaybackPositionPolicy.decode("garbage"))
        assertNull(PlaybackPositionPolicy.decode("1:x"))
    }
}
