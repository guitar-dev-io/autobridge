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
    val password: String = ""
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
 * One playable catalog entry. [url] is already a direct stream address, so playback never needs
 * the portal again. [seriesId] is set for series folders whose episodes load on demand.
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
    val catchupDays: Int = 0
) {
    val isSeriesFolder: Boolean get() = type == IptvEntryType.SERIES && url.isEmpty()
    val supportsCatchup: Boolean get() = type == IptvEntryType.LIVE && catchupDays > 0
}

/** A loaded source: its categories and every entry, already resolved to playable URLs. */
data class IptvCatalogData(
    val categories: List<IptvCategory>,
    val entries: List<IptvEntry>
) {
    fun entriesIn(categoryId: String): List<IptvEntry> =
        if (categoryId == ALL_CATEGORY_ID) entries else entries.filter { it.categoryId == categoryId }

    companion object {
        const val ALL_CATEGORY_ID = "__all__"
        val EMPTY = IptvCatalogData(emptyList(), emptyList())
    }
}
