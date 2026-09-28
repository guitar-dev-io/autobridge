package dev.autobridge.car

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
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.media.MediaPlaybackClient

/**
 * Unified Media Center with Music / Video / Streaming tabs. This does NOT remove the existing Music
 * or Video features — it composes them into one tabbed surface, reusing [MediaPlaybackClient] for
 * audio playback and [CarVideoScreen] for video, exactly as [CarMediaLibraryScreen] does. The
 * standalone library screen remains available.
 */
class CarMediaCenterScreen(carContext: CarContext) : Screen(carContext) {
    private data class Track(val title: String, val subtitle: String, val uri: String)

    private val mediaPlayback = MediaPlaybackClient(carContext)
    private var activeTab = TAB_MUSIC

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
            .addTab(tab("Music", TAB_MUSIC, DashboardArtwork.Kind.MEDIA))
            .addTab(tab("Video", TAB_VIDEO, DashboardArtwork.Kind.YOUTUBE))
            .addTab(tab("Streaming", TAB_STREAMING, DashboardArtwork.Kind.BROWSER))
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
                .setTitle("Now Playing")
                .addText(nowPlayingSubtitle(connected))
                .setBrowsable(true)
                .setOnClickListener { CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) } }
                .build()
        )
        audioLibrary.forEach { track ->
            list.addItem(
                Row.Builder()
                    .setTitle(track.title)
                    .addText(track.subtitle)
                    .setEnabled(connected)
                    .setOnClickListener {
                        mediaPlayback.play(track.uri, track.title)
                        RecentActivityStore.record(
                            carContext,
                            RecentActivityStore.Entry(
                                RecentActivityStore.Kind.MEDIA, track.title, "Music", track.uri
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
                    .addText(track.subtitle)
                    .setOnClickListener {
                        RecentActivityStore.record(
                            carContext,
                            RecentActivityStore.Entry(
                                RecentActivityStore.Kind.MEDIA, track.title, "Video", track.uri
                            )
                        )
                        screenManager.push(CarVideoScreen(carContext, track.uri, track.title))
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
                    .setTitle("Open stream link")
                    .addText("MP4, HLS or DASH URL")
                    .setBrowsable(true)
                    .setOnClickListener {
                        screenManager.pushForResult(CarBrowserSearchScreen(carContext, "")) { result ->
                            val url = dev.autobridge.entertainment.ContentAddress.https(result as? String ?: "")
                            if (url != null) {
                                RecentActivityStore.record(
                                    carContext,
                                    RecentActivityStore.Entry(
                                        RecentActivityStore.Kind.MEDIA, "Stream", "Streaming", url
                                    )
                                )
                                screenManager.push(CarVideoScreen(carContext, url, "Video stream"))
                            } else {
                                CarToast.makeText(carContext, "Enter a valid HTTPS link", CarToast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Open in Browser")
                    .addText("For web players (YouTube, etc.)")
                    .setBrowsable(true)
                    .setOnClickListener { CarNavigation.open(screenManager, "CarBrowserScreen") { CarBrowserScreen(carContext) } }
                    .build()
            )
            .build()
        return ListTemplate.Builder().setSingleList(list).build()
    }

    private fun nowPlayingSubtitle(connected: Boolean): String {
        if (!connected) return "Connecting to media session…"
        val title = mediaPlayback.currentTitle ?: return "Nothing playing"
        val state = if (mediaPlayback.isPlaying) "Playing" else "Paused"
        return "$title • $state"
    }

    private companion object {
        const val TAB_MUSIC = "music"
        const val TAB_VIDEO = "video"
        const val TAB_STREAMING = "streaming"
    }
}
