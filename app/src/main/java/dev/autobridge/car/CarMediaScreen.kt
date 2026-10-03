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
import dev.autobridge.R
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
        val state = carContext.getString(
            if (mediaPlayback.isPlaying) R.string.car_media_playing else R.string.car_media_paused
        )
        val connection = when (mediaPlayback.connectionState) {
            MediaPlaybackClient.ConnectionState.CONNECTED ->
                carContext.getString(R.string.car_media_session_connected)
            MediaPlaybackClient.ConnectionState.CONNECTING ->
                carContext.getString(R.string.car_media_session_connecting)
            MediaPlaybackClient.ConnectionState.ERROR -> carContext.getString(
                R.string.car_media_session_error,
                mediaPlayback.lastErrorMessage
                    ?: carContext.getString(R.string.car_media_session_unknown_error)
            )
            MediaPlaybackClient.ConnectionState.DISCONNECTED ->
                carContext.getString(R.string.car_media_session_disconnected)
        }
        val canControl = mediaPlayback.isConnected
        val nowPlayingRow = Row.Builder()
            .setTitle(mediaPlayback.currentTitle ?: carContext.getString(R.string.car_media_default_title))
            .addText(nowPlayingSubtitle(state, connection))
            .setEnabled(false)
            .apply { artworkIcon()?.let { setImage(it) } }
            .build()
        val list = ItemList.Builder()
            .addItem(nowPlayingRow)
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_media_sample))
                    .addText(carContext.getString(R.string.car_media_sample_caption))
                    .setEnabled(canControl)
                    .setOnClickListener {
                        mediaPlayback.playPlaylist(samplePlaylist())
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(
                        carContext.getString(
                            if (mediaPlayback.isPlaying) R.string.car_media_pause
                            else R.string.car_media_resume
                        )
                    )
                    .addText(carContext.getString(R.string.car_media_play_pause_caption))
                    .setEnabled(canControl)
                    .setOnClickListener {
                        if (mediaPlayback.isPlaying) mediaPlayback.pause() else mediaPlayback.resume()
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_media_previous))
                    .addText(carContext.getString(R.string.car_media_previous_caption))
                    .setEnabled(canControl)
                    .setOnClickListener {
                        mediaPlayback.previous()
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_media_next))
                    .addText(carContext.getString(R.string.car_media_next_caption))
                    .setEnabled(canControl)
                    .setOnClickListener {
                        mediaPlayback.next()
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_media_stop))
                    .addText(carContext.getString(R.string.car_media_stop_caption))
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
                    .setTitle(carContext.getString(R.string.car_media_title))
                    .setStartHeaderAction(androidx.car.app.model.Action.BACK)
                    .build()
            )
            .setSingleList(list)
            .build()
    }

    private fun nowPlayingSubtitle(state: String, connection: String): String {
        val artist = mediaPlayback.currentArtist
        return when {
            mediaPlayback.currentTitle != null && artist != null ->
                carContext.getString(R.string.car_media_artist_state, artist, state)
            mediaPlayback.currentTitle != null -> state
            else -> carContext.getString(R.string.car_media_state_connection, state, connection)
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
