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
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.Effect
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
import dev.autobridge.car.DuoSessionState
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.audio.WebMediaStatus
import dev.autobridge.logging.StructuredLog
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.settings.PreferredPlayer
import dev.autobridge.subtitles.SubtitleController
import dev.autobridge.settings.VideoEnhancement
import dev.autobridge.settings.VideoEngine
import dev.autobridge.settings.VideoSettings
import dev.autobridge.media.vlc.VlcEngineFactory

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

    /**
     * What the session, the car card, the parking gate and the settings listeners operate on. It
     * is the Media3 [Player] interface, so it holds either the ExoPlayer (the default engine) or
     * the libVLC facade ([dev.autobridge.media.vlc.VlcMediaPlayer]) interchangeably.
     */
    private var player: Player? = null

    /**
     * The concrete ExoPlayer, set non-null only on the Media3 branch. The ExoPlayer-only paths —
     * output geometry / GL effects, the video renderer, and the BEHIND_LIVE_WINDOW recovery — read
     * this and no-op when it is null (i.e. under VLC).
     */
    private var exoPlayer: ExoPlayer? = null
    private var facadeListener: Player.Listener? = null
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
            if (player == null) return
            val source = WebMediaHub.source
            // Only the Media3 branch competes with the web page for the card; under VLC there is
            // no ExoPlayer handle, so treat the engine as the non-web (EXO) source either way.
            val exo = exoPlayer
            val exoActive = exo != null && (exo.isPlaying ||
                (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING))
            if (source == null) {
                composite.select(SessionSourceArbiter.choose(composite.source, exoActive, false, false))
            } else {
                source.readMediaStatus { status ->
                    // The answer is asynchronous; the service may have been torn down meanwhile.
                    if (carPlayer !== composite) return@readMediaStatus
                    webPlayer?.update(status)
                    // Republish so the home dashboard can read this reading instead of polling the
                    // same WebView a second time. One probe per second, done here.
                    WebMediaHub.publishStatus(status)
                    composite.select(
                        SessionSourceArbiter.choose(composite.source, exoActive, true, status.playing)
                    )
                    advanceQueueIfFinished(source, status)
                }
            }
            mainHandler.postDelayed(this, WEB_POLL_MS)
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastLiveRecoveryMs = 0L

    /**
     * Whether the previous reading already said the page had finished, so the queue advances on the
     * *transition* into finished and not once per second afterwards.
     *
     * A page that has ended keeps reporting that it has ended for as long as it stays loaded, which
     * is until the next item replaces it. Acting on the level rather than the edge would empty the
     * whole queue into one page load, each item replacing the last before it could be heard.
     */
    private var webHadFinished = false

    /**
     * Loads the next queued page when the current one reaches its end.
     *
     * This lives here because the one-second poll is the only steady reading of what the page is
     * doing, and it already has the browser in hand. The decision is kept to "it finished, is there
     * a next one" — what next *means* belongs to the browser, which owns the queue and the
     * navigation ([WebMediaSource.skipToNext]).
     */
    private fun advanceQueueIfFinished(source: WebMediaSource, status: WebMediaStatus) {
        val finished = status.ended
        val startedFinishing = finished && !webHadFinished
        webHadFinished = finished
        if (!startedFinishing) return
        // Not every ended page has a successor; most do not, and then nothing should happen at all.
        if (source.skipToNext()) {
            StructuredLog.i("MEDIA", "page finished; advanced the play queue")
            // The next page is loading and has no media yet. Clearing this now means its own end is
            // seen as a fresh transition rather than a continuation of this one.
            webHadFinished = false
        }
    }

    /**
     * Translates the text-track cues ExoPlayer decodes and publishes them to [SubtitleHub], which
     * the player screen reads. Held here because this service owns the player the cues come off;
     * null until [onCreate] builds it.
     */
    private var subtitles: SubtitleController? = null

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

        /**
         * Every cue group off the active text track, forwarded to the translator. ExoPlayer
         * delivers these on its application looper - the main thread here - which is the thread
         * [SubtitleController] and [dev.autobridge.subtitles.SubtitleHub] expect, so no hop is
         * needed. An empty group is the gap between two lines and clears the screen.
         */
        override fun onCues(cueGroup: CueGroup) {
            subtitles?.onCues(cueGroup.cues.map { it.text })
        }

        /**
         * Clears the subtitle line when the track changes under us: a new item, or a seek that
         * jumps past the current cue, should not leave the previous line stranded on screen until
         * the next cue happens to arrive.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            subtitles?.clear()
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
     * Re-applies the picture settings when they change on the settings screen. Only the colour
     * adjustments can be applied to a stream already on air; the decoder choice is read when the
     * next item is prepared, so a change there is deliberately not forced through here - taking a
     * playing channel down to rebuild its renderer is worse than waiting for the next one.
     */
    private val videoSettingsListener: () -> Unit = {
        mainHandler.post { exoPlayer?.let { applyOutputGeometry(VideoOutputGeometry.current) } }
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
        // GL effects + output-resolution messaging are ExoPlayer-renderer features. Under VLC
        // exoPlayer is null and this whole path no-ops (libVLC does its own aspect handling).
        val currentPlayer = exoPlayer ?: return
        runCatching {
            // Colour first, letterbox last: Presentation pads the frame to the surface's shape,
            // and padding that has been through a brightness or saturation shader is no longer
            // black.
            val effects = buildList {
                addAll(enhancementEffects(VideoSettings.enhancement(this@MediaPlaybackService)))
                if (output != null) {
                    add(Presentation.createForAspectRatio(output.aspectRatio, Presentation.LAYOUT_SCALE_TO_FIT))
                }
            }
            currentPlayer.setVideoEffects(effects)
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
                "videoOutputGeometry " +
                    (output?.let { "${it.width}x${it.height} ratio=${"%.3f".format(it.aspectRatio)}" } ?: "cleared") +
                    " enhancement=" + (if (VideoSettings.enhancement(this).isNeutral) "neutral" else "on")
            )
        }
    }

    /**
     * The GL effects for the user's picture adjustments, or nothing at all when every slider sits
     * at neutral. An identity shader is still a shader the renderer has to run on every frame, so
     * "no adjustment" has to mean an empty list rather than a no-op effect.
     */
    @OptIn(UnstableApi::class)
    private fun enhancementEffects(enhancement: VideoEnhancement): List<Effect> {
        if (enhancement.isNeutral) return emptyList()
        return buildList {
            if (enhancement.brightness != 0 || enhancement.saturation != 0) {
                add(
                    HslAdjustment.Builder()
                        .adjustLightness(enhancement.lightnessAdjustment)
                        .adjustSaturation(enhancement.saturationAdjustment)
                        .build()
                )
            }
            if (enhancement.contrast != 0) add(Contrast(enhancement.contrastAdjustment))
        }
    }

    /**
     * Applies the "Preferred player" setting to decoder selection.
     *
     * The selector is consulted each time an item is prepared, so it reads the setting rather than
     * capturing it, and a change takes effect on the next channel without rebuilding the player.
     * A hardware-only filter that would leave nothing to decode with falls back to the full ranked
     * list: an unplayable stream is a worse answer than one decoded the other way.
     */
    @OptIn(UnstableApi::class)
    private fun decoderSelector(): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val ranked: List<MediaCodecInfo> = when (VideoSettings.preferredPlayer(this)) {
                PreferredPlayer.SOFTWARE -> MediaCodecSelector.PREFER_SOFTWARE
                else -> MediaCodecSelector.DEFAULT
            }.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            if (VideoSettings.preferredPlayer(this) != PreferredPlayer.HARDWARE) {
                ranked
            } else {
                ranked.filter { it.hardwareAccelerated }.ifEmpty { ranked }
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

    /**
     * Builds the Media3 ExoPlayer exactly as before — the default engine. Extracted from
     * [onCreate] so the engine branch there reads as one decision; the body is unchanged.
     */
    @OptIn(UnstableApi::class)
    private fun buildExoPlayer(): ExoPlayer {
        // DefaultMediaSourceFactory auto-selects progressive, HLS, and DASH sources. Audio remains
        // available through Feature.MEDIA even when Feature.VIDEO is denied while moving.
        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this))
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            // Decoder choice is a setting ("Preferred player"), and fallback is on so a decoder
            // that initialises and then refuses the stream hands over instead of ending playback.
            .setRenderersFactory(
                DefaultRenderersFactory(this)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(decoderSelector())
            )
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
        // Wrap the queue so steering-wheel Next/Previous stay live at both ends: without this,
        // Next is dead on the last item and Previous is dead on the first, and the head unit
        // greys those buttons out because hasNext/hasPreviousMediaItem() report false there. For a
        // channel/track list this is the expected behaviour - Next past the end returns to the
        // start. A single-item queue still has nowhere to go, which is correct.
        exo.repeatMode = Player.REPEAT_MODE_ALL
        exo.addListener(playerListener)
        // Arms the effect pipeline. The renderer only builds it when a video-effects list has been
        // set before the first prepare(), so this empty call has to happen at construction even
        // though the real letterbox ratio is not known until a screen attaches a surface.
        exo.setVideoEffects(emptyList())
        return exo
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // The decode engine is read once, when the service builds its player, exactly as the
        // decoder choice is read when the next item is prepared. VideoSettings.videoEngine() ANDs
        // BuildConfig.VLC_ENGINE first, so the VLC branch is unreachable in a flag-off build and
        // src/novlc's throwing factory is never called.
        when (VideoSettings.videoEngine(this)) {
            VideoEngine.MEDIA3 -> {
                val exo = buildExoPlayer()
                exoPlayer = exo
                player = exo
            }
            VideoEngine.VLC -> {
                // No ExoPlayer handle: every ExoPlayer-only path (output geometry, GL effects,
                // the video renderer, BEHIND_LIVE_WINDOW recovery) no-ops. The facade owns its own
                // audio focus and parked-gate behaviour.
                exoPlayer = null
                val facade = VlcEngineFactory.create(this)
                player = facade
                // The one load-bearing member of playerListener under VLC: the browse-tree refresh.
                // invalidateState() drives the session's view of the facade but does not invoke
                // this service's playerListener (never attached to the facade), so register a
                // minimal listener that refreshes the car card's queue when the playlist changes.
                val listener = object : Player.Listener {
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                        session?.notifyChildrenChanged(ROOT_ID, player?.mediaItemCount ?: 0, null)
                    }
                }
                facade.addListener(listener)
                facadeListener = listener
            }
        }
        val base: Player = player!!
        subtitles = SubtitleController(this).also { it.start() }
        ParkingStateStore.addListener(parkingListener)
        VideoOutputGeometry.addListener(geometryListener)
        VideoSettings.addListener(videoSettingsListener)
        applyOutputGeometry(VideoOutputGeometry.current)
        enforceVideoParking()

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // The session sits on the composite, not on the engine directly, so the car media card can
        // follow browser audio too. CarMediaPlayer forwards to whichever source the arbiter picks;
        // its ExoPlayer slot is the active decode engine, VLC or Media3.
        val web = WebMediaPlayer(base.applicationLooper)
        val composite = CarMediaPlayer(base, web)
        webPlayer = web
        carPlayer = composite

        session = MediaLibrarySession.Builder(this, composite, LibraryCallback())
            .setSessionActivity(openAppIntent)
            .build()
        mainHandler.post(webPoll)
    }

    /** Pure gate decision: surface the session only when Duo is not active AND the controller is authorized. */
    internal fun shouldSurfaceSession(duoActive: Boolean, authorized: Boolean): Boolean =
        !duoActive && authorized

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val duoActive = DuoSessionState.isActive
        val allowed = MediaControllerAuthorization.isAllowed(
            packageName = controllerInfo.packageName,
            uid = controllerInfo.uid,
            ownPackageName = packageName,
            ownUid = applicationInfo.uid,
            systemUid = Process.SYSTEM_UID
        )
        val surface = shouldSurfaceSession(duoActive, allowed)
        StructuredLog.i(
            "MEDIA",
            "MediaSession controller package=${controllerInfo.packageName} uid=${controllerInfo.uid} " +
                "allowed=$allowed duoActive=$duoActive surfaced=$surface"
        )
        return session.takeIf { surface }
    }

    override fun onDestroy() {
        ParkingStateStore.removeListener(parkingListener)
        VideoOutputGeometry.removeListener(geometryListener)
        VideoSettings.removeListener(videoSettingsListener)
        mainHandler.removeCallbacksAndMessages(null)
        // playerListener is attached only on the Media3 branch; facadeListener only under VLC.
        exoPlayer?.removeListener(playerListener)
        facadeListener?.let { player?.removeListener(it) }
        facadeListener = null
        subtitles?.release()
        subtitles = null
        session?.release()
        session = null
        carPlayer?.release()
        carPlayer = null
        webPlayer = null
        player?.release()
        player = null
        exoPlayer = null
        super.onDestroy()
    }
}
