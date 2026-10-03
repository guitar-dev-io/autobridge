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
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
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
    enum class Mode(@StringRes val titleRes: Int, val section: LibraryActivity.Section) {
        FOLDERS(R.string.car_folders, LibraryActivity.Section.FOLDERS),
        PLAYLISTS(R.string.car_playlists, LibraryActivity.Section.PLAYLISTS),
        GALLERY(R.string.car_gallery, LibraryActivity.Section.GALLERY)
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
                carContext.getString(R.string.car_needs_media_access, modeTitle())
            )
                .setHeader(
                    Header.Builder()
                        .setTitle(modeTitle())
                        .setStartHeaderAction(Action.BACK)
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle(carContext.getString(R.string.car_iptv_open_on_phone))
                        .setOnClickListener { openOnPhone() }
                        .build()
                )
                .build()
        }
        return if (group == null) groupTemplate() else itemTemplate(group)
    }

    private fun groupTemplate(): Template {
        val groups = when (mode) {
            Mode.FOLDERS -> LocalMediaRepository.folders(carContext)
            Mode.PLAYLISTS -> listOf(allMusicGroup()) + LocalMediaRepository.playlists(carContext)
            Mode.GALLERY -> LocalMediaRepository.galleryAlbums(carContext)
        }
        if (groups.isEmpty()) {
            return MessageTemplate.Builder(carContext.getString(R.string.car_nothing_found))
                .setHeader(
                    Header.Builder()
                        .setTitle(modeTitle())
                        .setStartHeaderAction(Action.BACK)
                        .build()
                )
                .build()
        }
        val paged = CarListPaging.page(carContext, groups, page)
        val list = ItemList.Builder()
        paged.items.forEach { entry ->
            list.addItem(
                Row.Builder()
                    .setTitle(entry.name)
                    .apply {
                        if (entry.count > 0) addText(
                            carContext.resources.getQuantityString(
                                R.plurals.car_iptv_items, entry.count, entry.count
                            )
                        )
                    }
                    .setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarLibraryScreen(carContext, mode, entry)) }
                    .build()
            )
        }
        if (paged.hasMore) {
            list.addItem(
                Row.Builder().setTitle(carContext.getString(R.string.car_iptv_show_more)).setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(CarLibraryScreen(carContext, mode, null, page + 1))
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder().setTitle(modeTitle()).setStartHeaderAction(Action.BACK).build()
            )
            .setSingleList(list.build())
            .build()
    }

    private fun itemTemplate(selected: LocalMediaRepository.Group): Template {
        val items = when {
            mode == Mode.PLAYLISTS && selected.id == ALL_MUSIC_ID ->
                LocalMediaRepository.allAudio(carContext)
            mode == Mode.PLAYLISTS -> LocalMediaRepository.playlistItems(carContext, selected.id)
            mode == Mode.GALLERY -> LocalMediaRepository.galleryItems(carContext, selected.id)
            else -> LocalMediaRepository.folderItems(carContext, selected.id)
        }
        if (items.isEmpty()) {
            return MessageTemplate.Builder(carContext.getString(R.string.car_nothing_to_play))
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
                Row.Builder().setTitle(carContext.getString(R.string.car_iptv_show_more)).setBrowsable(true)
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
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_photos_phone_only),
                CarToast.LENGTH_LONG
            ).show()
            return
        }
        if (mode == Mode.GALLERY || (mode == Mode.FOLDERS && !item.isAudio)) {
            CarVideoLauncher.open(screenManager, carContext, item.uri, item.title)
            return
        }
        if (!mediaPlayback.isConnected) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_connecting_player),
                CarToast.LENGTH_SHORT
            ).show()
            return
        }
        mediaPlayback.playPlaylist(items.map { it.uri }, index)
        CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) }
    }

    private fun subtitle(item: LocalMediaRepository.Item): String {
        if (item.durationMs <= 0L) {
            return if (mode == Mode.GALLERY) carContext.getString(R.string.media_kind_photo)
            else item.subtitle
        }
        val totalSeconds = item.durationMs / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun modeTitle(): String = carContext.getString(mode.titleRes)

    /** Synthetic bucket so Playlists always has something to open on Android 11+. */
    private fun allMusicGroup() = LocalMediaRepository.Group(
        ALL_MUSIC_ID,
        carContext.getString(R.string.car_all_music),
        0
    )

    private fun openOnPhone() {
        runCatching {
            carContext.startActivity(
                LibraryActivity.intent(carContext, mode.section).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_iptv_continue_on_phone),
                CarToast.LENGTH_LONG
            ).show()
        }.onFailure {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_iptv_phone_screen_failed),
                CarToast.LENGTH_SHORT
            ).show()
        }
    }

    private companion object {
        const val ALL_MUSIC_ID = "__all_music__"
    }
}
