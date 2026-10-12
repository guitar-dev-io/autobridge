package dev.autobridge.iptv

/**
 * Live channels sorted by what they show, whatever the provider called its categories.
 *
 * Providers group by country ("Thailand"), by package ("TH | VIP 2"), or not at all — a playlist
 * without `group-title` lands everything in "All". Someone in a car wants "the news channels", so
 * every live channel is read once, by its name and its provider group, and filed under a [Kind].
 * A channel matching nothing stays out of every smart group rather than being forced into one.
 *
 * The keywords are matched against provider text, so they stay bilingual in code rather than
 * moving to res/values-th: a Thai playlist has Thai names whatever language the UI is in. Pure
 * string work, unit-tested without a device.
 */
object IptvSmartGroups {
    /** The groups, in the order they are offered. The UI owns their labels. */
    enum class Kind { NEWS, SPORTS, KIDS, MOVIES, MUSIC, DOCUMENTARY, RELIGION, SHOPPING }

    /** One smart group and the channels filed under it, in catalog order. */
    data class Group(val kind: Kind, val entries: List<IptvEntry>)

    /** A group needs this many channels to be worth a chip; one channel is not a group. */
    const val MIN_GROUP_SIZE = 2

    /**
     * Word-start keywords per kind, lowercase. Latin keywords match at the start of a word, so
     * "news" finds "BBC News" and "Newsmax" but "art" does not find "Smart"; Thai has no spaces
     * between words, so Thai keywords match anywhere. Checked in [Kind] order, first match wins —
     * which is why "Cartoon Network" is kids before "network" could mean anything else, and why a
     * sports news channel files as news.
     */
    private val KEYWORDS: Map<Kind, List<String>> = mapOf(
        Kind.NEWS to listOf(
            "news", "cnn", "bbc world", "al jazeera", "aljazeera", "euronews", "france 24", "france24",
            "dw ", "sky news", "cnbc", "bloomberg", "nhk world", "cgtn", "tnn", "nation tv", "thai pbs news",
            "ข่าว", "เนชั่น"
        ),
        Kind.SPORTS to listOf(
            "sport", "espn", "bein", "eurosport", "dazn", "golf", "football", "soccer", "nba", "nfl",
            "f1", "motogp", "wwe", "ufc", "tennis", "fight", "racing", "premier league", "laliga",
            "กีฬา", "ฟุตบอล", "มวย"
        ),
        Kind.KIDS to listOf(
            "kids", "kid ", "junior", "cartoon", "nick", "disney", "boomerang", "baby", "cbeebies",
            "toon", "animation", "anime", "children", "การ์ตูน", "เด็ก"
        ),
        Kind.MOVIES to listOf(
            "movie", "movies", "cinema", "cine", "film", "hbo", "cinemax", "paramount", "mgm", "star movies",
            "hollywood", "bollywood", "ภาพยนตร์", "หนัง"
        ),
        Kind.MUSIC to listOf(
            "music", "mtv", "vh1", "hits", "radio", "fm ", "concert", "karaoke", "jazz", "rock", "pop ",
            "เพลง", "ดนตรี", "ลูกทุ่ง"
        ),
        Kind.DOCUMENTARY to listOf(
            "documentary", "docu", "discovery", "nat geo", "national geographic", "history", "animal planet",
            "science", "nature", "travel", "สารคดี"
        ),
        Kind.RELIGION to listOf(
            "religion", "religious", "church", "christian", "gospel", "islam", "quran", "bible", "catholic",
            "dhamma", "buddha", "ธรรมะ", "ศาสนา", "พุทธ"
        ),
        Kind.SHOPPING to listOf(
            "shop", "shopping", "qvc", "teleshop", "home shopping", "ช้อป", "ช็อป"
        )
    )

    /**
     * Every smart group among [entries]'s live channels with at least [MIN_GROUP_SIZE] members, in
     * [Kind] order. [categoryNames] maps category ids to the provider's names for them, which is
     * the only grouping text an Xtream channel has.
     */
    fun group(entries: List<IptvEntry>, categoryNames: Map<String, String> = emptyMap()): List<Group> {
        val filed = LinkedHashMap<Kind, MutableList<IptvEntry>>()
        entries.forEach { entry ->
            if (entry.type != IptvEntryType.LIVE) return@forEach
            val kind = classify(entry, categoryNames[entry.categoryId].orEmpty()) ?: return@forEach
            filed.getOrPut(kind) { mutableListOf() } += entry
        }
        return Kind.entries.mapNotNull { kind ->
            filed[kind]?.takeIf { it.size >= MIN_GROUP_SIZE }?.let { Group(kind, it) }
        }
    }

    /**
     * The one group [entry] belongs to, or null. Its own name is read first, because "BBC News"
     * filed under a provider's "UK" or "Entertainment" is still news; then its provider group.
     */
    fun classify(entry: IptvEntry, categoryName: String = ""): Kind? =
        classify(entry.title) ?: classify(entry.subtitle) ?: classify(categoryName)

    internal fun classify(text: String): Kind? {
        if (text.isBlank()) return null
        // Padding with spaces lets "dw " and "fm " match at the end of a name, and the leading
        // space is what every word-start match looks for.
        val haystack = " " + text.lowercase().replace(SEPARATORS, " ") + " "
        return Kind.entries.firstOrNull { kind -> KEYWORDS.getValue(kind).any { matches(haystack, it) } }
    }

    private fun matches(haystack: String, keyword: String): Boolean {
        if (keyword.any { it.code in THAI_RANGE }) return keyword in haystack
        var index = haystack.indexOf(keyword)
        while (index >= 0) {
            if (index == 0 || !haystack[index - 1].isLetterOrDigit()) return true
            index = haystack.indexOf(keyword, index + 1)
        }
        return false
    }

    private val SEPARATORS = Regex("[|:/_\\-•·.,()\\[\\]]+")
    private val THAI_RANGE = 0x0E00..0x0E7F
}
