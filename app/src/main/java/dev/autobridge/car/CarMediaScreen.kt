package dev.autobridge.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.media.MediaPlaybackClient

/** Car-native media controls backed by the same Media3 MediaSession as the phone UI. */
class CarMediaScreen(carContext: CarContext) : Screen(carContext) {
    private val mediaPlayback = MediaPlaybackClient(carContext)

    init {
        mediaPlayback.connect { invalidate() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                mediaPlayback.disconnect()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = if (mediaPlayback.isPlaying) "Playing" else "Paused / stopped"
        val connection = when (mediaPlayback.connectionState) {
            MediaPlaybackClient.ConnectionState.CONNECTED -> "Session connected"
            MediaPlaybackClient.ConnectionState.CONNECTING -> "Connecting to MediaSession…"
            MediaPlaybackClient.ConnectionState.ERROR -> "Session unavailable: ${mediaPlayback.lastErrorMessage ?: "unknown error"}"
            MediaPlaybackClient.ConnectionState.DISCONNECTED -> "Session disconnected"
        }
        val canControl = mediaPlayback.isConnected
        val nowPlayingRow = Row.Builder()
            .setTitle(mediaPlayback.currentTitle ?: "AutoBridge Media")
            .addText(nowPlayingSubtitle(state, connection))
            .setEnabled(false)
            .apply { artworkIcon()?.let { setImage(it) } }
            .build()
        val list = ItemList.Builder()
            .addItem(nowPlayingRow)
            .addItem(
                Row.Builder()
                    .setTitle("Play sample playlist")
                    .addText("Jazz, progressive audio and test media")
                    .setEnabled(canControl)
                    .setOnClickListener {
                        mediaPlayback.playPlaylist(samplePlaylist())
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(if (mediaPlayback.isPlaying) "Pause" else "Resume")
                    .addText("MediaSession play/pause")
                    .setEnabled(canControl)
                    .setOnClickListener {
                        if (mediaPlayback.isPlaying) mediaPlayback.pause() else mediaPlayback.resume()
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Previous track")
                    .addText("Previous item in the current queue")
                    .setEnabled(canControl)
                    .setOnClickListener {
                        mediaPlayback.previous()
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Next track")
                    .addText("Next item in the current queue")
                    .setEnabled(canControl)
                    .setOnClickListener {
                        mediaPlayback.next()
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Stop")
                    .addText("Stop audio playback")
                    .setEnabled(canControl)
                    .setOnClickListener {
                        mediaPlayback.stop()
                        invalidate()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Media")
                    .setStartHeaderAction(androidx.car.app.model.Action.BACK)
                    .build()
            )
            .setSingleList(list)
            .build()
    }

    private fun nowPlayingSubtitle(state: String, connection: String): String {
        val artist = mediaPlayback.currentArtist
        return when {
            mediaPlayback.currentTitle != null && artist != null -> "$artist • $state"
            mediaPlayback.currentTitle != null -> state
            else -> "$state • $connection"
        }
    }

    private fun artworkIcon(): CarIcon? {
        val data = mediaPlayback.currentArtworkData ?: return null
        val bitmap: Bitmap = runCatching {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        }.getOrNull() ?: return null
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private fun samplePlaylist(): List<String> = listOf(
        "https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3",
        "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3",
        "https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/dash/tears-of-steel-multi-lang.mpd"
    )
}
