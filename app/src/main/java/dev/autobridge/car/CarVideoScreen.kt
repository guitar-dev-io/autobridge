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
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.safety.ParkingStateStore

/** Native video output: the shared MediaSession renders to the host surface, without capture. */
class CarVideoScreen(
    carContext: CarContext,
    private val uri: String,
    private val title: String
) : Screen(carContext), SurfaceCallback {
    private val appManager = carContext.getCarService(AppManager::class.java)
    private val media = MediaPlaybackClient(carContext)
    private var surface: Surface? = null
    private var active = false
    private var started = false
    private var failure: String? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            player.playerError?.let { failure = "Unable to play this source. Return to the library and try another video." }
            invalidate()
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
                    failure = "Cannot connect to the media player. Return to the library and try again."
                    invalidate()
                })
            }

            override fun onStop(owner: LifecycleOwner) {
                active = false
                ParkingStateStore.removeListener(parkingListener)
                if (MirrorSurfaceOwnership.isOwner(this@CarVideoScreen)) {
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

    private fun allowed() = ParkingStateStore.isParked && FeaturePolicy.app.isAvailable(Feature.VIDEO)

    private fun attach() {
        val output = surface ?: return
        val player = media.player ?: return
        if (!active || !allowed() || !output.isValid || !MirrorSurfaceOwnership.isOwner(this)) return
        if (!player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
            failure = "Video output is unavailable on this connection."
            invalidate()
            return
        }
        player.setVideoSurface(output)
        if (!started) {
            started = true
            media.play(uri, title)
        }
    }

    private fun detach() {
        val output = surface ?: return
        media.player?.let { player ->
            if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) player.clearVideoSurface(output)
        }
        surface = null
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        detach()
        surface = surfaceContainer.surface
        attach()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        detach()
        media.pause()
    }

    override fun onGetTemplate(): Template {
        val message = failure ?: if (!allowed()) "Park the vehicle to watch video." else null
        if (message != null) {
            return PaneTemplate.Builder(Pane.Builder().addRow(Row.Builder().setTitle(title).addText(message).build()).build())
                .setHeader(Header.Builder().setTitle("Video").setStartHeaderAction(Action.BACK).build()).build()
        }
        val player = media.player
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder()
                .addAction(Action.BACK)
                .addAction(control(R.drawable.ic_car_home, "Home") { screenManager.popToRoot() })
                .addAction(control(if (media.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (media.isPlaying) "Pause" else "Play", player?.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) == true) {
                    if (media.isPlaying) media.pause() else if (allowed()) media.resume()
                })
                .build())
            .setMapActionStrip(ActionStrip.Builder()
                .addAction(control(android.R.drawable.ic_media_rew, "Back 10 seconds", player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true, mapAction = true) { seek(-10_000L) })
                .addAction(control(android.R.drawable.ic_media_ff, "Forward 10 seconds", player?.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) == true, mapAction = true) { seek(10_000L) })
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
