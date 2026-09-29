package dev.autobridge.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.autobridge.MainActivity
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.display.StructuredLog
import dev.autobridge.safety.ParkingStateStore

/** Hosts the long-lived Media3 player and MediaSession used by Android Auto and steering controls. */
class MediaPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
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

        session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(openAppIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
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
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
