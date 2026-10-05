package dev.autobridge.library

import android.content.Context
import androidx.annotation.StringRes
import dev.autobridge.R

/**
 * The single home-grid definition, shared by the phone launcher and the Android Auto dashboard.
 *
 * Keeping the order and wording in one place is what makes the two surfaces match: the phone
 * renders every section as a card, and the car renders the same list as grid tiles, paginated to
 * whatever the head unit's grid content limit allows.
 *
 * [webUrl] is set for the sections that are simply a website; everything else is handled by the
 * surface-specific screen for that section.
 */
enum class HomeSection(
    /**
     * Name and second line as string *ids*, resolved per use with [title] and [caption]. An enum
     * constant is built once per process, so text captured here would keep whatever language was
     * in force at class-init and survive a language change unchanged.
     */
    @StringRes val titleRes: Int,
    @StringRes val captionRes: Int,
    val accent: Int,
    val webUrl: String? = null
) {
    TV(R.string.section_tv, R.string.section_tv_caption, Accents.TV),
    RADIO(R.string.section_radio, R.string.section_radio_caption, Accents.RADIO),
    WEB(R.string.section_web, R.string.section_web_caption, Accents.WEB),
    YOUTUBE(
        R.string.section_youtube, R.string.section_youtube_caption, Accents.VIDEO,
        "https://m.youtube.com"
    ),
    YOUTUBE_MUSIC(
        R.string.section_youtube_music, R.string.section_youtube_music_caption, Accents.VIDEO,
        "https://music.youtube.com"
    ),
    /** , TikTok, Twitch and other web video sites; see [StreamingLinks]. */
    STREAMING(R.string.section_streaming, R.string.section_streaming_caption, Accents.VIDEO),
    FOLDERS(R.string.section_folders, R.string.section_folders_caption, Accents.FILES),
    FAVORITES(R.string.section_favorites, R.string.section_favorites_caption, Accents.FAVORITE),
    PLAYLISTS(R.string.section_playlists, R.string.section_playlists_caption, Accents.FILES),
    GALLERY(R.string.section_gallery, R.string.section_gallery_caption, Accents.WEB),
    WEATHER(R.string.section_weather, R.string.section_weather_caption, Accents.WEATHER),
    MIRROR(R.string.section_mirror, R.string.section_mirror_caption, Accents.SYSTEM),
    APPS(R.string.section_apps, R.string.section_apps_caption, Accents.SYSTEM),
    REMOTE(R.string.section_remote, R.string.section_remote_caption, Accents.SYSTEM),
    SETTINGS(R.string.section_settings, R.string.section_settings_caption, Accents.SYSTEM);

    fun title(context: Context): String = context.getString(titleRes)

    fun caption(context: Context): String = context.getString(captionRes)

    /** The [LibraryActivity] section this tile opens, or null when it is not a library section. */
    val librarySection: LibraryActivity.Section?
        get() = when (this) {
            TV -> LibraryActivity.Section.TV
            RADIO -> LibraryActivity.Section.RADIO
            FOLDERS -> LibraryActivity.Section.FOLDERS
            PLAYLISTS -> LibraryActivity.Section.PLAYLISTS
            GALLERY -> LibraryActivity.Section.GALLERY
            FAVORITES -> LibraryActivity.Section.FAVORITES
            STREAMING -> LibraryActivity.Section.STREAMING
            else -> null
        }

    /**
     * Accent constants mirrored from `AutoBridgeDesign` so this enum stays free of UI imports and
     * can be read by the car screens as well.
     */
    private object Accents {
        const val TV = 0xFF6EA8FF.toInt()
        const val RADIO = 0xFFFFB35C.toInt()
        const val WEB = 0xFF7DD3C0.toInt()
        const val VIDEO = 0xFFFF7A8A.toInt()
        const val FILES = 0xFF9BE08A.toInt()
        const val FAVORITE = 0xFFFF8FB1.toInt()
        const val SYSTEM = 0xFFB39DFF.toInt()
        const val WEATHER = 0xFF6BC5FF.toInt()
    }

    companion object {
        /**
         * Sections the car app offers. `REMOTE` is phone-only: it exists to drive the car screen
         * from the phone, so mirroring it onto the head unit would be circular.
         */
        val carSections: List<HomeSection> = entries.filterNot { it == REMOTE }

        /**
         * Sections the phone home grid offers. `APPS`, `REMOTE` and `SETTINGS` are left out because
         * the phone's bottom navigation already carries all three on every screen — listing them
         * again as tiles gave each destination two identical entry points and pushed the actual
         * content further down the grid. The car keeps them: it has no bottom navigation.
         */
        val phoneSections: List<HomeSection> =
            entries.filterNot { it == APPS || it == REMOTE || it == SETTINGS }
    }
}
