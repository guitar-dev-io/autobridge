package dev.autobridge.media

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import java.util.concurrent.Executor

/**
 * Connects to [MediaPlaybackService]'s MediaSession as a client controller. Playback commands
 * issued here go through the session (so external controllers, e.g. Android Auto or steering
 * wheel media buttons, observe the same state) rather than touching a player directly.
 */
class MediaPlaybackClient(private val context: Context) {
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val mainExecutor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    @Volatile
    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile
    var lastErrorMessage: String? = null
        private set

    val isConnected: Boolean
        get() = connectionState == ConnectionState.CONNECTED && controller != null

    val player: androidx.media3.common.Player?
        get() = controller

    val isPlaying: Boolean
        get() = controller?.isPlaying == true

    /**
     * Whether the item the session is currently on carries a video track. Used by the now-playing
     * bar to open the player as video rather than audio: a TV channel opened from the mini player
     * must reattach the video surface, not sit on the audio-only path. Reads the current size
     * first (set as soon as the first frame is decoded) and falls back to the selected tracks so
     * the answer is right even before a frame has arrived.
     */
    val hasVideo: Boolean
        get() {
            val c = controller ?: return false
            val size = c.videoSize
            if (size.width > 0 && size.height > 0) return true
            return c.currentTracks.groups.any { group ->
                group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && group.isSelected
            }
        }

    /** The index the shared session is currently on, or null when nothing is loaded. */
    val currentIndex: Int?
        get() = controller?.let { if (it.mediaItemCount > 0) it.currentMediaItemIndex else null }

