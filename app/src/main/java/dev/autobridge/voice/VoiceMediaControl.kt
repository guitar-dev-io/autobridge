package dev.autobridge.voice

import android.content.Context
import dev.autobridge.media.MediaPlaybackClient

/**
 * Next, previous and pause by voice, on the shared media session — the session the steering-wheel
 * buttons and Android Auto's media card already drive, so a spoken "เพลงถัดไป" does exactly what
 * the wheel's Next does: the next channel in a TV or radio list, the next track in a playlist.
 *
 * Connects a short-lived controller, acts once, and lets go. [done] gets false when nothing is
 * loaded, or there is no next/previous item, so the caller can say so instead of claiming it worked.
 */
object VoiceMediaControl {
    /** What to tell the driver after [action] worked. */
    fun doneText(action: VoiceAction): Int = when (action) {
        VoiceAction.MEDIA_NEXT -> dev.autobridge.R.string.voice_media_next
        VoiceAction.MEDIA_PREVIOUS -> dev.autobridge.R.string.voice_media_previous
        else -> dev.autobridge.R.string.voice_media_paused
    }

    fun run(context: Context, action: VoiceAction, done: (Boolean) -> Unit) {
        val client = MediaPlaybackClient(context.applicationContext)
        client.connect(
            onConnected = {
                val ok = act(client, action)
                client.disconnect()
                done(ok)
            },
            onError = {
                client.disconnect()
                done(false)
            }
        )
    }

    private fun act(client: MediaPlaybackClient, action: VoiceAction): Boolean {
        val player = client.player ?: return false
        if (player.mediaItemCount == 0) return false
        return when (action) {
            VoiceAction.MEDIA_NEXT -> client.hasNext.also { if (it) client.next() }
            VoiceAction.MEDIA_PREVIOUS -> client.hasPrevious.also { if (it) client.previous() }
            VoiceAction.MEDIA_PAUSE -> true.also { client.pause() }
            else -> false
        }
    }
}
