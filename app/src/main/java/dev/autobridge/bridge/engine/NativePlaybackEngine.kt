package dev.autobridge.bridge.engine

import android.content.Context
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.autobridge.bridge.BridgeError
import dev.autobridge.bridge.BridgeErrorType
import dev.autobridge.bridge.BridgeLog
import dev.autobridge.bridge.BridgePlaybackState
import dev.autobridge.bridge.BridgeSource
import dev.autobridge.bridge.ContentRouter
import dev.autobridge.bridge.EngineKind
import dev.autobridge.bridge.EngineState
import dev.autobridge.media.VideoOutputGeometry
import dev.autobridge.media.VideoSurfaceArbiter

/**
 * Plays a direct media URL through the app's existing MediaSession.
 *
 * It deliberately owns no player of its own. [dev.autobridge.media.MediaPlaybackService] already
 * holds the ExoPlayer instance, the audio focus and the session that Android Auto's media card,
 * the steering-wheel buttons and the notification all drive; a second player here would mean two
 * things making sound and a media card showing the wrong one. So this is a thin adapter onto
 * [dev.autobridge.media.MediaPlaybackClient] — the same controller the car's video screen and the
 * phone's library already use — reshaped to the [PlaybackEngine] contract.
 *
 * What it adds over calling the client directly is the surface handling and the error mapping:
 * the car's surface has to be attached to the player and published to
 * [VideoOutputGeometry] in that order (the renderer drops a resolution that arrives while it has
 * no surface), and an ExoPlayer failure has to become one of the bridge's error types rather than
 * an error code on the head unit.
 */
class NativePlaybackEngine(context: Context) : PlaybackEngine {

    override val kind = EngineKind.NATIVE

    override var onStateChanged: ((EngineState) -> Unit)? = null

    private val media = dev.autobridge.media.MediaPlaybackClient(context)

