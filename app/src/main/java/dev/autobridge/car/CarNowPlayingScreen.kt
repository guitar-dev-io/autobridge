package dev.autobridge.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.media.MediaPlaybackClient

/**
 * Native "Now Playing" screen drawn entirely by Android Auto (no MediaProjection / screen-share).
 *
 * Unlike [CarMediaScreen] which is a plain scrollable list, this uses a [PaneTemplate] so the
 * artwork, track metadata and transport controls read as a single content surface. It refreshes
 * itself on a light timer so elapsed state and metadata stay current while the driver is parked.
 */
class CarNowPlayingScreen(carContext: CarContext) : Screen(carContext) {
    private val mediaPlayback = MediaPlaybackClient(carContext)
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    init {
        mediaPlayback.connect { invalidate() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)
            }

            override fun onPause(owner: LifecycleOwner) {
                handler.removeCallbacks(refreshTick)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacks(refreshTick)
                mediaPlayback.disconnect()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val connected = mediaPlayback.isConnected
        val playing = mediaPlayback.isPlaying
        val title = mediaPlayback.currentTitle ?: "Nothing playing"
        val subtitle = buildSubtitle(connected, playing)

        val nowPlayingRow = Row.Builder()
            .setTitle(title)
            .addText(subtitle)
            .apply { artworkIcon()?.let { setImage(it, Row.IMAGE_TYPE_LARGE) } }
            .build()

        val pane = Pane.Builder()
            .addRow(nowPlayingRow)
            .addAction(
                Action.Builder()
                    .setTitle(if (playing) "Pause" else "Play")
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setEnabled(connected)
                    .setOnClickListener {
                        if (mediaPlayback.isPlaying) {
                            mediaPlayback.pause()
                        } else if (mediaPlayback.currentTitle != null) {
                            mediaPlayback.resume()
                        } else {
                            mediaPlayback.playPlaylist(samplePlaylist())
                        }
                        invalidate()
                    }
                    .build()
            )
            // PaneTemplate allows at most 2 actions. Keep Play/Pause and Next here; Prev and the
            // rest of the transport live on the Full controls (CarMediaScreen) screen.
            .addAction(
                Action.Builder()
                    .setTitle("Next")
                    .setEnabled(connected)
                    .setOnClickListener {
                        mediaPlayback.next()
                        invalidate()
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setHeader(
                Header.Builder()
                    .setTitle("Now Playing")
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.LIBRARY))
                            .setOnClickListener { CarNavigation.open(screenManager, "CarMediaLibraryScreen") { CarMediaLibraryScreen(carContext) } }
                            .build()
                    )
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.CONTROLS))
                            .setOnClickListener { CarNavigation.open(screenManager, "CarMediaScreen") { CarMediaScreen(carContext) } }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildSubtitle(connected: Boolean, playing: Boolean): String {
        if (!connected) {
            return when (mediaPlayback.connectionState) {
                MediaPlaybackClient.ConnectionState.CONNECTING -> "Connecting to media session…"
                MediaPlaybackClient.ConnectionState.ERROR ->
                    "Session unavailable: ${mediaPlayback.lastErrorMessage ?: "unknown error"}"
                else -> "Media session disconnected • tap Play to start the sample playlist"
            }
        }
        val artist = mediaPlayback.currentArtist
        val state = if (playing) "Playing" else "Paused"
        return when {
            mediaPlayback.currentTitle != null && artist != null -> "$artist • $state"
            mediaPlayback.currentTitle != null -> state
            else -> "Ready • tap Play for the sample playlist"
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
        "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"
    )

    private companion object {
        const val REFRESH_INTERVAL_MS = 2_000L
    }
}
