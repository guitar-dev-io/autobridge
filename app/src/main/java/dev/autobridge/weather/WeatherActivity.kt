package dev.autobridge.weather

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * Phone home for the Weather section: the current snapshot for the saved place, a refresh action,
 * and a search box to change the place. No account, no API key — Open-Meteo's keyless endpoints
 * back both the geocoding search and the forecast (see [WeatherClient]).
 */
class WeatherActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, WeatherActivity::class.java)
    }

    private val accent = dev.autobridge.library.HomeSection.WEATHER.accent

    private var searchQuery = ""
    private var searchResults: List<WeatherPlace> = emptyList()
    private var searching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        refresh(forceRefresh = false)
    }

    private fun refresh(forceRefresh: Boolean) {
        WeatherRepository.load(this, forceRefresh) { render() }
    }

    private fun render() {
        val place = WeatherLocationStore.place(this)
        val body = AutoBridgeDesign.body(this)

        if (place == null) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = "No location set",
                    message = "Search for a city to see its current weather.",
                    accent = accent
                ),
                gap = 16
            )
        } else {
            body.stack(weatherCard(place), gap = 16)
            body.stack(
                AutoBridgeDesign.pill(this, "Refresh", accent = accent) { refresh(forceRefresh = true) },
                gap = 20
            )
        }

        body.stack(AutoBridgeDesign.sectionLabel(this, "Change location"), gap = 8)
        body.stack(
            AutoBridgeDesign.searchField(
                context = this,
                hint = "Search city…",
                initial = searchQuery
            ) { text ->
                searchQuery = text
                searching = text.isNotBlank()
                if (text.isBlank()) {
                    searchResults = emptyList()
                    render()
                } else {
                    WeatherRepository.search(text) { places ->
                        if (searchQuery == text) {
                            searchResults = places
                            searching = false
                            render()
                        }
                    }
                }
            },
            gap = 8
        )
        when {
            searching -> body.stack(AutoBridgeDesign.sectionLabel(this, "Searching…"))
            searchResults.isNotEmpty() -> searchResults.forEach { candidate ->
                body.stack(
                    AutoBridgeDesign.contentRow(
                        context = this,
                        title = candidate.name,
                        subtitle = listOfNotNull(candidate.admin1, candidate.country).joinToString(", "),
                        accent = accent,
                        trailing = "›"
                    ) { selectPlace(candidate) }
                )
            }
        }

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = "Weather",
                    subtitle = place?.displayName ?: "Not set",
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun selectPlace(place: WeatherPlace) {
        WeatherLocationStore.setPlace(this, place)
        WeatherRepository.invalidate()
        searchQuery = ""
        searchResults = emptyList()
        render()
        refresh(forceRefresh = true)
    }

    private fun weatherCard(place: WeatherPlace): View {
        val cached = WeatherRepository.cachedSnapshot()
        val subtitle = when {
            cached != null && cached.place == place -> {
                val temp = "${cached.temperatureC.roundToDisplay()}°C"
                val range = if (cached.todayHighC != null && cached.todayLowC != null) {
                    " · H:${cached.todayHighC.roundToDisplay()}° L:${cached.todayLowC.roundToDisplay()}°"
                } else ""
                "$temp · ${cached.condition}$range"
            }
            else -> "Loading…"
        }
        return AutoBridgeDesign.contentRow(
            context = this,
            title = place.displayName,
            subtitle = subtitle,
            accent = accent,
            badgeIcon = dev.autobridge.R.drawable.ic_tile_weather,
            trailing = null
        ) { refresh(forceRefresh = true) }
    }

    private fun Double.roundToDisplay(): Int = Math.round(this).toInt()
}
