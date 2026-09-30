package dev.autobridge.weather

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Blocking HTTP access to Open-Meteo's free geocoding and forecast endpoints.
 *
 * No API key: Open-Meteo's public endpoints are keyless for non-commercial use, which fits
 * AutoBridge's no-account, no-secrets-file model. Every method here does network I/O and must be
 * called off the main thread; [WeatherRepository] owns the threading, matching [XtreamClient]'s
 * split for the IPTV sources.
 */
internal object WeatherClient {
    private const val TAG = "WeatherClient"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_BODY_BYTES = 512 * 1024

    private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

    /** Resolves a free-text place name into up to 5 candidate [WeatherPlace]s. */
    fun search(query: String): List<WeatherPlace> {
        val url = "$GEOCODING_URL?name=${URLEncoder.encode(query, "UTF-8")}&count=5&language=en"
        val body = fetch(url)
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: return emptyList()
        val places = mutableListOf<WeatherPlace>()
        for (index in 0 until results.length()) {
            val entry = results.optJSONObject(index) ?: continue
            places += WeatherPlace(
                name = entry.optString("name"),
                admin1 = entry.optString("admin1").takeIf { it.isNotBlank() },
                country = entry.optString("country").takeIf { it.isNotBlank() },
                latitude = entry.optDouble("latitude"),
                longitude = entry.optDouble("longitude")
            )
        }
        return places
    }

    /** Current conditions plus today's high/low for [place]'s coordinates. */
    fun forecast(place: WeatherPlace): WeatherSnapshot {
        val url = "$FORECAST_URL?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,apparent_temperature,wind_speed_10m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
        val body = fetch(url)
        val root = JSONObject(body)
        val current = root.optJSONObject("current") ?: JSONObject()
        val daily = root.optJSONObject("daily")
        val highs = daily?.optJSONArray("temperature_2m_max")
        val lows = daily?.optJSONArray("temperature_2m_min")
        return WeatherSnapshot(
            place = place,
            temperatureC = current.optDouble("temperature_2m"),
            feelsLikeC = current.optDouble("apparent_temperature").takeIf { !it.isNaN() },
            windKph = current.optDouble("wind_speed_10m"),
            weatherCode = current.optInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            todayHighC = highs?.optDouble(0)?.takeIf { !it.isNaN() },
            todayLowC = lows?.optDouble(0)?.takeIf { !it.isNaN() },
            fetchedAtMs = System.currentTimeMillis()
        )
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AutoBridge/1.0 (Android)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Weather API returned HTTP $status")
            val body = StringBuilder()
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    body.append(buffer, 0, read)
                    if (body.length > MAX_BODY_BYTES) throw IllegalStateException("Weather response too large")
                }
            }
            return body.toString()
        } catch (error: Exception) {
            Log.w(TAG, "Weather request failed for $url", error)
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
