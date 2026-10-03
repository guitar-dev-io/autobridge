package dev.autobridge.car

import androidx.annotation.StringRes
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.car.app.model.ListTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.car.app.Screen
import dev.autobridge.R
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.media.MediaPlaybackClient

/**
 * Unified Media Center with Music / Video / Streaming tabs. This does NOT remove the existing Music
 * or Video features — it composes them into one tabbed surface, reusing [MediaPlaybackClient] for
 * audio playback and [CarVideoScreen] for video, exactly as [CarMediaLibraryScreen] does. The
 * standalone library screen remains available.
 */
class CarMediaCenterScreen(carContext: CarContext) : Screen(carContext) {
    private data class Track(
        val title: String,
        @StringRes val subtitleRes: Int,
        val uri: String
    )

    private val mediaPlayback = MediaPlaybackClient(carContext)
    private var activeTab = TAB_MUSIC

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
        val builder = TabTemplate.Builder(
            object : TabTemplate.TabCallback {
                override fun onTabSelected(tabContentId: String) {
                    activeTab = tabContentId
                    invalidate()
                }
            }
        )
            // TabTemplate accepts only APP_ICON here: ActionsConstraints rejects BACK with
            // "Missing required action types: APP_ICON", which crashed the app the moment Media
            // Center was opened on the head unit. The host draws its own back affordance.
            .setHeaderAction(Action.APP_ICON)
            .addTab(tab(carContext.getString(R.string.car_media_center_tab_music), TAB_MUSIC, DashboardArtwork.Kind.MEDIA))
            .addTab(tab(carContext.getString(R.string.car_media_center_tab_video), TAB_VIDEO, DashboardArtwork.Kind.YOUTUBE))
            .addTab(tab(carContext.getString(R.string.car_media_center_tab_streaming), TAB_STREAMING, DashboardArtwork.Kind.BROWSER))
            .setActiveTabContentId(activeTab)
            .setTabContents(TabContents.Builder(contentFor(activeTab)).build())

        return builder.build()
    }

    /**
     * TabTemplate requires an icon on every tab — building one without it throws
     * "A icon must be set for the tab" and takes the whole car app down. The artwork is the same
     * set the dashboard tiles use, so the tabs match the grid they were reached from.
     */
    private fun tab(title: String, contentId: String, artwork: DashboardArtwork.Kind): Tab =
        Tab.Builder()
            .setTitle(title)
            .setContentId(contentId)
            .setIcon(DashboardArtwork.icon(artwork, compact = true))
            .build()

    private fun contentFor(tabId: String): Template = when (tabId) {
        TAB_VIDEO -> videoTemplate()
        TAB_STREAMING -> streamingTemplate()
        else -> musicTemplate()
    }

    private fun musicTemplate(): Template {
        val connected = mediaPlayback.isConnected
        val list = ItemList.Builder()
        list.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.car_media_center_now_playing))
                .addText(nowPlayingSubtitle(connected))
                .setBrowsable(true)
                .setOnClickListener { CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) } }
                .build()
        )
        audioLibrary.forEach { track ->
            list.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(carContext.getString(track.subtitleRes))
                    .setEnabled(connected)
                    .setOnClickListener {
                        mediaPlayback.play(track.uri, track.title)
                        RecentActivityStore.record(
                            carContext,
                            RecentActivityStore.Entry(
                                RecentActivityStore.Kind.MEDIA,
                                track.title,
                                carContext.getString(R.string.car_recent_kind_music),
                                track.uri
                            )
                        )
                        CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) }
                    }
                    .build()
            )
        }
        return ListTemplate.Builder().setSingleList(list.build()).build()
    }

    private fun videoTemplate(): Template {
        val list = ItemList.Builder()
        videoLibrary.forEach { track ->
            list.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(carContext.getString(track.subtitleRes))
                    .setOnClickListener {
                        RecentActivityStore.record(
                            carContext,
                            RecentActivityStore.Entry(
                                RecentActivityStore.Kind.MEDIA,
                                track.title,
                                carContext.getString(R.string.car_recent_kind_video),
                                track.uri
                            )
                        )
                        CarVideoLauncher.open(screenManager, carContext, track.uri, track.title)
                    }
                    .build()
            )
        }
        return ListTemplate.Builder().setSingleList(list.build()).build()
    }

    private fun streamingTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_media_center_open_stream))
                    .addText(carContext.getString(R.string.car_media_center_open_stream_caption))
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.pushForResult(CarBrowserSearchScreen(carContext, "")) { result ->
                            val url = dev.autobridge.entertainment.ContentAddress.https(result as? String ?: "")
                            if (url != null) {
                                RecentActivityStore.record(
                                    carContext,
                                    RecentActivityStore.Entry(
                                        RecentActivityStore.Kind.MEDIA,
                                        carContext.getString(R.string.car_recent_stream),
                                        carContext.getString(R.string.car_recent_kind_streaming),
                                        url
                                    )
                                )
                                CarVideoLauncher.open(
                                    screenManager, carContext, url,
                                    carContext.getString(R.string.car_media_center_video_stream)
                                )
                            } else {
                                CarToast.makeText(
                                    carContext,
                                    carContext.getString(R.string.car_media_center_invalid_link),
                                    CarToast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_media_center_open_browser))
                    .addText(carContext.getString(R.string.car_media_center_open_browser_caption))
                    .setBrowsable(true)
                    .setOnClickListener { CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) } }
                    .build()
            )
            .build()
        return ListTemplate.Builder().setSingleList(list).build()
    }

    private fun nowPlayingSubtitle(connected: Boolean): String {
        if (!connected) return carContext.getString(R.string.car_lib_connecting_session)
        val title = mediaPlayback.currentTitle
            ?: return carContext.getString(R.string.car_lib_nothing_playing)
        val state = carContext.getString(
            if (mediaPlayback.isPlaying) R.string.car_lib_playing else R.string.car_lib_paused
        )
        return carContext.getString(R.string.car_lib_title_state, title, state)
    }

    private companion object {
        const val TAB_MUSIC = "music"
        const val TAB_VIDEO = "video"
        const val TAB_STREAMING = "streaming"
    }
}
