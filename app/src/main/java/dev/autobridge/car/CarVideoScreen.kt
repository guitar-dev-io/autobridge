package dev.autobridge.car

import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.iptv.ChannelQueue
import dev.autobridge.iptv.IptvChannelQueue
import dev.autobridge.logging.StructuredLog
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.media.VideoOutputGeometry
import dev.autobridge.media.VideoSurfaceArbiter
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement

/** Native video output: the shared MediaSession renders to the host surface, without capture. */
class CarVideoScreen(
    carContext: CarContext,
    private val uri: String,
    private val title: String,
    /**
     * The channels around this one, so Next / Previous on the wheel or the media card step
     * through the category; null plays [uri] on its own. See [IptvChannelQueue].
     */
    private val queue: ChannelQueue? = null,
) : Screen(carContext), SurfaceCallback {
    private val appManager = carContext.getCarService(AppManager::class.java)
    private val media = MediaPlaybackClient(carContext)
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var active = false
    private var started = false
    /**
     * Set when playback is paused by something other than the user - the host taking the surface
     * away, or this screen being stopped - so the picture comes back on its own once the surface
     * does. Never set for a pause the user asked for, and cleared as soon as it is consumed.
     */
    private var resumeWhenSurfaceReturns = false
    /** Setup and connection failures, which stand until the screen is reopened. */
    private var failure: String? = null

    /**
     * Playback failure, kept apart from [failure] because it follows the player instead of
     * latching. A live channel that recovers - the service re-prepares at the live edge after
     * BEHIND_LIVE_WINDOW - used to leave this screen on the error pane for good, because nothing
     * ever cleared the message again.
     */
    private var playbackFailure: String? = null

    /**
     * True from starting a different channel until its first frame is on screen. The host keeps
     * showing whatever was last drawn on its surface, so without saying so the previous channel's
     * picture sat there while the new one buffered — which read as "I picked B and it is still A".
     */
    private var loadingNewChannel = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            playbackFailure = player.playerError?.let {
                carContext.getString(R.string.car_video_source_failed)
            }
            invalidate()
        }

        override fun onRenderedFirstFrame() {
            if (!loadingNewChannel) return
            loadingNewChannel = false
            invalidate()
        }

        /** Pairs with the surface size in the log, so a stretched picture can be read off the two. */
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            StructuredLog.i(
                "CAR_VIDEO",
                "videoSize ${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio}"
            )
        }
    }
    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        carContext.mainExecutor.execute {
            if (active && MirrorSurfaceOwnership.isOwner(this) && !allowed()) {
                detach()
                media.pause()
                invalidate()
            }
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                active = true
                ParkingStateStore.addListener(parkingListener)
                ProjectionService.stop(carContext)
                MirrorSurfaceOwnership.claim(this@CarVideoScreen)
                appManager.setSurfaceCallback(this@CarVideoScreen)
                media.connect(onConnected = {
                    media.player?.addListener(listener)
                    attach()
                    invalidate()
                }, onError = {
                    failure = carContext.getString(R.string.car_video_player_failed)
                    invalidate()
                })
            }

            override fun onStop(owner: LifecycleOwner) {
                active = false
                ParkingStateStore.removeListener(parkingListener)
                if (MirrorSurfaceOwnership.isOwner(this@CarVideoScreen)) {
                    resumeWhenSurfaceReturns = media.player?.playWhenReady == true
                    detach()
                    media.pause()
                } else {
                    surface = null
                }
                media.player?.removeListener(listener)
                media.disconnect()
                if (MirrorSurfaceOwnership.release(this@CarVideoScreen)) appManager.setSurfaceCallback(null)
            }
        })
    }

    private fun allowed() =
        SafetyEnforcement.gateParked(ParkingStateStore.isParked) && FeaturePolicy.app.isAvailable(Feature.VIDEO)

    private fun attach() {
        val output = surface ?: return
        val player = media.player ?: return
        if (!active || !allowed() || !output.isValid || !MirrorSurfaceOwnership.isOwner(this)) return
        if (!player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
            failure = carContext.getString(R.string.car_video_output_unavailable)
            invalidate()
            return
        }
        // Starting a different channel: stop the previous one before the surface is bound, or the
        // player paints the old channel's paused frame onto it and leaves it there until the new
        // stream's first frame arrives — and keeps playing the old channel's sound meanwhile.
        val switchingChannel = !started && !isSessionAlreadyOnThisChannel(player)
        if (switchingChannel && player.isCommandAvailable(Player.COMMAND_STOP)) player.stop()
        player.setVideoSurface(output)
        // Published after the surface, never before: the player letterboxes into this size, and the
        // renderer discards an output resolution that arrives while it still has no surface.
        VideoOutputGeometry.set(surfaceWidth, surfaceHeight)
        // The car now owns the shared player's video output; the phone player lets go of it.
        VideoSurfaceArbiter.claimForCar(reassert)
        if (!started) {
            started = true
            // Returning to TV (a fresh screen instance) while the shared session is still on the
            // same channel: this instance has started=false, but the player already holds the
            // item. Re-issuing play() would restart it; instead bind the surface to what is
            // already playing and nudge a re-render, so the picture comes back rather than leaving
            // audio-only. A new channel (nothing loaded, or a different URI) still starts fresh.
            if (isSessionAlreadyOnThisChannel(player)) {
                StructuredLog.i("CAR_VIDEO", "adopting the session already on this channel; re-rendering onto the surface")
                nudgeReRender(player)
                if (resumeWhenSurfaceReturns) {
                    resumeWhenSurfaceReturns = false
                    media.resume()
                }
            } else {
                loadingNewChannel = true
                startPlayback()
            }
        } else if (resumeWhenSurfaceReturns) {
            resumeWhenSurfaceReturns = false
            // A live channel has moved on while the surface was gone. Resuming where it stopped
            // either stalls or throws BEHIND_LIVE_WINDOW, so rejoin at the live edge instead.
            if (player.isCurrentMediaItemLive &&
                player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            ) {
                player.seekToDefaultPosition()
            } else {
                nudgeReRender(player)
            }
            StructuredLog.i("CAR_VIDEO", "resuming playback after the surface came back")
            media.resume()
        } else {
            // Surface (re)bound to a player that is already playing and was not paused by us -
            // force a frame onto the new surface so a mid-playback re-attach is not audio-only.
            nudgeReRender(player)
        }
    }

    /**
     * True when the shared session already has this screen's channel loaded, so a new screen
     * instance should adopt it rather than restart it. Compared on the current item's source URI.
     */
    private fun isSessionAlreadyOnThisChannel(player: Player): Boolean {
        if (player.mediaItemCount == 0) return false
        val current = player.currentMediaItem?.localConfiguration?.uri?.toString()
            ?: player.currentMediaItem?.requestMetadata?.mediaUri?.toString()
            ?: return false
        return current == uri
    }

    /**
     * Makes the renderer flush the current frame onto a surface that was just set under an
     * already-playing player. Setting a video surface mid-playback does not by itself repaint, so
     * live channels rejoin the live edge and everything else does a zero-delta seek.
     */
    private fun nudgeReRender(player: Player) {
        if (player.isCurrentMediaItemLive &&
            player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
        ) {
            player.seekToDefaultPosition()
        } else if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            player.seekTo(player.currentPosition)
        }
    }

    /**
     * Re-binds the car surface and repaints. Run by [VideoSurfaceArbiter] after the phone has
     * dropped its binding, so a phone clear that lands late cannot leave the car audio-only.
     */
    private val reassert: () -> Unit = reassert@{
        val output = surface ?: return@reassert
        val player = media.player ?: return@reassert
        if (!active || !output.isValid || !MirrorSurfaceOwnership.isOwner(this)) return@reassert
        if (!player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) return@reassert
        player.setVideoSurface(output)
        VideoOutputGeometry.set(surfaceWidth, surfaceHeight)
        if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            player.seekTo(player.currentPosition)
        }
        StructuredLog.i("CAR_VIDEO", "surface re-asserted after the phone let go")
    }

    private fun detach() {
        VideoSurfaceArbiter.releaseForCar(reassert)
        val output = surface ?: return
        VideoOutputGeometry.clear()
        media.player?.let { player ->
            if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) player.clearVideoSurface(output)
        }
        surface = null
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        detach()
        surface = surfaceContainer.surface
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        // The head unit picks this size, and it is what decides how much letterboxing the picture
        // gets, so it is recorded rather than inferred from a photo of the screen.
        StructuredLog.i("CAR_VIDEO", "surface ${surfaceWidth}x$surfaceHeight dpi=${surfaceContainer.dpi}")
        CarDisplayInfo.record(surfaceWidth, surfaceHeight, surfaceContainer.dpi)
        attach()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        // A destroy event for a surface this screen never attached to is a stale delivery from the
        // surface this screen's predecessor just gave up - the two SurfaceCallback swaps that happen
        // when one channel screen is popped and another pushed are not synchronized with the host's
        // own async surface teardown. Acting on it here would pause the shared player right as the
        // new channel starts, which looks like the channel switch silently did nothing.
        if (surfaceContainer.surface !== surface) return
        // The host takes the surface away on transient events - the phone display going to sleep is
        // one of them - and hands it back a moment later. Pausing without recording why left TV
        // playback stopped for good: the car screen kept the last frame and only the Play button
        // could restart it.
        resumeWhenSurfaceReturns = media.player?.playWhenReady == true
        detach()
        media.pause()
    }

    override fun onGetTemplate(): Template {
        // The "park to watch" message is not an error - it clears itself once the car stops - so
        // it gets no retry action, unlike a real setup/playback failure.
        val retryable = failure != null || playbackFailure != null
        val message = failure ?: playbackFailure
            ?: if (!allowed()) carContext.getString(R.string.car_video_park_to_watch) else null
        if (message != null) {
            val pane = Pane.Builder().addRow(Row.Builder().setTitle(title).addText(message).build())
            val header = Header.Builder()
                .setTitle(carContext.getString(R.string.car_video_title))
                .setStartHeaderAction(Action.BACK)
                .apply {
                    // Icon-only, matching CarWeatherScreen's header refresh action rather than a
                    // full-width Pane button.
                    if (retryable) {
                        addEndHeaderAction(
                            Action.Builder()
                                .setIcon(CarIcons.of(carContext, CarIcons.REFRESH))
                                .setOnClickListener { retry() }
                                .build()
                        )
                    }
                }
                .build()
            return PaneTemplate.Builder(pane.build()).setHeader(header).build()
        }
        val player = media.player
        return NavigationTemplate.Builder()
            .apply {
                // A card over the surface naming the channel that is on its way, while the old
                // picture may still be on the host's surface.
                if (loadingNewChannel) {
                    setNavigationInfo(
                        MessageInfo.Builder(carContext.getString(R.string.car_video_loading))
                            .setText(title)
                            .build()
                    )
                }
            }
            .setActionStrip(ActionStrip.Builder()
                .addAction(Action.BACK)
                // Home and Play/Pause are icons only: with titles the host had room for three of the
                // four buttons and silently dropped the last one, which was Close.
                .addAction(control(R.drawable.ic_car_home, carContext.getString(R.string.car_video_home), iconOnly = true) { screenManager.popToRoot() })
                .addAction(control(if (media.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    carContext.getString(
                        if (media.isPlaying) R.string.car_now_pause else R.string.car_now_play
                    ),
                    player?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true,
                    iconOnly = true
                ) {
                    if (media.isPlaying) media.pause() else if (allowed()) media.resume()
                })
                // Back and Home only leave the screen and the channel carries on paused in the
                // media card; this is the one way to turn it off.
                .addAction(control(R.drawable.ic_car_stop, carContext.getString(R.string.car_video_close)) {
                    resumeWhenSurfaceReturns = false
                    media.close()
                    screenManager.pop()
                })
                .build())
            .setMapActionStrip(ActionStrip.Builder()
                .addAction(control(android.R.drawable.ic_media_rew, carContext.getString(R.string.car_video_back_10), player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true, mapAction = true) { seek(-10_000L) })
                .addAction(control(android.R.drawable.ic_media_ff, carContext.getString(R.string.car_video_forward_10), player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true, mapAction = true) { seek(10_000L) })
                .build())
            .build()
    }

    /**
     * Re-attempts playback after a setup or playback failure. A playback failure leaves the
     * player's current item in place with its error still latched, so attach()'s usual
     * "already on this channel" adoption would just re-render the broken state - the retry has to
     * re-issue play() itself rather than go through that path.
     */
    private fun retry() {
        if (playbackFailure != null) {
            playbackFailure = null
            if (media.isConnected) {
                started = true
                loadingNewChannel = true
                startPlayback()
            } else {
                started = false
            }
            invalidate()
            return
        }
        failure = null
        started = false
        if (media.isConnected) {
            attach()
        } else {
            media.connect(onConnected = {
                media.player?.addListener(listener)
                attach()
                invalidate()
            }, onError = {
                failure = carContext.getString(R.string.car_video_player_failed)
                invalidate()
            })
        }
        invalidate()
    }

    /** Opens this screen's channel — with its queue, when it was opened from a list. */
    private fun startPlayback() {
        val channels = queue
        if (channels == null) {
            media.play(uri, title)
        } else {
            media.playPlaylist(
                channels.channels.map { it.url }, channels.startIndex, channels.channels.map { it.title }
            )
        }
    }

    private fun seek(delta: Long) {
        val player = media.player ?: return
        if (!allowed() || !player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val upper = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + delta).coerceIn(0L, upper))
    }

    private fun control(icon: Int, description: String, enabled: Boolean = true, mapAction: Boolean = false, iconOnly: Boolean = false, action: () -> Unit): Action =
        Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon))
            .build()).setEnabled(enabled).apply { if (!mapAction && !iconOnly) setTitle(description) }
            .setOnClickListener { action(); invalidate() }.build()
}