    /**
     * The queue the session is currently playing, as the ordered list of each item's source URI.
     * Empty when nothing is loaded. Used to tell whether a queue the phone is about to launch is
     * already the live one, so it can be adopted instead of reset.
     */
    fun currentQueueUris(): List<String> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).map { i ->
            c.getMediaItemAt(i).localConfiguration?.uri?.toString()
                ?: c.getMediaItemAt(i).requestMetadata.mediaUri?.toString()
                ?: ""
        }
    }

    val hasNext: Boolean
        get() = controller?.hasNextMediaItem() == true

    val hasPrevious: Boolean
        get() = controller?.hasPreviousMediaItem() == true

    /** Current track title from the session metadata, or null when nothing is loaded. */
    val currentTitle: String?
        get() = controller?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }

    /** Current track artist/subtitle from the session metadata, or null. */
    val currentArtist: String?
        get() = controller?.mediaMetadata?.let { metadata ->
            (metadata.artist ?: metadata.albumArtist ?: metadata.subtitle)?.toString()?.takeIf { it.isNotBlank() }
        }

    /** Embedded album artwork bytes when the session exposes them, or null. */
    val currentArtworkData: ByteArray?
        get() = controller?.mediaMetadata?.artworkData

    fun connect(onConnected: () -> Unit = {}, onError: () -> Unit = {}) {
        disconnect()
        connectionState = ConnectionState.CONNECTING
        lastErrorMessage = null
        val token = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (controllerFuture === future) {
                    runCatching { future.get() }.onSuccess {
                        controller = it
                        connectionState = ConnectionState.CONNECTED
                        lastErrorMessage = null
                        onConnected()
                    }.onFailure {
                        controller = null
                        connectionState = ConnectionState.ERROR
                        lastErrorMessage = it.message ?: it.javaClass.simpleName
                        onError()
                    }
                }
            },
            mainExecutor
        )
    }

    /** Plays a single source. [uri] may be an http(s) URL, a bare local path, or a file/content URI. */
    fun play(uri: String, title: String? = null) {
        if (!FeaturePolicy.app.isAvailable(Feature.MEDIA)) return
        val mediaController = controller ?: return dropped("no controller yet")
        val source = buildMediaItem(uri) ?: return dropped("unusable source")
        val item = if (title.isNullOrBlank()) source else source.buildUpon()
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
        mediaController.setMediaItem(item)
        mediaController.prepare()
        mediaController.play()
    }

    /**
     * Replaces the queue with [uris] (in order) and starts at [startIndex], enabling
     * steering-wheel Next/Previous across the list. Blank/invalid entries are dropped.
     *
     * [titles] is optional and positional: when a name is given for an entry it becomes that
     * item's session metadata, so the car's media card and the phone's now-playing line say
     * "Channel 3HD" rather than a playlist URL. A title is looked up by the entry's own index in
     * [uris], so an unusable entry takes its own name out of the queue with it.
     */
    fun playPlaylist(uris: List<String>, startIndex: Int = 0, titles: List<String> = emptyList()) {
        if (!FeaturePolicy.app.isAvailable(Feature.MEDIA)) return
        val mediaController = controller ?: return
        val items = uris.mapIndexedNotNull { index, uri ->
            val source = buildMediaItem(uri) ?: return@mapIndexedNotNull null
            val name = titles.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull source
            source.buildUpon()
                .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
                .build()
        }
        if (items.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, items.size - 1)
        mediaController.setMediaItems(items, safeIndex, 0L)
        mediaController.prepare()
        mediaController.play()
    }

    /** A command that cannot be delivered is a fault worth seeing, not silence. */
    private fun dropped(reason: String) {
        android.util.Log.w("MediaPlaybackClient", "Playback command dropped: $reason")
    }

    fun next() {
        controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    }

    fun previous() {
        controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
    }

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        if (FeaturePolicy.app.isAvailable(Feature.MEDIA)) controller?.play()
    }

    fun stop() {
        // Stopping is always allowed, including after a policy transition.
        controller?.stop()
    }

    /**
     * Ends what is playing: stops it and empties the queue, so the channel is gone rather than
     * paused. [pause] and [stop] both leave the item loaded, which keeps the media card, the
     * notification and the mini player up with a Play button — a TV channel the driver wanted
     * off could only ever be paused. Always allowed, like [stop].
     *
     * A channel sent to the car through the bridge is also the bridge's session, and the car
     * home's "Continue" card is that session's snapshot; both are let go when they are this same
     * item, or the card would offer back the channel that was just turned off — and a disconnect
     * would save it again. Main thread only, like the bridge.
     */
    fun close() {
        val c = controller ?: return
        val uris = currentQueueUris().filter { it.isNotBlank() }.toSet()
        val title = currentTitle
        c.stop()
        c.clearMediaItems()
        forgetBridgeSession(uris, title)
    }

    private fun forgetBridgeSession(uris: Set<String>, title: String?) {
        fun same(source: dev.autobridge.bridge.BridgeSource?): Boolean =
            source != null && (source.url in uris || (title != null && source.title == title))
        val bridge = dev.autobridge.bridge.AutoBridgeSessionManager
        // stop() saves a snapshot first, so the stored one is cleared after it, not before.
        if (same(bridge.current.source)) bridge.stop(context)
        val store = dev.autobridge.bridge.BridgeStore
        if (same(store.lastSession(context)?.source)) store.setLastSession(context, null)
    }

    /**
     * Builds a [MediaItem] from an arbitrary source string via [MediaSourceResolver], attaching an
     * explicit MIME type for HLS/DASH so the right source is used even when the URL has no
     * recognizable extension (e.g. a manifest behind a query-only endpoint).
     */
    private fun buildMediaItem(uri: String): MediaItem? {
        val resolved = MediaSourceResolver.resolve(uri) ?: return null
        val builder = MediaItem.Builder().setUri(resolved.uri)
        when (resolved.type) {
            MediaSourceResolver.SourceType.HLS -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            MediaSourceResolver.SourceType.DASH -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            MediaSourceResolver.SourceType.PROGRESSIVE,
            MediaSourceResolver.SourceType.LOCAL -> Unit
        }
        return builder.build()
    }

    fun disconnect() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        connectionState = ConnectionState.DISCONNECTED
        lastErrorMessage = null
    }
}
