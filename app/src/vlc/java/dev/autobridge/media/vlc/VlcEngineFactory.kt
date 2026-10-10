package dev.autobridge.media.vlc

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import dev.autobridge.settings.VideoSettings

/**
 * Builds the libVLC-backed [Player] for [dev.autobridge.media.MediaPlaybackService]. This is the
 * `src/vlc` copy, compiled in only under `-Pautobridge.vlc=true`; the matching `src/novlc` copy
 * throws, and the service references this one stable symbol regardless of flag state.
 *
 * The returned [VlcMediaPlayer] wraps a real [LibVlcBackend] and is seeded with the user's aspect
 * ratio so the first frame already honours it.
 */
object VlcEngineFactory {
    fun create(context: Context): Player {
        val backend = LibVlcBackend(context)
        return VlcMediaPlayer(Looper.getMainLooper(), context, backend).also {
            it.applyAspectRatio(VideoSettings.aspectRatio(context))
        }
    }
}
