package dev.autobridge.media

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import dev.autobridge.display.StructuredLog

/** Which player the media session is currently showing. */
enum class SessionSource { EXO, WEB }

/**
 * Chooses the session's source from what is audible. Pure, so the rule stays readable in one
 * place:
 * - ExoPlayer wins whenever it is playing or about to (library, IPTV).
 * - Otherwise a playing web page takes over.
 * - Otherwise nothing changes, so a paused source stays on the card and can be resumed from it.
 * - With no browser at all, ExoPlayer.
 */
object SessionSourceArbiter {
    fun choose(current: SessionSource, exoActive: Boolean, webAvailable: Boolean, webPlaying: Boolean): SessionSource =
        when {
            exoActive -> SessionSource.EXO
            !webAvailable -> SessionSource.EXO
            webPlaying -> SessionSource.WEB
            else -> current
        }
}

/**
 * The player the media session is built on: forwards to ExoPlayer or to [WebMediaPlayer],
 * whichever [SessionSourceArbiter] picked.
 *
 * Loading anything (a controller setting or adding items, the car tapping a queue entry) always
 * switches back to ExoPlayer first, since only ExoPlayer can play an item. The prepare/play that
 * follow then reach ExoPlayer as well, because the session dispatches each command to whatever
 * this forwards to at that moment.
 */
@OptIn(UnstableApi::class)
class CarMediaPlayer(
    private val exo: Player,
    private val web: WebMediaPlayer,
) : ForwardingSimpleBasePlayer(exo) {

    var source: SessionSource = SessionSource.EXO
        private set

    fun select(next: SessionSource) {
        if (next == source) return
        source = next
        setPlayer(if (next == SessionSource.WEB) web else exo)
        StructuredLog.i("MEDIA", "session source -> $next")
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        select(SessionSource.EXO)
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        select(SessionSource.EXO)
        return super.handleAddMediaItems(index, mediaItems)
    }

    /**
     * Releases the web side only. ExoPlayer is owned and released by the service, so it is not
     * released here even when it is the forwarded player.
     */
    override fun handleRelease(): ListenableFuture<*> {
        web.release()
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }
}
