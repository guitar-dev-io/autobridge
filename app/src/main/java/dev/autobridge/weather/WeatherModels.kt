package dev.autobridge.weather

/** One geocoding match: a place name plus the coordinates a forecast call needs. */
data class WeatherPlace(
    val name: String,
    val admin1: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double
) {
    /** "Bangkok, Thailand" when both parts exist, otherwise whatever is available. */
    val displayName: String
        get() = listOfNotNull(name, admin1?.takeIf { it != name }, country).distinct().joinToString(", ")
}

/** Current conditions plus today's high/low, resolved for one [WeatherPlace]. */
data class WeatherSnapshot(
    val place: WeatherPlace,
    val temperatureC: Double,
    val feelsLikeC: Double?,
    val windKph: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val todayHighC: Double?,
    val todayLowC: Double?,
    val fetchedAtMs: Long
) {
    val condition: String get() = WeatherCodes.describe(weatherCode)
}

/**
 * WMO weather-interpretation codes as used by Open-Meteo's `weathercode` field. Only the ranges
 * needed for a short one-line condition are mapped; unknown codes fall back to "Weather".
 */
object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2, 3 -> "Partly cloudy"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Weather"
    }
}
