package dev.autobridge.iptv

/** What a source feeds: the TV grid (video) or the Radio grid (audio-only). */
enum class IptvKind { TV, RADIO }

/** How a source is fetched: an Xtream Codes account, or a plain M3U playlist URL. */
enum class IptvSourceType { XTREAM, M3U }

/**
 * A configured IPTV provider. [url] is the portal (Xtream) or the playlist address (M3U);
 * [username]/[password] are only meaningful for [IptvSourceType.XTREAM].
 */
data class IptvSource(
    val id: String,
    val name: String,
    val kind: IptvKind,
    val type: IptvSourceType,
    val url: String,
    val username: String = "",
    val password: String = "",
    /** An XMLTV guide the user typed for an M3U source; blank means "use what the playlist says". */
    val guideUrl: String = ""
) {
    val credentials: XtreamCredentials?
        get() = if (type == IptvSourceType.XTREAM) {
            XtreamCredentials.parse(url, username, password)
        } else {
            null
        }
}

/** A provider category ("Sports", "News"). [id] is the Xtream category id, or a synthetic group id. */
data class IptvCategory(val id: String, val name: String, val count: Int = 0)

/** What the catalog can hand to a player: live channels, movies, and series episodes. */
enum class IptvEntryType { LIVE, MOVIE, SERIES }

/**
 * How an entry's address has to be opened. Curated playlists point some channels at a YouTube or
 * Twitch page rather than a stream, and a media player cannot do anything with those.
 */
enum class IptvPlayback { STREAM, WEB_PAGE }

/**
 * One playable catalog entry. [url] is already a direct stream address, so playback never needs
 * the portal again. [seriesId] is set for series folders whose episodes load on demand.
 * [guideId] is the playlist's `tvg-id`, the key an XMLTV guide files this channel under, and
 * [country] its `tvg-country`; both are blank for Xtream entries, whose guide is asked by stream id.
 */
data class IptvEntry(
    val id: String,
    val title: String,
    val categoryId: String,
    val type: IptvEntryType,
    val url: String,
    val logo: String = "",
    val subtitle: String = "",
    val seriesId: String = "",
    val catchupDays: Int = 0,
    val playback: IptvPlayback = IptvPlayback.STREAM,
    val guideId: String = "",
    val country: String = ""
) {
    val isSeriesFolder: Boolean get() = type == IptvEntryType.SERIES && url.isEmpty()
    val supportsCatchup: Boolean get() = type == IptvEntryType.LIVE && catchupDays > 0
    val isWebPage: Boolean get() = playback == IptvPlayback.WEB_PAGE
}

/**
 * A loaded source: its categories and every entry, already resolved to playable URLs.
 * [guideUrls] are the XMLTV guides an M3U playlist's header declared, in the order written.
 */
data class IptvCatalogData(
    val categories: List<IptvCategory>,
    val entries: List<IptvEntry>,
    val guideUrls: List<String> = emptyList()
) {
    /**
     * The live channels sorted into [IptvSmartGroups], worked out once per loaded catalog: a
     * country-sized list takes a moment to classify and the categories page draws on every
     * keystroke. Outside the constructor, so it is not part of equality.
     */
    val smartGroups: List<IptvSmartGroups.Group> by lazy {
        IptvSmartGroups.group(entries, categories.associate { it.id to it.name })
    }

    fun entriesIn(categoryId: String): List<IptvEntry> =
        if (categoryId == ALL_CATEGORY_ID) entries else entries.filter { it.categoryId == categoryId }

    companion object {
        const val ALL_CATEGORY_ID = "__all__"
        val EMPTY = IptvCatalogData(emptyList(), emptyList())
    }
}
