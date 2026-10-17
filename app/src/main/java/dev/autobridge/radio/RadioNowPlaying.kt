package dev.autobridge.radio

/**
 * What a radio "now playing" line says: the song and artist when the stream names them, and the
 * station either way.
 *
 * The session's title is the station's own name until the stream sends its in-band title (ICY
 * `StreamTitle`), which Media3 then puts in its place, usually as "Artist - Song". So a session
 * title that is not the station's name is the song, and it is split on the first " - ". A stream
 * that names nothing leaves the station as the only thing to show. Pure, so it is a JVM test.
 */
data class RadioNowPlaying(val song: String?, val artist: String?, val station: String) {
    companion object {
        private val SEPARATORS = listOf(" - ", " – ", " — ")

        /**
         * [station] is the queue item's own name (what the app put in), [sessionTitle] and
         * [sessionArtist] what the session reports now.
         */
        fun of(station: String?, sessionTitle: String?, sessionArtist: String?): RadioNowPlaying {
            val name = station?.trim().orEmpty()
            val title = sessionTitle?.trim().orEmpty()
            val stationName = name.ifBlank { title }
            if (title.isBlank() || title.equals(stationName, ignoreCase = true)) {
                return RadioNowPlaying(null, sessionArtist?.trim()?.takeIf { it.isNotBlank() }, stationName)
            }
            val split = SEPARATORS.firstNotNullOfOrNull { separator ->
                val at = title.indexOf(separator)
                if (at <= 0 || at + separator.length >= title.length) null
                else title.substring(0, at).trim() to title.substring(at + separator.length).trim()
            }
            return if (split != null) {
                RadioNowPlaying(song = split.second, artist = split.first, station = stationName)
            } else {
                RadioNowPlaying(song = title, artist = sessionArtist?.trim()?.takeIf { it.isNotBlank() }, station = stationName)
            }
        }
    }
}
