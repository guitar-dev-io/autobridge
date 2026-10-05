package dev.autobridge.iptv

/**
 * A short list of public, free-to-air playlists the user can add with one tap instead of typing a
 * URL on a phone.
 *
 * These are addresses only. AutoBridge does not host, mirror, bundle or redistribute any playlist
 * or stream; each entry is fetched live from the project that publishes it, under that project's
 * own terms, exactly as if the user had pasted the address into the M3U form. Nothing here is
 * added automatically — adding a source stays an explicit action.
 *
 * Kept deliberately small. A directory that tries to enumerate every regional list rots the moment
 * upstream renames a file; these are the entry points that have stayed put, and the per-country
 * split is already available inside them as playlist groups.
 */
object IptvDirectory {
    /**
     * One offer in the picker. [note] is what the row says about the list: its size and the thing
     * the user would otherwise find out only after loading it. [seeded] marks the ones a fresh
     * install starts with, so TV and Radio have something to show before anything is configured.
     */
    data class Entry(
        val name: String,
        val url: String,
        val kind: IptvKind,
        val note: String,
        val seeded: Boolean = false
    )

    private val ENTRIES = listOf(
        Entry(
            name = "Free-TV",
            url = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
            kind = IptvKind.TV,
            note = "~2,000 free-to-air channels, grouped by country",
            seeded = true
        ),
        Entry(
            name = "Thai (dearbulut)",
            url = "https://dearbulut.github.io/iptv/playlists/language/tha.m3u",
            kind = IptvKind.TV,
            note = "Thai-language channels",
            seeded = true
        ),
        Entry(
            name = "iptv-org · All countries",
            url = "https://iptv-org.github.io/iptv/index.m3u",
            kind = IptvKind.TV,
            note = "Very large; the first load takes a while"
        ),
        Entry(
            name = "radio-browser · Thailand",
            url = "https://de1.api.radio-browser.info/m3u/stations/bycountry/thailand",
            kind = IptvKind.RADIO,
            note = "Thai stations from the radio-browser community database",
            seeded = true
        ),
        Entry(
            name = "radio-browser · Top voted",
            url = "https://de1.api.radio-browser.info/m3u/stations/topvote/100",
            kind = IptvKind.RADIO,
            note = "The 100 highest-voted stations worldwide"
        )
    )

    fun list(kind: IptvKind): List<Entry> = ENTRIES.filter { it.kind == kind }

    /** The lists a fresh install starts with. */
    fun defaults(): List<Entry> = ENTRIES.filter { it.seeded }

    /**
     * Which defaults still have to be created, given the URLs already seeded once and the URLs
     * already configured.
     *
     * Seeding is remembered per URL rather than behind a single "has run" flag, which is what makes
     * removal stick: a deleted default is absent from [existingUrls] but present in [seededUrls],
     * so it is never recreated — while a default added in a later version, absent from both, still
     * arrives. Pure, so the rule is unit-tested without SharedPreferences.
     */
    fun pendingDefaults(seededUrls: Set<String>, existingUrls: Set<String>): List<Entry> =
        defaults().filter { it.url !in seededUrls && it.url !in existingUrls }

    /** The source to persist for [entry]; the caller still decides whether to save it. */
    fun toSource(entry: Entry, id: String): IptvSource = IptvSource(
        id = id,
        name = entry.name,
        kind = entry.kind,
        type = IptvSourceType.M3U,
        url = entry.url
    )
}
