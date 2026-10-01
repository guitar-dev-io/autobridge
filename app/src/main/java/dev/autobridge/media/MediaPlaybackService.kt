package dev.autobridge.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Timeline
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.autobridge.MainActivity
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.display.StructuredLog
import dev.autobridge.safety.ParkingStateStore

/**
 * Hosts the long-lived Media3 player and MediaSession used by Android Auto and steering controls.
 *
 * A [MediaLibraryService] rather than a plain session service: Android Auto only treats an app as a
 * media app - and so only gives it the media card beside the navigation panel in its split
 * dashboard - when it can browse a library root through `android.media.browse.MediaBrowserService`.
 * The tree is deliberately shallow: one root whose children are the current play queue, so the car
 * can show and jump within what is already playing without this service owning a catalogue.
 */
class MediaPlaybackService : MediaLibraryService() {
    private companion object {
        /** Minimum gap between two live-edge recoveries, so a failing stream is not retried in a loop. */
        const val LIVE_RECOVERY_INTERVAL_MS = 5_000L
        const val ROOT_ID = "autobridge:root"
        const val QUEUE_PREFIX = "autobridge:queue:"
        const val WEB_POLL_MS = 1_000L
    }

    private var player: ExoPlayer? = null
    private var webPlayer: WebMediaPlayer? = null
    private var carPlayer: CarMediaPlayer? = null
    private var session: MediaLibrarySession? = null

    /**
     * Polls the car browser's page audio once a second while a browser is registered, and lets
     * [SessionSourceArbiter] decide whether the session shows it or ExoPlayer. One small fixed
     * script per second, and only while the browser exists.
     */
    private val webPoll = object : Runnable {
        override fun run() {
            val composite = carPlayer ?: return
            val exo = player ?: return
            val source = WebMediaHub.source
            val exoActive = exo.isPlaying ||
                (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING)
            if (source == null) {
                composite.select(SessionSourceArbiter.choose(composite.source, exoActive, false, false))
            } else {
                source.readMediaStatus { status ->
                    // The answer is asynchronous; the service may have been torn down meanwhile.
                    if (carPlayer !== composite) return@readMediaStatus
                    webPlayer?.update(status)
                    composite.select(
                        SessionSourceArbiter.choose(composite.source, exoActive, true, status.playing)
                    )
                }
            }
            mainHandler.postDelayed(this, WEB_POLL_MS)
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLiveRecoveryMs = 0L

    /**
     * Rejoins a live stream whose window has moved past our position - what an IPTV channel does
     * while playback is paused, and the reason a paused channel came back as a dead error pane
     * instead of a picture. Media3 does not recover from this on its own.
     */
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastLiveRecoveryMs < LIVE_RECOVERY_INTERVAL_MS) {
                StructuredLog.w("MEDIA", "behind live window again within ${LIVE_RECOVERY_INTERVAL_MS}ms; reporting it")
                return
            }
            lastLiveRecoveryMs = now
            StructuredLog.i("MEDIA", "behind live window; rejoining at the live edge")
            player?.let { target ->
                target.seekToDefaultPosition()
                target.prepare()
            }
        }

