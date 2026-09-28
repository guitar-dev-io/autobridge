package dev.autobridge.library

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
    val title: String,
    val caption: String,
    val accent: Int,
    val webUrl: String? = null
) {
    TV("TV", "Live channels & VOD", Accents.TV),
    RADIO("Radio", "Audio streams", Accents.RADIO),
    WEB("Web browser", "Full page browsing", Accents.WEB),
    YOUTUBE("Youtube", "Video on the web", Accents.VIDEO, "https://m.youtube.com"),
    YOUTUBE_MUSIC("YouTube Music", "Streaming music", Accents.VIDEO, "https://music.youtube.com"),
    YOUTUBE_KIDS("YouTube Kids", "Family content", Accents.VIDEO, "https://www.youtubekids.com"),
    FOLDERS("Folders", "On-device media", Accents.FILES),
    FAVORITES("Favorites", "Saved channels & pages", Accents.FAVORITE),
    PLAYLISTS("Playlists", "Your music", Accents.FILES),
    GALLERY("Gallery", "Photos & clips", Accents.WEB),
    MIRROR("Mirror", "Share this screen", Accents.SYSTEM),
    APPS("Apps", "Quick launch", Accents.SYSTEM),
    REMOTE("Remote", "Drive the car screen", Accents.SYSTEM),
    SETTINGS("Settings", "Modes & diagnostics", Accents.SYSTEM);

    /** The [LibraryActivity] section this tile opens, or null when it is not a library section. */
    val librarySection: LibraryActivity.Section?
        get() = when (this) {
            TV -> LibraryActivity.Section.TV
            RADIO -> LibraryActivity.Section.RADIO
            FOLDERS -> LibraryActivity.Section.FOLDERS
            PLAYLISTS -> LibraryActivity.Section.PLAYLISTS
            GALLERY -> LibraryActivity.Section.GALLERY
            FAVORITES -> LibraryActivity.Section.FAVORITES
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
