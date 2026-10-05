package dev.autobridge.media

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.autobridge.audio.WebMediaStatus
import dev.autobridge.logging.StructuredLog

/** A browser surface whose page audio the media session can read and control. */
interface WebMediaSource {
    fun readMediaStatus(onResult: (WebMediaStatus) -> Unit)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /**
     * Loads the next page in [dev.autobridge.browser.BrowserPlayQueue], consuming it.
     *
     * The queue belongs to this app, so unlike a site's own "up next" this is something the native
     * layer can actually act on. Returns false when the queue is empty, which is how the caller
     * knows to leave the finished page where it is.
     */
    fun skipToNext(): Boolean
}

/**
 * Hands the car browser's page audio to [MediaPlaybackService], in the same process-wide store
 * shape as [VideoOutputGeometry]: the WebView lives in the car browser, the session in the service,
 * and neither owns the other.
 *
 * While a source is registered the hub also holds a [MediaController] connection to the service.
 * That binding is what keeps the session alive while only the browser is making sound, so Android
 * Auto still has a media session to show in its media card when nothing in ExoPlayer is playing.
 *
 * Main thread only: the source is a WebView, and the service polls it from its main handler.
 */
object WebMediaHub {
    private val mainHandler = Handler(Looper.getMainLooper())

    var source: WebMediaSource? = null
        private set

    private var keepAlive: ListenableFuture<MediaController>? = null

    fun register(context: Context, newSource: WebMediaSource) {
        runOnMain {
            if (source === newSource) return@runOnMain
            source = newSource
            if (keepAlive == null) {
                val appContext = context.applicationContext
                val token = SessionToken(appContext, ComponentName(appContext, MediaPlaybackService::class.java))
                keepAlive = MediaController.Builder(appContext, token).buildAsync()
            }
            StructuredLog.i("MEDIA", "web media source registered")
        }
    }

    fun unregister(oldSource: WebMediaSource) {
        runOnMain {
            if (source !== oldSource) return@runOnMain
            source = null
            keepAlive?.let { MediaController.releaseFuture(it) }
            keepAlive = null
            StructuredLog.i("MEDIA", "web media source unregistered")
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
