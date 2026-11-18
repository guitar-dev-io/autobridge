package dev.autobridge.rear

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * The passengers' player, shared by the wired rear screen ([RearScreenActivity]) and the Wi-Fi
 * tablet ([dev.autobridge.rear.wifi.RearReceiverActivity]).
 *
 * Its own ExoPlayer, never the app's MediaSession player, and it takes no audio focus: whatever
 * the car is playing keeps playing. [muted] is how the rear shows the same video as the front
 * without the two copies echoing through one set of speakers.
 *
 * Main thread only, like ExoPlayer. [onChange] runs after anything that changes what the phone's
 * notification or the tablet's status should say.
 */
// PlayerView's buffering setter is @UnstableApi in Media3; deliberate, as in PlayerActivity.
@androidx.annotation.OptIn(UnstableApi::class)
class RearPlayer(context: Context, private val onChange: () -> Unit) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context)))
        .build()
        .apply {
            // No audio focus: the car's own audio keeps playing.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false
            )
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = onChange()
            })
        }

    var muted = false
        private set
    var title = ""
        private set

    val isPlaying: Boolean get() = player.isPlaying
    val hasMedia: Boolean get() = player.mediaItemCount > 0

    fun view(context: Context): PlayerView = PlayerView(context).apply {
        setBackgroundColor(0xFF000000.toInt())
        player = this@RearPlayer.player
        useController = true
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
    }

    fun play(url: String, title: String, positionMs: Long, muted: Boolean) {
        this.title = title
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        if (positionMs > 0) player.setMediaItem(item, positionMs) else player.setMediaItem(item)
        setMuted(muted)
        player.prepare()
        player.play()
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun setMuted(value: Boolean) {
        muted = value
        player.volume = if (value) 0f else 1f
        onChange()
    }

    /** Stops and forgets the video, leaving the player ready for the next one. */
    fun stop() {
        player.stop()
        player.clearMediaItems()
        title = ""
        onChange()
    }

    fun release() = player.release()
}
