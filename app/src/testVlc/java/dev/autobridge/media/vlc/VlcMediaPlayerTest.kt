package dev.autobridge.media.vlc

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-independent logic behind the libVLC facade — queue storage and end-of-queue wrap, the
 * stateful parked gate (re-applied on prepare), and the live-vs-VOD seek decision — exercised over
 * a fake [VlcBackend] with no device, no Looper and no real libVLC `.so`. These classes only
 * compile under `-Pautobridge.vlc=true`, so this source set is wired into the unit tests only then.
 */
class VlcMediaPlayerTest {

    /**
     * A fake libVLC. [hasMediaLoaded] flips on [load]; the test can mark the loaded media live and
     * can make [setVideoTrackEnabled] fail to prove the gate's fail-closed path. Loaded URIs are
     * recorded so queue/wrap behaviour can be asserted.
     */
    private class FakeBackend : VlcBackend {
        val loadedUris = mutableListOf<String>()
        var videoEnabled = true
            private set
        var disableShouldFail = false
        private var live = false
        private var duration = 60_000L
        private var loaded = false

        fun markLive(value: Boolean) {
            live = value
            if (value) duration = 0L
        }

        private data class FakeSource(override val uri: String) : VlcBackend.Source

        override val hasMediaLoaded: Boolean get() = loaded
        override val phase: VlcBackend.PlaybackPhase get() = VlcBackend.PlaybackPhase.PLAYING
        override val isLive: Boolean get() = loaded && live
        override val positionMs: Long get() = 0L
        override val durationMs: Long get() = if (live) 0L else duration
        override val videoWidth: Int get() = 0
        override val videoHeight: Int get() = 0

        override fun source(uri: String): VlcBackend.Source = FakeSource(uri)

        override fun load(source: VlcBackend.Source) {
            loadedUris.add(source.uri)
            loaded = true
            videoEnabled = true
        }

        override fun play() {}
        override fun pause() {}
        override fun stop() { loaded = false }
        override fun seekTo(positionMs: Long) {}

        override fun setVideoTrackEnabled(enabled: Boolean): Boolean {
            if (!enabled && disableShouldFail) return false
            videoEnabled = enabled
            return true
        }

        override fun setAspectRatio(ratio: String?) {}
        override fun setScale(scale: Float) {}
        override fun attachSurface(surface: android.view.Surface) {}
        override fun detachSurface() {}
        override fun setListener(listener: VlcBackend.Listener?) {}
        override fun release() {}
    }

    /** MediaItems carry only a mediaId (JVM-safe, no android.net.Uri); the logic reads that as URI. */
    private fun logicOver(backend: VlcBackend) =
        VlcPlaybackLogic(backend) { it.mediaId }

    private fun items(vararg ids: String): List<MediaItem> =
        ids.map { MediaItem.Builder().setMediaId(it).build() }

    // -- (a) queue storage + end-of-queue wrap -------------------------------------------------

    @Test
    fun `setMediaItems stores the whole queue and loads the start item`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)

        logic.setMediaItems(items("a", "b", "c"), startIndex = 0)

        assertEquals(3, logic.queue.size)
        assertEquals(0, logic.currentIndex)
        assertEquals(listOf("a"), backend.loadedUris)
        assertTrue("multi-item queue can step", logic.canStep)
    }

    @Test
    fun `next and previous wrap at both ends`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)
        logic.setMediaItems(items("a", "b", "c"), startIndex = 0)

        logic.stepTo(logic.previousIndex()) // from 0 wraps to last
        assertEquals(2, logic.currentIndex)
        assertEquals("c", backend.loadedUris.last())

        logic.stepTo(logic.nextIndex()) // from 2 wraps to first
        assertEquals(0, logic.currentIndex)
        assertEquals("a", backend.loadedUris.last())

        logic.stepTo(logic.nextIndex())
        assertEquals(1, logic.currentIndex)
        assertEquals("b", backend.loadedUris.last())
    }

    @Test
    fun `addMediaItems grows the stored queue`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)
        logic.setMediaItems(items("a", "b"), startIndex = 0)

        logic.addMediaItems(index = 2, items = items("c", "d"))

        assertEquals(4, logic.queue.size)
        assertEquals(listOf("a", "b", "c", "d"), logic.queue.map { it.mediaId })
        // Appending after current does not move the current item.
        assertEquals(0, logic.currentIndex)
    }

    @Test
    fun `addMediaItems onto an empty queue loads the first item`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)

        logic.addMediaItems(index = 0, items = items("x", "y"))

        assertEquals(2, logic.queue.size)
        assertEquals(0, logic.currentIndex)
        assertEquals(listOf("x"), backend.loadedUris)
    }

    @Test
    fun `a single-item queue has nowhere to step`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)
        logic.setMediaItems(items("only"), startIndex = 0)

        assertFalse(logic.canStep)
        assertEquals(0, logic.nextIndex())
        assertEquals(0, logic.previousIndex())
    }

    // -- (b) parked gate, stateful and re-applied on prepare -----------------------------------

    @Test
    fun `gate with a media prepared disables the video track`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)
        logic.setMediaItems(items("a"), startIndex = 0) // loads -> hasMediaLoaded

        val safe = logic.recordGate(videoDisabled = true)

        assertTrue("disable succeeded, so the write is safe", safe)
        assertFalse("video track is disabled while parked", backend.videoEnabled)
    }

    @Test
    fun `gate fail-closes when disabling video fails while a media is prepared`() {
        val backend = FakeBackend().apply { disableShouldFail = true }
        val logic = logicOver(backend)
        logic.setMediaItems(items("a"), startIndex = 0)

        val safe = logic.recordGate(videoDisabled = true)

        assertFalse("a refused disable is not safe; the caller must pause", safe)
    }

    @Test
    fun `gate recorded with no media loaded returns success and does not pause`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)

        // The service writes the parked gate once in onCreate(), before any media is loaded.
        val safe = logic.recordGate(videoDisabled = true)

        assertTrue("no media means nothing unsafe to stop", safe)
        assertTrue(logic.videoTrackGated)
        assertTrue("nothing loaded", backend.loadedUris.isEmpty())
    }

    @Test
    fun `the stored gate is re-applied on the next media load`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)
        logic.recordGate(videoDisabled = true) // recorded before any media

        logic.setMediaItems(items("a"), startIndex = 0) // first load must honour the stored gate

        assertFalse("video disabled the moment a track exists", backend.videoEnabled)
    }

    @Test
    fun `clearing the gate re-enables video on the next load`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)
        logic.setMediaItems(items("a"), startIndex = 0)
        logic.recordGate(videoDisabled = true)
        assertFalse(backend.videoEnabled)

        logic.recordGate(videoDisabled = false)

        assertTrue("gate cleared, video back on", backend.videoEnabled)
    }

    // -- (c) live vs VOD seek ------------------------------------------------------------------

    @Test
    fun `in-item seek is available for VOD and withheld for live`() {
        val backend = FakeBackend()
        val logic = logicOver(backend)
        logic.setMediaItems(items("a"), startIndex = 0)

        assertTrue("VOD with a finite length can seek", logic.canSeekInItem)

        backend.markLive(true)
        assertFalse("a live stream withholds in-item seek", logic.canSeekInItem)
    }
}
