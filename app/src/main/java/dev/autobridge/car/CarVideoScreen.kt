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
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.display.StructuredLog
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.media.VideoOutputGeometry
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement

/** Native video output: the shared MediaSession renders to the host surface, without capture. */
class CarVideoScreen(
    carContext: CarContext,
    private val uri: String,
    private val title: String
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
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            playbackFailure = player.playerError?.let {
                carContext.getString(R.string.car_video_source_failed)
            }
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
        player.setVideoSurface(output)
        // Published after the surface, never before: the player letterboxes into this size, and the
        // renderer discards an output resolution that arrives while it still has no surface.
        VideoOutputGeometry.set(surfaceWidth, surfaceHeight)
        if (!started) {
            started = true
            media.play(uri, title)
        } else if (resumeWhenSurfaceReturns) {
            resumeWhenSurfaceReturns = false
            // A live channel has moved on while the surface was gone. Resuming where it stopped
            // either stalls or throws BEHIND_LIVE_WINDOW, so rejoin at the live edge instead.
            if (player.isCurrentMediaItemLive &&
                player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            ) {
                player.seekToDefaultPosition()
            }
            StructuredLog.i("CAR_VIDEO", "resuming playback after the surface came back")
            media.resume()
        }
    }

    private fun detach() {
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
        // The host takes the surface away on transient events - the phone display going to sleep is
        // one of them - and hands it back a moment later. Pausing without recording why left TV
        // playback stopped for good: the car screen kept the last frame and only the Play button
        // could restart it.
        resumeWhenSurfaceReturns = media.player?.playWhenReady == true
        detach()
        media.pause()
    }

    override fun onGetTemplate(): Template {
        val message = failure ?: playbackFailure
            ?: if (!allowed()) carContext.getString(R.string.car_video_park_to_watch) else null
        if (message != null) {
            return PaneTemplate.Builder(Pane.Builder().addRow(Row.Builder().setTitle(title).addText(message).build()).build())
                .setHeader(Header.Builder().setTitle(carContext.getString(R.string.car_video_title)).setStartHeaderAction(Action.BACK).build()).build()
        }
        val player = media.player
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder()
                .addAction(Action.BACK)
                .addAction(control(R.drawable.ic_car_home, carContext.getString(R.string.car_video_home)) { screenManager.popToRoot() })
                .addAction(control(if (media.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    carContext.getString(
                        if (media.isPlaying) R.string.car_now_pause else R.string.car_now_play
                    ),
                    player?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true
                ) {
                    if (media.isPlaying) media.pause() else if (allowed()) media.resume()
                })
                .build())
            .setMapActionStrip(ActionStrip.Builder()
                .addAction(control(android.R.drawable.ic_media_rew, carContext.getString(R.string.car_video_back_10), player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true, mapAction = true) { seek(-10_000L) })
                .addAction(control(android.R.drawable.ic_media_ff, carContext.getString(R.string.car_video_forward_10), player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true, mapAction = true) { seek(10_000L) })
                .build())
            .build()
    }

    private fun seek(delta: Long) {
        val player = media.player ?: return
        if (!allowed() || !player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val upper = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + delta).coerceIn(0L, upper))
    }

    private fun control(icon: Int, description: String, enabled: Boolean = true, mapAction: Boolean = false, action: () -> Unit): Action =
        Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon))
            .build()).setEnabled(enabled).apply { if (!mapAction) setTitle(description) }
            .setOnClickListener { action(); invalidate() }.build()
}