        /** The browse tree is the queue, so a queue change is a change of the root's children. */
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            session?.notifyChildrenChanged(ROOT_ID, player?.mediaItemCount ?: 0, null)
        }
    }

    private fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("AutoBridge")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    /**
     * The queue entry at [index] as a browsable child. Entries built by [MediaPlaybackClient] carry
     * no media id and often no title, so both are synthesised; the original item, with its URI, is
     * what playback resolves back to in [resolveQueueItem].
     */
    private fun queueChild(source: MediaItem, index: Int): MediaItem {
        val title = source.mediaMetadata.title?.takeIf { it.isNotBlank() }
            ?: source.localConfiguration?.uri?.lastPathSegment
            ?: "Item ${index + 1}"
        return source.buildUpon()
            .setMediaId(QUEUE_PREFIX + index)
            .setMediaMetadata(
                source.mediaMetadata.buildUpon()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun queueIndexOf(mediaId: String): Int? {
        if (!mediaId.startsWith(QUEUE_PREFIX)) return null
        val index = mediaId.removePrefix(QUEUE_PREFIX).toIntOrNull() ?: return null
        return index.takeIf { it in 0 until (player?.mediaItemCount ?: 0) }
    }

    /** Maps a controller's item back to something playable, or null when it cannot be. */
    private fun resolveQueueItem(item: MediaItem): MediaItem? {
        if (item.localConfiguration != null) return item
        val index = queueIndexOf(item.mediaId) ?: return null
        return player?.getMediaItemAt(index)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            if (mediaId == ROOT_ID) return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), null))
            val index = queueIndexOf(mediaId)
            val currentPlayer = player
            return Futures.immediateFuture(
                if (index == null || currentPlayer == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                else LibraryResult.ofItem(queueChild(currentPlayer.getMediaItemAt(index), index), null)
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != ROOT_ID) {
                return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
            val currentPlayer = player
            val count = currentPlayer?.mediaItemCount ?: 0
            val from = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(1)).coerceAtMost(count.toLong()).toInt()
            val to = (from + pageSize.coerceAtLeast(1)).coerceAtMost(count)
            val children = ImmutableList.builder<MediaItem>()
            if (currentPlayer != null) {
                for (i in from until to) children.add(queueChild(currentPlayer.getMediaItemAt(i), i))
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(children.build(), params))
        }

        /**
         * Tapping a queue entry on the car jumps within the existing queue instead of replacing it
         * with that one item, so steering-wheel next/previous still walk the whole list.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val currentPlayer = player
            val tapped = mediaItems.singleOrNull()?.let { queueIndexOf(it.mediaId) }
            if (currentPlayer != null && tapped != null) {
                val queue = (0 until currentPlayer.mediaItemCount).map { currentPlayer.getMediaItemAt(it) }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(queue, tapped, C.TIME_UNSET))
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { resolveQueueItem(it) }
            if (resolved.any { it == null }) {
                StructuredLog.w("MEDIA", "addMediaItems: ${resolved.count { it == null }} unresolvable item(s) from ${controller.packageName}")
                return Futures.immediateFailedFuture(UnsupportedOperationException("unresolvable media item"))
            }
            return Futures.immediateFuture(resolved.filterNotNull().toMutableList())
        }
    }
    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        if (Looper.myLooper() == Looper.getMainLooper()) enforceVideoParking()
        else mainHandler.post { enforceVideoParking() }
    }

    /**
     * Re-letterboxes when a screen attaches to, or releases, a video output surface. Always hops
     * through the main handler even when already on it: the surface itself arrives at the player
     * over the MediaSession, which posts, so applying inline could run before the surface is set
     * and the renderer would have nothing to size the output against.
     */
    private val geometryListener: (VideoOutputGeometry.Output?) -> Unit = { output ->
        mainHandler.post { applyOutputGeometry(output) }
    }

    /**
     * Letterboxes the picture into the output surface rather than stretching it to fill.
     *
     * [Presentation.LAYOUT_SCALE_TO_FIT] pads the frame with black until it matches the surface's
     * aspect ratio, so every pixel of the source stays on screen; the surface-filling scale that
     * follows is then distortion-free. The renderer will not size the effect pipeline's output
     * without [Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION], which a `SurfaceView` would have supplied
     * on its own but a bare Surface does not.
     */
    @OptIn(UnstableApi::class)
    private fun applyOutputGeometry(output: VideoOutputGeometry.Output?) {
        val currentPlayer = player ?: return
        runCatching {
            currentPlayer.setVideoEffects(
                if (output == null) emptyList()
                else listOf(Presentation.createForAspectRatio(output.aspectRatio, Presentation.LAYOUT_SCALE_TO_FIT))
            )
            if (output != null) {
                videoRenderer(currentPlayer)?.let { renderer ->
                    currentPlayer.createMessage(renderer)
                        .setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION)
                        .setPayload(Size(output.width, output.height))
                        .send()
                }
            }
        }.onFailure {
            StructuredLog.w("MEDIA", "videoOutputGeometry failed: ${it.javaClass.simpleName} ${it.message}")
        }.onSuccess {
            StructuredLog.i(
                "MEDIA",
                "videoOutputGeometry " + (output?.let { "${it.width}x${it.height} ratio=${"%.3f".format(it.aspectRatio)}" } ?: "cleared")
            )
        }
    }

    private fun videoRenderer(target: ExoPlayer): Renderer? =
        (0 until target.rendererCount).firstOrNull { target.getRendererType(it) == C.TRACK_TYPE_VIDEO }
            ?.let { target.getRenderer(it) }

    private fun enforceVideoParking() {
        val currentPlayer = player ?: return
        currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !FeaturePolicy.app.isAvailable(Feature.VIDEO))
            .build()
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // DefaultMediaSourceFactory auto-selects progressive, HLS, and DASH sources. Audio remains
        // available through Feature.MEDIA even when Feature.VIDEO is denied while moving.
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this))
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            // Stated rather than defaulted: USAGE_MEDIA with CONTENT_TYPE_MUSIC is what tells the
            // car's audio policy this is media and may be ducked for a navigation prompt, instead
            // of being treated as an unclassified stream. The second argument hands focus handling
            // to Media3, which pauses on a transient loss and resumes afterwards.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // Keeps the session's reported position moving while paused, so the car's progress bar
            // and the phone UI agree.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = exoPlayer
        exoPlayer.addListener(playerListener)
        // Arms the effect pipeline. The renderer only builds it when a video-effects list has been
        // set before the first prepare(), so this empty call has to happen at construction even
        // though the real letterbox ratio is not known until a screen attaches a surface.
        exoPlayer.setVideoEffects(emptyList())
        ParkingStateStore.addListener(parkingListener)
        VideoOutputGeometry.addListener(geometryListener)
        applyOutputGeometry(VideoOutputGeometry.current)
        enforceVideoParking()

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // The session sits on the composite, not on ExoPlayer directly, so the car media card can
        // follow browser audio too. Everything else here keeps talking to ExoPlayer itself.
        val web = WebMediaPlayer(exoPlayer.applicationLooper)
        val composite = CarMediaPlayer(exoPlayer, web)
        webPlayer = web
        carPlayer = composite

        session = MediaLibrarySession.Builder(this, composite, LibraryCallback())
            .setSessionActivity(openAppIntent)
            .build()
        mainHandler.post(webPoll)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val allowed = MediaControllerAuthorization.isAllowed(
            packageName = controllerInfo.packageName,
            uid = controllerInfo.uid,
            ownPackageName = packageName,
            ownUid = applicationInfo.uid,
            systemUid = Process.SYSTEM_UID
        )
        StructuredLog.i(
            "MEDIA",
            "MediaSession controller package=${controllerInfo.packageName} uid=${controllerInfo.uid} allowed=$allowed"
        )
        return session.takeIf { allowed }
    }

    override fun onDestroy() {
        ParkingStateStore.removeListener(parkingListener)
        VideoOutputGeometry.removeListener(geometryListener)
        mainHandler.removeCallbacksAndMessages(null)
        player?.removeListener(playerListener)
        session?.release()
        session = null
        carPlayer?.release()
        carPlayer = null
        webPlayer = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
