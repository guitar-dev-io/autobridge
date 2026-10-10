package dev.autobridge.media.vlc

import androidx.media3.common.MediaItem

/**
 * The device-independent heart of [VlcMediaPlayer]: the queue, the current index, the parked-gate
 * state machine, and the live/VOD seek decision — everything that can be reasoned about without a
 * `Looper`, a `Context`, an `AudioManager` or a real libVLC `.so`. [VlcMediaPlayer] owns the Media3
 * `SimpleBasePlayer` surface and audio focus and delegates these decisions here, so this class can
 * be unit-tested over a fake [VlcBackend] (see VlcMediaPlayerTest).
 *
 * It drives the backend directly (load/seek/track-enable), because the behaviours under test —
 * "a set of N items yields an N-entry playlist", "the gate recorded before any media is re-applied
 * on the next load", "a live stream withholds in-item seek" — are exactly backend-interaction
 * sequences. The facade reads [queue]/[currentIndex] back out to build its Media3 `State`.
 */
class VlcPlaybackLogic(
    private val backend: VlcBackend,
    /**
     * How a queue item's playable URI is read. The default reads the Media3 `localConfiguration` /
     * `requestMetadata` the session fills in; a JVM unit test injects a reader keyed off `mediaId`
     * so it needs no real `android.net.Uri`.
     */
    private val uriExtractor: (MediaItem) -> String? = { item ->
        item.localConfiguration?.uri?.toString() ?: item.requestMetadata.mediaUri?.toString()
    },
) {

    val queue: MutableList<MediaItem> = mutableListOf()
    var currentIndex: Int = 0
        private set

    /** True once prepare() has been seen; the gate re-applies and play may start. */
    var prepared: Boolean = false

    /** The video track is disabled (parked) when true. Recorded on every track-selection write. */
    var videoTrackGated: Boolean = false
        private set

    /** Replace the whole queue; load the chosen start item. */
    fun setMediaItems(items: List<MediaItem>, startIndex: Int) {
        queue.clear()
        queue.addAll(items)
        currentIndex = when {
            queue.isEmpty() -> 0
            startIndex < 0 -> 0
            else -> startIndex.coerceIn(0, queue.lastIndex)
        }
        loadCurrent()
    }

    /** Insert items at [index]; if the queue was empty the first becomes current and loads. */
    fun addMediaItems(index: Int, items: List<MediaItem>) {
        val at = index.coerceIn(0, queue.size)
        val wasEmpty = queue.isEmpty()
        queue.addAll(at, items)
        if (wasEmpty) {
            currentIndex = 0
            loadCurrent()
        } else if (at <= currentIndex) {
            currentIndex += items.size
        }
    }

    fun nextIndex(): Int = if (queue.isEmpty()) 0 else (currentIndex + 1) % queue.size
    fun previousIndex(): Int =
        if (queue.isEmpty()) 0 else (currentIndex - 1 + queue.size) % queue.size

    /** Jump to [index] in the queue (wrapping handled by the caller via next/previousIndex). */
    fun stepTo(index: Int) {
        if (queue.isEmpty() || index == currentIndex || index !in queue.indices) return
        currentIndex = index
        loadCurrent()
    }

    /** Next/Previous are only offered when the queue has somewhere to go. */
    val canStep: Boolean get() = queue.size > 1

    /** In-item seek is a VOD affordance; a live stream withholds it. */
    val canSeekInItem: Boolean get() = backend.hasMediaLoaded && !backend.isLive && backend.durationMs > 0

    fun seekInItem(positionMs: Long) {
        if (canSeekInItem) backend.seekTo(positionMs.coerceAtLeast(0L))
    }

    private fun loadCurrent() {
        val item = queue.getOrNull(currentIndex) ?: return
        val uri = uriExtractor(item) ?: return
        backend.load(backend.source(uri))
        reapplyGate()
    }

    // -- Parked gate ---------------------------------------------------------------------------

    /**
     * Record the latest gate from a track-selection write and, if a media is prepared, enforce it.
     * Returns true when the write is safe (nothing to stop, or video disabled successfully). A
     * false return means the engine refused to disable video while a media was prepared — the
     * caller fail-closes by pausing. With no media loaded this is only a recorded intent (success).
     */
    fun recordGate(videoDisabled: Boolean): Boolean {
        videoTrackGated = videoDisabled
        if (!backend.hasMediaLoaded) return true
        return applyGate()
    }

    /** Re-apply the stored gate on prepare / track-available. Returns false on a failed disable. */
    fun reapplyGate(): Boolean {
        if (!backend.hasMediaLoaded) return true
        return applyGate()
    }

    private fun applyGate(): Boolean {
        val ok = backend.setVideoTrackEnabled(!videoTrackGated)
        // Only a failed *disable while parked* is unsafe; a failed enable is cosmetic.
        return ok || !videoTrackGated
    }
}
