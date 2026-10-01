package dev.autobridge.media

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.autobridge.audio.WebMediaStatus

/**
 * Presents a web page's `<audio>`/`<video>` as a Media3 [Player], so the media session (and through
 * it the Android Auto media card and steering-wheel buttons) can show and control browser audio.
 *
 * It owns no playback. State is whatever [update] last reported from the page; play, pause and seek
 * are forwarded to [WebMediaHub.source]. Next/previous are not offered: a page's own media-session
 * handlers cannot be invoked from outside it, and a guessed "next" button would be worse than none.
 *
 * The set/prepare commands are advertised but inert here. [CarMediaPlayer] intercepts set-items to
 * switch back to ExoPlayer; they must still be listed, or the session would reject a controller's
 * "play this file" while the web page is the active source.
 */
@OptIn(UnstableApi::class)
class WebMediaPlayer(looper: Looper) : SimpleBasePlayer(looper) {
    companion object {
        const val MEDIA_ID = "autobridge:web"
    }

    private var status = WebMediaStatus()
    private var state: State = buildState(status)

    /** Publishes a fresh reading from the page. */
    fun update(newStatus: WebMediaStatus) {
        if (newStatus == status) return
        status = newStatus
        state = buildState(newStatus)
        invalidateState()
    }

    override fun getState(): State = state

    private fun buildState(current: WebMediaStatus): State {
        val seekable = current.durationMs > 0
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
            )
            .apply { if (seekable) add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }
            .build()

        val metadata = MediaMetadata.Builder()
            .setTitle(current.title.ifBlank { current.pageTitle.ifBlank { "Browser" } })
            .setArtist(current.artist.takeIf { it.isNotBlank() })
            .setAlbumTitle(current.album.takeIf { it.isNotBlank() })
            .setArtworkUri(current.artworkUrl.takeIf { it.startsWith("https://") }?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        val item = MediaItemData.Builder(MEDIA_ID)
            .setMediaItem(MediaItem.Builder().setMediaId(MEDIA_ID).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(seekable)
            .setDurationUs(if (seekable) current.durationMs * 1000 else C.TIME_UNSET)
            .build()

        // Built once per reading: the extrapolating supplier starts its clock when it is created,
        // so rebuilding it on every getState() call would freeze the position at the last reading.
        val position = if (current.playing) {
            PositionSupplier.getExtrapolating(current.positionMs, 1f)
        } else {
            PositionSupplier.getConstant(current.positionMs)
        }

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(position)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(current.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val target = WebMediaHub.source
        if (target != null) {
            if (playWhenReady) target.play() else target.pause()
            // Optimistic, so the card flips at once; the next poll confirms or corrects it.
            update(status.copy(playing = playWhenReady))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (seekCommand == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM && positionMs != C.TIME_UNSET) {
            WebMediaHub.source?.seekTo(positionMs)
            update(status.copy(positionMs = positionMs.coerceAtLeast(0L)))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        WebMediaHub.source?.pause()
        update(status.copy(playing = false))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
