package dev.autobridge.car

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.library.LibraryActivity
import dev.autobridge.library.LocalMediaRepository
import dev.autobridge.media.MediaPlaybackClient

/**
 * On-device Folders, Playlists and Gallery for Android Auto, reading the same
 * [LocalMediaRepository] the phone screens use.
 *
 * Storage permissions can only be granted from an Activity, so a missing permission shows a
 * message that hands the user to [LibraryActivity] on the phone instead of failing silently.
 * Audio plays through the shared MediaSession; video takes the car surface via [CarVideoScreen].
 */
class CarLibraryScreen(
    carContext: CarContext,
    private val mode: Mode,
    private val group: LocalMediaRepository.Group? = null,
    private val page: Int = 0
) : Screen(carContext) {
    enum class Mode(val title: String, val section: LibraryActivity.Section) {
        FOLDERS("Folders", LibraryActivity.Section.FOLDERS),
        PLAYLISTS("Playlists", LibraryActivity.Section.PLAYLISTS),
        GALLERY("Gallery", LibraryActivity.Section.GALLERY)
    }

    private val mediaPlayback = MediaPlaybackClient(carContext)

    private val requiredTypes: Set<LocalMediaRepository.MediaType> = when (mode) {
        Mode.FOLDERS -> setOf(LocalMediaRepository.MediaType.AUDIO, LocalMediaRepository.MediaType.VIDEO)
        Mode.PLAYLISTS -> setOf(LocalMediaRepository.MediaType.AUDIO)
        Mode.GALLERY -> setOf(LocalMediaRepository.MediaType.IMAGE, LocalMediaRepository.MediaType.VIDEO)
    }

    init {
        mediaPlayback.connect(onConnected = { invalidate() }, onError = { invalidate() })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = mediaPlayback.disconnect()
        })
    }

    override fun onGetTemplate(): Template {
        if (!LocalMediaRepository.hasPermission(carContext, requiredTypes)) {
            return MessageTemplate.Builder(
                "${mode.title} needs media access.\nGrant it on the phone, then come back."
            )
                .setHeader(Header.Builder().setTitle(mode.title).setStartHeaderAction(Action.BACK).build())
                .addAction(
                    Action.Builder().setTitle("Open on phone").setOnClickListener { openOnPhone() }.build()
                )
                .build()
        }
        return if (group == null) groupTemplate() else itemTemplate(group)
    }

    private fun groupTemplate(): Template {
        val groups = when (mode) {
            Mode.FOLDERS -> LocalMediaRepository.folders(carContext)
            Mode.PLAYLISTS -> listOf(ALL_MUSIC) + LocalMediaRepository.playlists(carContext)
            Mode.GALLERY -> LocalMediaRepository.galleryAlbums(carContext)
        }
        if (groups.isEmpty()) {
            return MessageTemplate.Builder("Nothing found on this device.")
                .setHeader(Header.Builder().setTitle(mode.title).setStartHeaderAction(Action.BACK).build())
                .build()
        }
        val paged = CarListPaging.page(carContext, groups, page)
        val list = ItemList.Builder()
        paged.items.forEach { entry ->
            list.addItem(
                Row.Builder()
                    .setTitle(entry.name)
                    .apply { if (entry.count > 0) addText("${entry.count} items") }
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarLibraryScreen(carContext, mode, entry)) }
                    .build()
            )
        }
        if (paged.hasMore) {
            list.addItem(
                Row.Builder().setTitle("Show more").setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarLibraryScreen(carContext, mode, null, page + 1))
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(mode.title).setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }

    private fun itemTemplate(selected: LocalMediaRepository.Group): Template {
        val items = when {
            mode == Mode.PLAYLISTS && selected.id == ALL_MUSIC.id -> LocalMediaRepository.allAudio(carContext)
            mode == Mode.PLAYLISTS -> LocalMediaRepository.playlistItems(carContext, selected.id)
            mode == Mode.GALLERY -> LocalMediaRepository.galleryItems(carContext, selected.id)
            else -> LocalMediaRepository.folderItems(carContext, selected.id)
        }
        if (items.isEmpty()) {
            return MessageTemplate.Builder("Nothing to play here.")
                .setHeader(Header.Builder().setTitle(selected.name).setStartHeaderAction(Action.BACK).build())
                .build()
        }
        val paged = CarListPaging.page(carContext, items, page)
        val list = ItemList.Builder()
        paged.items.forEachIndexed { offset, item ->
            list.addItem(
                Row.Builder()
                    .setTitle(item.title)
                    .addText(subtitle(item))
                    .setOnClickListener { play(items, paged.startIndex + offset) }
                    .build()
            )
        }
        if (paged.hasMore) {
            list.addItem(
                Row.Builder().setTitle("Show more").setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarLibraryScreen(carContext, mode, selected, page + 1))
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(Header.Builder().setTitle(selected.name).setStartHeaderAction(Action.BACK).build())
            .setSingleList(list.build())
            .build()
    }

    /**
     * Audio queues the whole list so steering-wheel Next/Previous walks the folder; video and
     * photos are single items, and a photo has no car playback path at all.
     */
    private fun play(items: List<LocalMediaRepository.Item>, index: Int) {
        val item = items.getOrNull(index) ?: return
        if (mode == Mode.GALLERY && item.durationMs <= 0L) {
            CarToast.makeText(carContext, "Photos can only be viewed on the phone", CarToast.LENGTH_LONG).show()
            return
        }
        if (mode == Mode.GALLERY || (mode == Mode.FOLDERS && item.subtitle != "Audio")) {
            CarVideoLauncher.open(screenManager, carContext, item.uri, item.title)
            return
        }
        if (!mediaPlayback.isConnected) {
            CarToast.makeText(carContext, "Connecting to the player…", CarToast.LENGTH_SHORT).show()
            return
        }
        mediaPlayback.playPlaylist(items.map { it.uri }, index)
        CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) }
    }

    private fun subtitle(item: LocalMediaRepository.Item): String {
        if (item.durationMs <= 0L) return if (mode == Mode.GALLERY) "Photo" else item.subtitle
        val totalSeconds = item.durationMs / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun openOnPhone() {
        runCatching {
            carContext.startActivity(
                LibraryActivity.intent(carContext, mode.section).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CarToast.makeText(carContext, "Continue on the phone", CarToast.LENGTH_LONG).show()
        }.onFailure {
            CarToast.makeText(carContext, "Could not open the phone screen", CarToast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** Synthetic bucket so Playlists always has something to open on Android 11+. */
        val ALL_MUSIC = LocalMediaRepository.Group("__all_music__", "All music", 0)
    }
}
