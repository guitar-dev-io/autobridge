package dev.autobridge.bridge.engine

import android.content.Context
import android.view.Surface
import dev.autobridge.bridge.BridgeError
import dev.autobridge.bridge.BridgeErrorType
import dev.autobridge.bridge.BridgeLog
import dev.autobridge.bridge.BridgePlaybackState
import dev.autobridge.bridge.BridgeSource
import dev.autobridge.bridge.ContentRouter
import dev.autobridge.bridge.EngineKind
import dev.autobridge.bridge.EngineState
import dev.autobridge.remotestream.ControlMessage
import dev.autobridge.remotestream.RemoteStreamConfig
import dev.autobridge.remotestream.RemoteStreamReceiver
import dev.autobridge.remotestream.RemoteStreamStatus
import java.net.URI
import java.util.Locale

/**
 * Plays content another machine is rendering.
 *
 * The host opens the page or the player, encodes what it draws as H.264, and ships it here; this
 * decodes it onto the car surface and sends the driver's commands back. Nothing about the car
 * browser or the native player is involved, which is the isolation the spec asks for: deleting
 * this file and [dev.autobridge.remotestream] would leave the other two engines untouched.
 *
 * ## What it is for, and what it is not for
 *
 * It is for content the car's own WebView renders badly or not at all — a site that needs a
 * desktop browser, a codec Chromium on the phone does not have, a layout that only works at a
 * size the car cannot give it.
 *
 * It is **not** a way around DRM. [canHandle] refuses a protected host outright, and
 * [ContentRouter.fallback] refuses to route one here even after a browser failure, so there is
 * no path — deliberate or accidental — by which a Netflix page becomes a remote stream. A host
 * that chose to render protected content would be doing that on its own account and would see
 * the same black frames the car does; this app neither asks it to nor helps it.
 */
class RemoteStreamPlaybackEngine(context: Context) : PlaybackEngine {

    override val kind = EngineKind.REMOTE_STREAM

    override var onStateChanged: ((EngineState) -> Unit)? = null

    private val appContext = context.applicationContext

    private var receiver: RemoteStreamReceiver? = null
    private var source: BridgeSource? = null
    private var playback = BridgePlaybackState.IDLE
    private var error: BridgeError? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var released = false

    private var surface: Surface? = null

    /** The stream's own status, which the car screen shows verbatim. */
    var status: RemoteStreamStatus = RemoteStreamStatus.DISCONNECTED
        private set

    override fun canHandle(source: BridgeSource): Boolean {
        if (!RemoteStreamConfig.isAvailable(appContext)) return false
        val host = runCatching { URI(source.url).host }.getOrNull()?.lowercase(Locale.ROOT)
        if (host != null && ContentRouter.isProtected(host)) return false
        return true
    }

    override fun open(source: BridgeSource) {
        if (released) return
        val endpoint = RemoteStreamConfig.endpoint(appContext)
        if (endpoint == null) {
            error = BridgeError(BridgeErrorType.REMOTE_STREAM_ERROR, "no stream host configured")
            playback = BridgePlaybackState.ERROR
            BridgeLog.w("remote.open_refused", "reason" to "no host configured")
            emit()
            return
        }

        this.source = source
        error = null
        playback = BridgePlaybackState.LOADING
        emit()
        BridgeLog.i("remote.open", "url" to source.url, "endpoint" to endpoint)

        if (receiver == null) {
            receiver = RemoteStreamReceiver(endpoint).also { created ->
                created.onStatus = { next, detail -> onStatus(next, detail) }
                created.onHostStatus = { message -> onHostStatus(message) }
                created.setSurface(surface)
                created.start()
            }
        }
        // The open is a control message like any other: the host is already connected (or is
        // about to be), and telling it what to render is not a different kind of operation from
        // telling it to pause.
        receiver?.send(ControlMessage.Open(source.url, source.positionMs))
    }

    private fun onStatus(next: RemoteStreamStatus, detail: String?) {
        status = next
        when (next) {
            RemoteStreamStatus.CONNECTING -> playback = BridgePlaybackState.LOADING
            RemoteStreamStatus.BUFFERING -> playback = BridgePlaybackState.LOADING
            RemoteStreamStatus.CONNECTED -> {
                error = null
                if (playback != BridgePlaybackState.PAUSED) playback = BridgePlaybackState.PLAYING
            }
            RemoteStreamStatus.DISCONNECTED -> playback = BridgePlaybackState.IDLE
            RemoteStreamStatus.ERROR -> {
                error = BridgeError(BridgeErrorType.REMOTE_STREAM_ERROR, detail)
                playback = BridgePlaybackState.ERROR
            }
        }
        emit()
    }

    private fun onHostStatus(message: dev.autobridge.remotestream.HostStatusMessage) {
        positionMs = message.positionMs
        durationMs = message.durationMs
        message.title?.let { title -> source = source?.copy(title = title) }
        if (message.error != null) {
            error = BridgeError(BridgeErrorType.REMOTE_STREAM_ERROR, message.error)
            playback = BridgePlaybackState.ERROR
        } else {
            playback = when (message.state.lowercase(Locale.ROOT)) {
                "playing" -> BridgePlaybackState.PLAYING
                "paused" -> BridgePlaybackState.PAUSED
                "buffering", "loading" -> BridgePlaybackState.LOADING
                "ended" -> BridgePlaybackState.ENDED
                else -> playback
            }
        }
        emit()
    }

    override fun play() {
        receiver?.send(ControlMessage.Playback(ControlMessage.Playback.Action.PLAY))
    }

    override fun pause() {
        receiver?.send(ControlMessage.Playback(ControlMessage.Playback.Action.PAUSE))
    }

    override fun seekTo(positionMs: Long) {
        receiver?.send(
            ControlMessage.Playback(ControlMessage.Playback.Action.SEEK, positionMs.coerceAtLeast(0L))
        )
    }

    /** Forwards one of the non-playback commands (keyboard, D-pad, scroll) to the host. */
    fun sendControl(message: ControlMessage) {
        receiver?.send(message)
    }

    override fun attachSurface(surface: Surface?, width: Int, height: Int) {
        this.surface = surface
        receiver?.setSurface(surface)
    }

    override fun release() {
        if (released) return
        released = true
        receiver?.release()
        receiver = null
        surface = null
        playback = BridgePlaybackState.IDLE
        status = RemoteStreamStatus.DISCONNECTED
        BridgeLog.i("remote.engine_released")
        emit()
    }

    override fun snapshot(): EngineState = EngineState(
        kind = kind,
        playback = playback,
        source = source,
        positionMs = positionMs,
        durationMs = durationMs,
        error = error
    )

    private fun emit() = onStateChanged?.invoke(snapshot())
}
