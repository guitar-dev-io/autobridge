package dev.autobridge.iptv

import org.json.JSONObject
import java.util.Base64

/** One listing in a channel's guide. Times are epoch milliseconds; [category] may be blank. */
data class IptvProgramme(
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val category: String = ""
) {
    /** How far through the programme [nowMs] is, 0..1. */
    fun progress(nowMs: Long): Float =
        if (stopMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (stopMs - startMs)).coerceIn(0f, 1f)
}

/** What a channel is showing at a moment, and what follows it. Either may be missing. */
data class IptvNowNext(val now: IptvProgramme?, val next: IptvProgramme?) {
    val isEmpty: Boolean get() = now == null && next == null
}

/**
 * The decisions behind the programme guide, kept free of threads, caches and the network so each
 * one is unit-tested on its own: reading a portal's short guide, picking the programme on now,
 * choosing which XMLTV files are worth downloading, and finding a channel inside one.
 */
object IptvGuideRules {
    /**
     * A guide header that lists more files than this is a catalogue of guides, not a guide: Free-TV
     * names a hundred, one of them 200 MB. Past it, only files whose name carries the country of
     * the channels in view are fetched.
     */
    const val MAX_UNFILTERED_GUIDES = 3

    /** No load fetches more files than this, whatever the header says. */
    const val MAX_GUIDES_PER_LOAD = 4

    /**
     * The programme on at [nowMs] and the one after it, from [programmes] in any order. A gap in
     * the guide leaves "now" empty but still names what comes next, which is worth showing.
     */
    fun nowNext(programmes: List<IptvProgramme>, nowMs: Long): IptvNowNext {
        if (programmes.isEmpty()) return IptvNowNext(null, null)
        val sorted = programmes.sortedBy { it.startMs }
        val now = sorted.lastOrNull { it.startMs <= nowMs && nowMs < it.stopMs }
        val next = sorted.firstOrNull { it.startMs >= (now?.stopMs ?: nowMs) && it !== now }
        return IptvNowNext(now, next)
    }

    /**
     * The listings in an Xtream `get_short_epg` answer. Titles are base64 on most portals and plain
     * on some; both are read. Listings without usable times are dropped, because a guide row that
     * cannot say *when* is a guess.
     */
    fun parseShortEpg(json: String): List<IptvProgramme> {
        val listings = runCatching { JSONObject(json).optJSONArray("epg_listings") }.getOrNull() ?: return emptyList()
        val programmes = mutableListOf<IptvProgramme>()
        for (index in 0 until listings.length()) {
            val listing = listings.optJSONObject(index) ?: continue
            val title = decode(listing.optString("title")).takeIf { it.isNotBlank() } ?: continue
            val start = listing.optString("start_timestamp").toLongOrNull() ?: continue
            val stop = listing.optString("stop_timestamp").toLongOrNull() ?: continue
            if (stop <= start) continue
            programmes += IptvProgramme(title, start * 1000, stop * 1000)
        }
        return programmes
    }

    /**
     * Which XMLTV files to fetch for [entries], best first.
     *
     * A guide the user typed wins outright. Otherwise the playlist's own [declared] guides come
     * first and the directory's [fallback] after them — headers rot, and a known-good file is the
     * difference between a guide and none. A header that lists a catalogue of files is narrowed to
     * the ones whose file name carries a country of the channels in view (`epg_ripper_TH1` for
     * channels filed `….th`); a catalogue with no such match fetches nothing rather than guessing.
     */
    fun guideUrls(
        override: String,
        declared: List<String>,
        fallback: String,
        entries: List<IptvEntry>
    ): List<String> {
        if (override.isNotBlank()) return listOf(override.trim())
        val chosen = if (declared.size <= MAX_UNFILTERED_GUIDES) {
            declared
        } else {
            val countries = entries.flatMap { countriesOf(it) }.toSet()
            declared.filter { url -> fileTokens(url).any { it in countries } }
        }
        return (chosen + listOfNotNull(fallback.trim().takeIf { it.isNotEmpty() }))
            .distinct()
            .take(MAX_GUIDES_PER_LOAD)
    }

    /**
     * The keys [entry] may be filed under in an XMLTV guide, most specific first: its `tvg-id`
     * as written, the same id without iptv-org's `@SD`/`@HD` feed suffix, and its normalised name
     * for guides that file channels under ids the playlist does not use.
     */
    fun matchKeys(entry: IptvEntry): List<String> = listOfNotNull(
        entry.guideId.trim().lowercase().takeIf { it.isNotEmpty() },
        entry.guideId.substringBefore('@').trim().lowercase().takeIf { it.isNotEmpty() },
        normalizeName(entry.title).takeIf { it.isNotEmpty() }?.let { NAME_KEY + it }
    ).distinct()

    /** The key a guide's `<display-name>` is filed under, so it meets [matchKeys]' name key. */
    fun nameKey(displayName: String): String? = normalizeName(displayName).takeIf { it.isNotEmpty() }?.let { NAME_KEY + it }

    /**
     * A channel name reduced to what two spellings of it share: case, punctuation and the quality
     * tags lists append (`HD`, `FHD`, `(720p)`, `[Geo-blocked]`) are dropped. Thai letters are kept
     * as they are; only Latin case folds.
     */
    fun normalizeName(name: String): String {
        val withoutBrackets = name.replace(BRACKETED, " ")
        return withoutBrackets.lowercase()
            .split(NON_WORD)
            .filter { it.isNotEmpty() && it !in QUALITY_TAGS }
            .joinToString("")
    }

    /** The countries [entry] claims: its `tvg-country` list and the suffix of its `tvg-id`. */
    internal fun countriesOf(entry: IptvEntry): List<String> {
        val declared = entry.country.split(';', ',', ' ').map { it.trim().lowercase() }.filter { it.length == 2 }
        val fromId = entry.guideId.substringBefore('@').substringAfterLast('.', "").lowercase()
            .takeIf { it.length == 2 && it.all(Char::isLetter) }
        return declared + listOfNotNull(fromId)
    }

    /** `…/epg_ripper_TH1.xml.gz` → `epg`, `ripper`, `th`: the words of the file name, lowercased. */
    internal fun fileTokens(url: String): List<String> =
        url.substringBefore('?').substringAfterLast('/').lowercase()
            .removeSuffix(".gz").removeSuffix(".xml")
            .split(FILE_SEPARATORS)
            .filter { it.isNotEmpty() }

    private fun decode(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val decoded = runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
        // A plain title that happens to be valid base64 decodes to bytes that are not text.
        return if (decoded != null && decoded.none { it == '�' || (it.isISOControl() && it != '\n') }) {
            decoded.trim()
        } else {
            value
        }
    }

    private const val NAME_KEY = "name:"
    private val BRACKETED = Regex("\\([^)]*\\)|\\[[^]]*]")
    // Marks count as word characters: Thai vowel and tone marks are combining marks, not letters,
    // and splitting on them would cut "ช่อง" into pieces.
    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val FILE_SEPARATORS = Regex("[^a-z]+")
    private val QUALITY_TAGS = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "1080p", "720p", "576p", "480p")
}
