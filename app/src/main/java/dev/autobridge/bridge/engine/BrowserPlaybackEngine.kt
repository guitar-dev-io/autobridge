package dev.autobridge.bridge.engine

import android.content.Context
import dev.autobridge.bridge.BridgeError
import dev.autobridge.bridge.BridgeErrorType
import dev.autobridge.bridge.BridgeLog
import dev.autobridge.bridge.BridgePlaybackState
import dev.autobridge.bridge.BridgeSource
import dev.autobridge.bridge.ContentRouter
import dev.autobridge.bridge.EngineKind
import dev.autobridge.bridge.EngineState
import dev.autobridge.browser.BrowserResumePoint
import dev.autobridge.browser.CarBrowserRuntime
import dev.autobridge.media.WebMediaHub
import dev.autobridge.remote.CarScreenController
import java.net.URI
import java.util.Locale

/**
 * Renders a page on the car's existing browser surface.
 *
 * This engine owns almost nothing. The car WebView, its surface, its chrome and its lifetime
 * already belong to [CarBrowserRuntime] and `CarBrowserScreen`, and the page's `<video>` is
 * already reachable through [WebMediaHub] — that is how the Android Auto media card and the
 * steering-wheel buttons reach a playing page today. Re-implementing any of it here would mean
 * two WebViews and two ideas of what is playing.
 *
 * So the engine is an adapter: route a source to the car browser, read the page's media state
 * back out, and translate play/pause/seek into the hub calls that already exist. The value it
 * adds is that the manager and the car player UI can treat a web page and an MP4 the same way.
 *
 * ## The resume point
 *
 * A page cannot be told "start at 12:34" in general — there is no web mechanism for it. The one
 * place it works is a URL that encodes its own position, which [BrowserResumePoint] knows how to
 * write for YouTube watch pages. Everything else is opened at the top, and the position is kept
 * in the snapshot so the handoff back to the phone still has it.
 */
class BrowserPlaybackEngine(context: Context) : PlaybackEngine {

    override val kind = EngineKind.BROWSER

    override var onStateChanged: ((EngineState) -> Unit)? = null

    private val appContext = context.applicationContext

    private var source: BridgeSource? = null
    private var playback = BridgePlaybackState.IDLE
    private var error: BridgeError? = null
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var released = false

    override fun canHandle(source: BridgeSource): Boolean {
        val uri = runCatching { URI(source.url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        if (host.isNullOrBlank()) return false
        // A protected host renders a player that will never show a picture here. Refusing it is
        // what turns a black screen into a message; see ContentRouter for why this is a refusal
        // rather than a fallback to some other engine.
        return !ContentRouter.isProtected(host)
    }

    override fun open(source: BridgeSource) {
        if (released) return
        val target = CarScreenController.requireBrowser()
        if (target == null) {
            error = BridgeError(BridgeErrorType.CAR_NOT_CONNECTED, "no car browser")
            playback = BridgePlaybackState.ERROR
            BridgeLog.w("browser.open_refused", "reason" to "car not connected", "url" to source.url)
            emit()
            return
        }

        val url = BrowserResumePoint.withResumeAt(source.url, source.positionMs, Long.MAX_VALUE / 2)
        this.source = source.copy(url = url)
        error = null
        playback = BridgePlaybackState.LOADING
        emit()

        BridgeLog.i(
            "browser.open",
            "url" to url,
            "resumed" to (url != source.url),
            "positionMs" to source.positionMs
        )
        target.openUrl(url)
    }

    override fun play() {
        if (released) return
        val hub = WebMediaHub.source
        if (hub == null) {
            BridgeLog.w("browser.play_unavailable")
            return
        }
        hub.play()
    }

    override fun pause() {
        if (released) return
        WebMediaHub.source?.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        WebMediaHub.source?.seekTo(positionMs)
    }

    /** Loads the next queued page, which is the only "next" a browser surface can honour. */
    fun skipToNext(): Boolean = WebMediaHub.source?.skipToNext() ?: false

    /**
     * Asks the live page where it stands and reports the answer through [onStateChanged].
     *
     * Reading the page is a round trip through JavaScript, so it is a request rather than a
     * property: the car UI calls this on its own refresh tick instead of this engine polling a
     * WebView on a timer nobody asked for.
     */
    fun refresh() {
        if (released) return
        val renderer = CarBrowserRuntime.rendererOrNull()
        if (renderer == null) {
            if (playback != BridgePlaybackState.IDLE) {
                playback = BridgePlaybackState.IDLE
                emit()
            }
            return
        }
        if (renderer.hasError) {
            error = BridgeError(BridgeErrorType.WEB_RENDER_ERROR, "renderer reported a load error")
            playback = BridgePlaybackState.ERROR
            BridgeLog.w("browser.render_error", "url" to (renderer.url))
            emit()
            return
        }
        renderer.readWebMediaStatus { status ->
            lastPositionMs = status.positionMs
            lastDurationMs = status.durationMs
            error = null
            playback = when {
                renderer.isLoading -> BridgePlaybackState.LOADING
                status.ended -> BridgePlaybackState.ENDED
                status.playing -> BridgePlaybackState.PLAYING
                else -> BridgePlaybackState.PAUSED
            }
            val title = status.title.ifBlank { status.pageTitle }
            source = source?.copy(
                title = title.ifBlank { source?.title.orEmpty() },
                positionMs = status.positionMs
            )
            emit()
        }
    }

    override fun release() {
        if (released) return
        released = true
        // The renderer is NOT torn down here. It belongs to the car session (see CarBrowserRuntime),
        // outlives any single screen on purpose, and releasing it from an engine would throw away
        // the page, its history and its tabs every time the bridge switched engines.
        playback = BridgePlaybackState.IDLE
        BridgeLog.i("browser.released")
        emit()
    }

    override fun snapshot(): EngineState = EngineState(
        kind = kind,
        playback = playback,
        source = source,
        positionMs = lastPositionMs,
        durationMs = lastDurationMs,
        error = error
    )

    private fun emit() = onStateChanged?.invoke(snapshot())
}
