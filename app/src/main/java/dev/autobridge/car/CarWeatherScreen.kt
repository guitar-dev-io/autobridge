package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.weather.WeatherLocationStore
import dev.autobridge.weather.WeatherRepository
import kotlin.math.roundToInt

/**
 * Read-only current conditions for the place picked on the phone. Picking a place needs a text
 * search, which is a phone job here (see [dev.autobridge.weather.WeatherActivity]) the same way
 * IPTV source credentials are entered on the phone and only played back on the car.
 */
class CarWeatherScreen(carContext: CarContext) : Screen(carContext) {
    private var loading = false

    init {
        refresh(forceRefresh = false)
    }

    override fun onGetTemplate(): Template {
        val place = WeatherLocationStore.place(carContext)
        val header = Header.Builder().setTitle(carContext.getString(R.string.car_weather_title)).setStartHeaderAction(Action.BACK).build()

        if (place == null) {
            return MessageTemplate.Builder(
                carContext.getString(R.string.car_weather_no_location)
            ).setHeader(header).build()
        }

        val snapshot = WeatherRepository.cachedSnapshot()
        val list = ItemList.Builder()
        if (snapshot != null && snapshot.place == place) {
            list.addItem(row(carContext.getString(R.string.car_weather_now), "${snapshot.temperatureC.round()}°C · ${snapshot.condition}"))
            if (snapshot.feelsLikeC != null) list.addItem(row(carContext.getString(R.string.car_weather_feels_like), "${snapshot.feelsLikeC.round()}°C"))
            if (snapshot.todayHighC != null && snapshot.todayLowC != null) {
                list.addItem(row(carContext.getString(R.string.car_weather_today), "H:${snapshot.todayHighC.round()}° L:${snapshot.todayLowC.round()}°"))
            }
            list.addItem(row(carContext.getString(R.string.car_weather_wind), "${snapshot.windKph.round()} km/h"))
        } else {
            list.addItem(row(place.displayName, carContext.getString(if (loading) R.string.car_weather_loading else R.string.car_weather_tap_refresh)))
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_weather_title))
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.REFRESH))
                            .setOnClickListener { refresh(forceRefresh = true) }
                            .build()
                    )
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    private fun refresh(forceRefresh: Boolean) {
        loading = true
        WeatherRepository.load(carContext, forceRefresh) { result ->
            loading = false
            when (result) {
                is WeatherRepository.Result.Failed ->
                    CarToast.makeText(carContext, result.message, CarToast.LENGTH_SHORT).show()
                else -> Unit
            }
            invalidate()
        }
    }

    private fun row(title: String, value: String): Row =
        Row.Builder().setTitle(title).addText(value).build()

    private fun Double.round(): Int = roundToInt()
}
