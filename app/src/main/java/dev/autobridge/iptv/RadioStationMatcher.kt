package dev.autobridge.iptv

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/**
 * Matches what a driver types against radio station names, shared by the car and phone searches.
 *
 * A plain `contains` fails on the way people look for a station: "FM 95.5" against "95.5 Delight"
 * (the band word is not in the name), "955" or "95,5" for 95.5, "cool 93" against "Cool Fahrenheit
 * 93", Thai digits, and Thai SARA AM typed composed or decomposed. So both sides are normalized
 * first, the query is split into tokens, and every token has to be found: a word as a substring,
 * a number against the numbers in the name.
 *
 * Pure Kotlin with no Android imports, so it is tested on the JVM.
 */
object RadioStationMatcher {
    // Matched against what the user types, not shown to them, so the Thai band words live in code
    // like the other matching vocabularies (see IptvSmartGroups, XtreamClient).
    private val BAND_WORDS = setOf("fm", "am", "mhz", "khz", "คลื่น", "วิทยุ", "เอฟเอ็ม")
    private val BAND_PREFIXES = listOf("fm", "am", "คลื่น", "วิทยุ", "เอฟเอ็ม")
    private val BAND_SUFFIXES = listOf("mhz", "khz", "fm", "am")

    private val DECIMAL_SEPARATOR = Regex("""(?<=\d)\s*[.,]\s*(?=\d)""")
    private val WHITESPACE = Regex("""\s+""")
    private val NUMBER_TOKEN = Regex("""^\d+(\.\d+)?$""")
    private val NUMBER_IN_NAME = Regex("""\d+(?:\.\d+)?""")

    /** NFKC, lowercase, ASCII digits, "95 , 5" → "95.5", punctuation to spaces, spaces collapsed. */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val digits = buildString(folded.length) {
            folded.forEach { c ->
                val d = Character.digit(c, 10)
                append(if (d >= 0 && Character.isDigit(c)) ('0' + d) else c)
            }
        }
        val joined = DECIMAL_SEPARATOR.replace(digits, ".")
        val kept = buildString(joined.length) {
            joined.forEach { c -> append(if (keep(c)) c else ' ') }
        }
        return WHITESPACE.replace(kept, " ").trim()
    }

    private fun keep(c: Char): Boolean = c == '.' || Character.isLetterOrDigit(c) || when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** The query's tokens, with band words ("FM", "MHz", "คลื่น") dropped unless nothing else is left. */
    fun queryTokens(query: String): List<String> {
        val raw = normalize(query).split(' ').filter { it.isNotEmpty() }
            .map(::stripBand)
            .map { it.trim('.') }
            .filter { it.isNotEmpty() }
        val meaningful = raw.filterNot { it in BAND_WORDS }
        return meaningful.ifEmpty { raw }
    }

    /** "fm95.5" → "95.5", "95.5mhz" → "95.5"; anything that is not band + number is left alone. */
    private fun stripBand(token: String): String {
        var t = token
        BAND_PREFIXES.firstOrNull { t.length > it.length && t.startsWith(it) && t[it.length].isDigit() }
            ?.let { t = t.substring(it.length) }
        BAND_SUFFIXES.firstOrNull { suffix ->
            t.length > suffix.length && t.endsWith(suffix) &&
                NUMBER_TOKEN.matches(t.dropLast(suffix.length).trimEnd('.'))
        }?.let { t = t.dropLast(it.length).trimEnd('.') }
        return t
    }

    /** What is sent to radio-browser's name search: the meaningful tokens only. */
    fun apiQuery(query: String): String = queryTokens(query).joinToString(" ")

    /** True when the query has a frequency-like token, which the API's substring search cannot judge. */
    fun hasNumber(query: String): Boolean = queryTokens(query).any { NUMBER_TOKEN.matches(it) }

    fun matches(name: String, query: String): Boolean = matchesTokens(normalize(name), queryTokens(query))

    private fun matchesTokens(normalized: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val numbers by lazy { NUMBER_IN_NAME.findAll(normalized).map { it.value }.toList() }
        return tokens.all { token ->
            if (NUMBER_TOKEN.matches(token)) numbers.any { numberMatches(token, it) }
            else normalized.contains(token)
        }
    }

    private fun numberMatches(token: String, inName: String): Boolean {
        val a = token.toBigDecimalOrNull() ?: return false
        val b = inName.toBigDecimalOrNull() ?: return false
        if (a.compareTo(b) == 0) return true
        if ('.' in token) return false
        if (token == inName.substringBefore('.')) return true
        return token.length >= 3 && '.' in inName && token == inName.replace(".", "")
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

    /** The matching [items], exact names first, then names starting with the first token, then the rest. */
    fun <T> rank(items: List<T>, query: String, name: (T) -> String): List<T> {
        val tokens = queryTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val normalizedQuery = normalize(query)
        val first = tokens.first()
        return items.map { it to normalize(name(it)) }
            .filter { (_, n) -> matchesTokens(n, tokens) }
            .sortedBy { (_, n) ->
                when {
                    n == normalizedQuery -> 0
                    n.startsWith(first) -> 1
                    else -> 2
                }
            }
            .map { it.first }
    }
}
