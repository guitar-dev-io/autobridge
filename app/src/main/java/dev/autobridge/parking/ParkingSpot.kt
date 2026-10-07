package dev.autobridge.parking

import java.util.Locale

/** Where the car was left, and a note to find it by ("B2, pillar 14"). */
data class ParkingSpot(val lat: Double, val lon: Double, val note: String, val savedMs: Long) {
    /**
     * A `geo:` link that Maps opens on the spot, with the note as its label. The label is kept to
     * safe characters: the brackets and the parentheses of a `geo:` query are not escaped.
     */
    fun geoUri(): String {
        val position = "%.6f,%.6f".format(Locale.US, lat, lon)
        // Letters include their combining marks: a Thai note keeps its vowels and tone marks.
        val label = note.filter { it.isLetterOrDigit() || it.isMark() || it == ' ' || it == '-' || it == ',' }.trim()
        return "geo:$position?q=$position" + if (label.isNotEmpty()) "(${java.net.URLEncoder.encode(label, "UTF-8")})" else ""
    }

    /** The plain position, `geo:lat,lon`, which the car's navigation intent takes. */
    fun navigationUri(): String = "geo:" + "%.6f,%.6f".format(Locale.US, lat, lon)

    private fun Char.isMark(): Boolean = Character.getType(this).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
    }

    companion object {
        /** True for a latitude and longitude that can be a place on Earth. */
        fun isValid(lat: Double, lon: Double): Boolean =
            lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
    }
}
