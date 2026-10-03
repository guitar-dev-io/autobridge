package dev.autobridge.car

import android.content.Intent
import androidx.annotation.StringRes
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
import dev.autobridge.R
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
    private data class Track(
        val title: String,
        @StringRes val subtitleRes: Int,
        val uri: String
    )

    private val mediaPlayback = MediaPlaybackClient(carContext)

    private val audioLibrary = listOf(
        Track(
            "Jazz in Paris",
            R.string.car_lib_sample_audio,
            "https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3"
        ),
        Track(
            "Play",
            R.string.car_lib_sample_audio,
            "https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"
        ),
        Track(
            "Wonderful World",
            R.string.car_lib_sample_audio,
            "https://storage.googleapis.com/exoplayer-test-media-0/wonderful_world.mp3"
        )
    )

    private val videoLibrary = listOf(
        Track(
            "Frame counter",
            R.string.car_lib_sample_video,
            "https://storage.googleapis.com/exoplayer-test-media-1/mp4/frame-counter-one-hour.mp4"
        ),
        Track(
            "Tears of Steel (DASH)",
            R.string.car_lib_adaptive_video,
            "https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/dash/tears-of-steel-multi-lang.mpd"
        )
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
                if (videoOnly != false) addItem(
                    Row.Builder().setTitle(carContext.getString(R.string.car_lib_open_video_link))
                    .addText(carContext.getString(R.string.car_lib_open_video_link_caption)).setBrowsable(true)
                    .setOnClickListener {
                        screenManager.pushForResult(CarBrowserSearchScreen(carContext, "")) { result ->
                            val url = dev.autobridge.entertainment.ContentAddress.https(result as? String ?: "")
                            if (url != null) CarVideoLauncher.open(
                                screenManager, carContext, url,
                                carContext.getString(R.string.car_lib_video_stream)
                            )
                            else CarToast.makeText(
                                carContext,
                                carContext.getString(R.string.car_lib_invalid_video_link),
                                CarToast.LENGTH_SHORT
                            ).show()
                        }
                    }.build()
                )
            }
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_lib_open_file))
                    .addText(carContext.getString(R.string.car_lib_open_file_caption))
                    .setBrowsable(true)
                    .setOnClickListener { openFilePicker() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_lib_now_playing))
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
                    .setTitle(
                        carContext.getString(
                            when (videoOnly) {
                                true -> R.string.car_lib_video
                                false -> R.string.car_lib_music
                                null -> R.string.car_lib_media_library
                            }
                        )
                    )
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .addSectionedList(SectionedItemList.create(browseList, carContext.getString(R.string.car_lib_section_library)))
            .apply {
                if (videoOnly != true) addSectionedList(SectionedItemList.create(audioList, carContext.getString(R.string.car_lib_section_audio)))
                if (videoOnly != false) addSectionedList(SectionedItemList.create(videoList, carContext.getString(R.string.car_lib_section_video)))
            }
            .build()
    }

    private fun trackRow(track: Track, connected: Boolean, video: Boolean): Row =
        Row.Builder()
            .setTitle(track.title)
            .addText(carContext.getString(track.subtitleRes))
            .setEnabled(connected)
            .setOnClickListener {
                if (video) {
                    CarVideoLauncher.open(screenManager, carContext, track.uri, track.title)
                } else {
                    mediaPlayback.play(track.uri, track.title)
                    CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) }
                }
            }
            .build()

    private fun nowPlayingSubtitle(connected: Boolean): String {
        if (!connected) return carContext.getString(R.string.car_lib_connecting_session)
        val title = mediaPlayback.currentTitle
            ?: return carContext.getString(R.string.car_lib_nothing_playing)
        val state = carContext.getString(
            if (mediaPlayback.isPlaying) R.string.car_lib_playing else R.string.car_lib_paused
        )
        return carContext.getString(R.string.car_lib_title_state, title, state)
    }

    private fun openFilePicker() {
        runCatching {
            carContext.startActivity(
                Intent(carContext, EntertainmentActivity::class.java)
                    .putExtra(EntertainmentActivity.EXTRA_BROWSER_MODE, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_lib_pick_on_phone),
                CarToast.LENGTH_LONG
            ).show()
        }.onFailure {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_lib_picker_failed),
                CarToast.LENGTH_SHORT
            ).show()
        }
    }
}