    private var source: BridgeSource? = null
    private var playback = BridgePlaybackState.IDLE
    private var error: BridgeError? = null
    private var connected = false
    private var released = false

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Set while waiting for the session to connect, replayed once it does. */
    private var deferred: BridgeSource? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val failure = player.playerError
            if (failure != null) {
                error = mapError(failure)
                playback = BridgePlaybackState.ERROR
                BridgeLog.e(
                    "native.error",
                    "code" to failure.errorCodeName,
                    "type" to error?.type,
                    "url" to source?.url
                )
            } else {
                error = null
                playback = when {
                    player.playbackState == Player.STATE_BUFFERING -> BridgePlaybackState.LOADING
                    player.playbackState == Player.STATE_ENDED -> BridgePlaybackState.ENDED
                    player.isPlaying -> BridgePlaybackState.PLAYING
                    player.playbackState == Player.STATE_IDLE -> BridgePlaybackState.IDLE
                    else -> BridgePlaybackState.PAUSED
                }
            }
            emit()
        }
    }

    override fun canHandle(source: BridgeSource): Boolean =
        ContentRouter.isDirectMedia(source.url, source.mimeType)

    override fun open(source: BridgeSource) {
        if (released) return
        this.source = source
        error = null
        playback = BridgePlaybackState.LOADING
        emit()
        BridgeLog.i("native.open", "url" to source.url, "positionMs" to source.positionMs)

        if (connected) {
            start(source)
            return
        }
        deferred = source
        media.connect(
            onConnected = {
                connected = true
                media.player?.addListener(listener)
                attachToPlayer()
                deferred?.let { start(it) }
                deferred = null
            },
            onError = {
                error = BridgeError(BridgeErrorType.PLAYBACK_ERROR, "media session connect failed")
                playback = BridgePlaybackState.ERROR
                BridgeLog.e("native.connect_failed", "url" to source.url)
                emit()
            }
        )
    }

    private fun start(source: BridgeSource) {
        media.play(source.url, source.displayTitle)
        // The handoff's whole point. A session that was just told to play is not seekable yet in
        // every case, so the seek is attempted and simply does nothing when the command is
        // unavailable — losing the resume point is a worse outcome than a no-op, but throwing on
        // it would be worse than both.
        if (source.positionMs > 0L) seekTo(source.positionMs)
    }

    override fun play() {
        if (released) return
        media.resume()
    }

    override fun pause() {
        if (released) return
        media.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val player = media.player ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val upper = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0L, upper))
    }

    override fun attachSurface(surface: Surface?, width: Int, height: Int) {
        this.surface = surface
        surfaceWidth = width
        surfaceHeight = height
        attachToPlayer()
    }

    private fun attachToPlayer() {
        val player = media.player ?: return
        if (!player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) return
        val output = surface
        if (output == null || !output.isValid) {
            VideoOutputGeometry.clear()
            player.clearVideoSurface()
            VideoSurfaceArbiter.releaseForCar(reassert)
            return
        }
        player.setVideoSurface(output)
        // Order matters: see VideoOutputGeometry. The size is what the renderer letterboxes into,
        // and it is discarded if it arrives before the surface.
        VideoOutputGeometry.set(surfaceWidth, surfaceHeight)
        BridgeLog.i("native.surface", "size" to "${surfaceWidth}x$surfaceHeight")
        // The car now owns the shared player's video output; the phone player lets go of it.
        VideoSurfaceArbiter.claimForCar(reassert)
    }

    /**
     * Re-binds the car surface and repaints. Run by [VideoSurfaceArbiter] after the phone has
     * dropped its binding, so a phone clear that lands late cannot leave the car audio-only.
     */
    private val reassert: () -> Unit = reassert@{
        if (released) return@reassert
        val player = media.player ?: return@reassert
        val output = surface ?: return@reassert
        if (!output.isValid || !player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) return@reassert
        player.setVideoSurface(output)
        VideoOutputGeometry.set(surfaceWidth, surfaceHeight)
        if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            player.seekTo(player.currentPosition)
        }
        BridgeLog.i("native.surface_reasserted")
    }

    override fun release() {
        if (released) return
        released = true
        VideoOutputGeometry.clear()
        VideoSurfaceArbiter.releaseForCar(reassert)
        // Teardown runs while the car session is going away, so every step here races something
        // the platform is also tearing down. A throw on the way out would take the process with
        // it for no gain - whatever is being released is going away regardless.
        runCatching {
            media.player?.let { player ->
                player.removeListener(listener)
                if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) player.clearVideoSurface()
            }
            media.pause()
            media.disconnect()
        }.onFailure { BridgeLog.w("native.release_failed", "reason" to it.message) }
        surface = null
        connected = false
        playback = BridgePlaybackState.IDLE
        BridgeLog.i("native.released")
        emit()
    }

    override fun snapshot(): EngineState {
        val player = media.player
        return EngineState(
            kind = kind,
            playback = playback,
            source = source,
            positionMs = player?.currentPosition ?: 0L,
            durationMs = player?.duration?.takeIf { it > 0 } ?: 0L,
            error = error
        )
    }

    private fun emit() = onStateChanged?.invoke(snapshot())

    /**
     * ExoPlayer's error code turned into one of the bridge's types.
     *
     * The IO range is everything that failed to arrive — DNS, connection, HTTP status, a truncated
     * read — which is a network problem to the user no matter which of them it was. The DRM range
     * is reported as protected content rather than as a playback fault: a Widevine failure on a
     * direct URL means the file is protected, and saying "cannot play" would invite the user to
     * retry something that cannot start working.
     */
    private fun mapError(failure: PlaybackException): BridgeError {
        val code = failure.errorCode
        val type = when (code) {
            in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                BridgeErrorType.NETWORK_ERROR
            in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED ->
                BridgeErrorType.DRM_PROTECTED_CONTENT
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                BridgeErrorType.UNSUPPORTED_CONTENT
            else -> BridgeErrorType.PLAYBACK_ERROR
        }
        return BridgeError(type, "${failure.errorCodeName}: ${failure.message}")
    }
}
