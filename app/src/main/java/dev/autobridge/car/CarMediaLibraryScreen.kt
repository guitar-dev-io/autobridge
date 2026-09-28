package dev.autobridge.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.entertainment.EntertainmentActivity
import dev.autobridge.media.MediaPlaybackClient

/**
 * Fermata-style media library for the car. Lets the driver open on-device audio/video via the
 * phone's document picker (routed through [EntertainmentActivity]) and offers ready-to-play sample
 * tracks streamed through the shared MediaSession, so audio works with no screen-share at all.
 *
 * Sample videos open CarVideoScreen, which attaches the car surface to the shared MediaSession.
 * The phone file picker remains available for local media.
 */
class CarMediaLibraryScreen(carContext: CarContext, private val videoOnly: Boolean? = null) : Screen(carContext) {
    private data class Track(val title: String, val subtitle: String, val uri: String)

    private val mediaPlayback = MediaPlaybackClient(carContext)

    private val audioLibrary = listOf(
        Track("Jazz in Paris", "Sample audio • MP3", "https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3"),
        Track("Play", "Sample audio • MP3", "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"),
        Track("Wonderful World", "Sample audio • MP3", "https://storage.googleapis.com/exoplayer-test-media-0/wonderful_world.mp3")
    )

    private val videoLibrary = listOf(
        Track("Frame counter", "Sample video • MP4", "https://storage.googleapis.com/exoplayer-test-media-1/mp4/frame-counter-one-hour.mp4"),
        Track("Tears of Steel (DASH)", "Adaptive video • MPD", "https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/dash/tears-of-steel-multi-lang.mpd")
    )

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                mediaPlayback.disconnect()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val connected = mediaPlayback.isConnected

        val browseList = ItemList.Builder()
            .apply {
                if (videoOnly != false) addItem(Row.Builder().setTitle("Open video link")
                    .addText("MP4, HLS or DASH stream").setBrowsable(true)
                    .setOnClickListener {
                        screenManager.pushForResult(CarBrowserSearchScreen(carContext, "")) { result ->
                            val url = dev.autobridge.entertainment.ContentAddress.https(result as? String ?: "")
                            if (url != null) screenManager.push(CarVideoScreen(carContext, url, "Video stream"))
                            else CarToast.makeText(carContext, "Enter a valid HTTPS video link", CarToast.LENGTH_SHORT).show()
                        }
                    }.build())
            }
            .addItem(
                Row.Builder()
                    .setTitle("Open file from phone")
                    .addText("Pick audio or video from device storage")
                    .setBrowsable(true)
                    .setOnClickListener { openFilePicker() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Now Playing")
                    .addText(nowPlayingSubtitle(connected))
                    .setBrowsable(true)
                    .setOnClickListener { CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) } }
                    .build()
            )
            .build()

        val audioList = ItemList.Builder().apply {
            audioLibrary.forEach { track -> addItem(trackRow(track, connected, false)) }
        }.build()

        val videoList = ItemList.Builder().apply {
            videoLibrary.forEach { track -> addItem(trackRow(track, connected, true)) }
        }.build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(when (videoOnly) { true -> "Video"; false -> "Music"; null -> "Media Library" })
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .addSectionedList(SectionedItemList.create(browseList, "Library"))
            .apply {
                if (videoOnly != true) addSectionedList(SectionedItemList.create(audioList, "Audio"))
                if (videoOnly != false) addSectionedList(SectionedItemList.create(videoList, "Video"))
            }
            .build()
    }

    private fun trackRow(track: Track, connected: Boolean, video: Boolean): Row =
        Row.Builder()
            .setTitle(track.title)
            .addText(track.subtitle)
            .setEnabled(connected)
            .setOnClickListener {
                if (video) {
                    screenManager.push(CarVideoScreen(carContext, track.uri, track.title))
                } else {
                    mediaPlayback.play(track.uri, track.title)
                    CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) }
                }
            }
            .build()

    private fun nowPlayingSubtitle(connected: Boolean): String {
        if (!connected) return "Connecting to media session…"
        val title = mediaPlayback.currentTitle ?: return "Nothing playing"
        val state = if (mediaPlayback.isPlaying) "Playing" else "Paused"
        return "$title • $state"
    }

    private fun openFilePicker() {
        runCatching {
            carContext.startActivity(
                Intent(carContext, EntertainmentActivity::class.java)
                    .putExtra(EntertainmentActivity.EXTRA_BROWSER_MODE, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CarToast.makeText(carContext, "Pick a file on the phone", CarToast.LENGTH_LONG).show()
        }.onFailure {
            CarToast.makeText(carContext, "Could not open the media picker", CarToast.LENGTH_SHORT).show()
        }
    }
}
