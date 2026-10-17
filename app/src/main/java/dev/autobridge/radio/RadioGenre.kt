package dev.autobridge.radio

import androidx.annotation.StringRes
import dev.autobridge.R
import dev.autobridge.iptv.IptvEntry

/**
 * The genre chips across the top of a radio category: the whole list first, then the genres the
 * stations in view actually have.
 *
 * radio-browser's own tags are free text in any language, and many Thai stations carry none, so a
 * station is matched on its tags and its name together. The keywords are what stations call
 * themselves ("ลูกทุ่ง", "luk thung", "news"), never shown, which is why Thai sits in code here.
 * Pure, so the matching is a JVM test.
 */
enum class RadioGenre(@StringRes val labelRes: Int, private val keywords: List<String>) {
    ALL(R.string.radio_genre_all, emptyList()),
    LUK_THUNG(R.string.radio_genre_luk_thung, listOf("ลูกทุ่ง", "luk thung", "lukthung", "luk-thung", "หมอลำ", "mor lam", "country")),
    POP(R.string.radio_genre_pop, listOf("pop", "ป๊อป", "hits", "top 40", "t-pop", "thai pop")),
    NEWS(R.string.radio_genre_news, listOf("news", "ข่าว", "talk", "สถานีข่าว")),
    INTERNATIONAL(R.string.radio_genre_international, listOf("สากล", "international", "inter", "english", "rock", "jazz")),
    DHARMA(R.string.radio_genre_dharma, listOf("ธรรมะ", "dhamma", "dharma", "buddhist", "religious", "religion"));

    fun matches(entry: IptvEntry): Boolean {
        if (this == ALL) return true
        val haystack = (entry.tags + " " + entry.title).lowercase()
        return keywords.any { haystack.contains(it) }
    }

    companion object {
        /** [ALL], then each genre that at least one of [entries] matches, in declaration order. */
        fun available(entries: List<IptvEntry>): List<RadioGenre> =
            listOf(ALL) + RadioGenre.entries.filter { genre -> genre != ALL && entries.any(genre::matches) }

        fun filter(entries: List<IptvEntry>, genre: RadioGenre): List<IptvEntry> =
            if (genre == ALL) entries else entries.filter(genre::matches)
    }
}
