package dev.autobridge.iptv

import org.xmlpull.v1.XmlPullParser
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * A streaming reader for XMLTV guides (`<tv><channel/>…<programme/>…</tv>`).
 *
 * Guide files run from tens of kilobytes to hundreds of megabytes and cover a week, while a channel
 * row needs the programme on now and the one after it. So nothing is held as a document: the file
 * is pulled element by element and only programmes overlapping [fromMs, untilMs) are kept, up to
 * [maxProgrammes]. Everything else is skipped as it streams past.
 *
 * The parser is handed in rather than created here, so the same code runs on a device (the
 * platform's parser) and in a JVM unit test (kXML, which is what the platform's parser is).
 */
object XmlTvParser {
    /**
     * What a guide said, keyed for lookup: [programmes] by lowercased channel id, and [names] from
     * each channel's normalised `<display-name>` ([IptvGuideRules.nameKey]) to that id.
     */
    data class Guide(
        val programmes: Map<String, List<IptvProgramme>>,
        val names: Map<String, String>
    ) {
        /** The listings for an entry, tried by each of its [IptvGuideRules.matchKeys] in turn. */
        fun programmesFor(keys: List<String>): List<IptvProgramme> {
            keys.forEach { key ->
                val id = if (key in programmes) key else names[key]
                programmes[id]?.let { return it }
            }
            return emptyList()
        }

        val isEmpty: Boolean get() = programmes.isEmpty()

        companion object {
            val EMPTY = Guide(emptyMap(), emptyMap())
        }
    }

    const val DEFAULT_MAX_PROGRAMMES = 80_000

    fun parse(
        parser: XmlPullParser,
        fromMs: Long,
        untilMs: Long,
        maxProgrammes: Int = DEFAULT_MAX_PROGRAMMES
    ): Guide {
        val programmes = HashMap<String, MutableList<IptvProgramme>>()
        val names = HashMap<String, String>()
        var kept = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")?.trim()?.lowercase().orEmpty()
                        readChannelNames(parser).forEach { name ->
                            IptvGuideRules.nameKey(name)?.let { key -> if (id.isNotEmpty()) names.putIfAbsent(key, id) }
                        }
                    }
                    "programme" -> {
                        val channel = parser.getAttributeValue(null, "channel")?.trim()?.lowercase().orEmpty()
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        val inWindow = channel.isNotEmpty() && start != null && stop != null &&
                            stop > start && stop > fromMs && start < untilMs
                        if (inWindow && kept < maxProgrammes) {
                            val (title, category) = readProgrammeText(parser)
                            if (title.isNotBlank()) {
                                programmes.getOrPut(channel) { mutableListOf() } +=
                                    IptvProgramme(title, start!!, stop!!, category)
                                kept++
                            }
                        } else {
                            skip(parser)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return Guide(programmes, names)
    }

    /**
     * An XMLTV timestamp (`20261012183000 +0700`) as epoch milliseconds. The offset is optional and
     * UTC when absent, as the format says; seconds and minutes may be missing too. Null when the
     * value is not a timestamp.
     */
    fun parseTime(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.length < 12) return null
        val digits = value.takeWhile(Char::isDigit)
        if (digits.length < 12) return null
        val padded = digits.take(14).padEnd(14, '0')
        val offsetText = value.substring(digits.length).trim()
        return runCatching {
            val local = LocalDateTime.of(
                padded.substring(0, 4).toInt(), padded.substring(4, 6).toInt(), padded.substring(6, 8).toInt(),
                padded.substring(8, 10).toInt(), padded.substring(10, 12).toInt(), padded.substring(12, 14).toInt()
            )
            val offset = if (offsetText.matches(OFFSET)) {
                val sign = if (offsetText[0] == '-') -1 else 1
                ZoneOffset.ofHoursMinutes(
                    sign * offsetText.substring(1, 3).toInt(), sign * offsetText.substring(3, 5).toInt()
                )
            } else {
                ZoneOffset.UTC
            }
            local.toInstant(offset).toEpochMilli()
        }.getOrNull()
    }

    /** Every `<display-name>` inside the `<channel>` the parser is on; leaves it at `</channel>`. */
    private fun readChannelNames(parser: XmlPullParser): List<String> {
        val names = mutableListOf<String>()
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.START_TAG && parser.name == "display-name") {
                parser.nextText().trim().takeIf { it.isNotEmpty() }?.let(names::add)
            }
        }
        return names
    }

    /** The first `<title>` and `<category>` inside the `<programme>` the parser is on. */
    private fun readProgrammeText(parser: XmlPullParser): Pair<String, String> {
        var title = ""
        var category = ""
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> parser.nextText().trim().let { if (title.isEmpty()) title = it }
                "category" -> parser.nextText().trim().let { if (category.isEmpty()) category = it }
            }
        }
        return title to category
    }

    private fun skip(parser: XmlPullParser) {
        val depth = parser.depth
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return
            if (event == XmlPullParser.END_TAG && parser.depth == depth) return
        }
    }

    private val OFFSET = Regex("[+-]\\d{4}")
}
