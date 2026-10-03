package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import dev.autobridge.R

/**
 * Lets the user type a URL or search query on the car display and returns it to
 * [CarBrowserScreen] via [Screen.setResult]. Uses the Car App Library [SearchTemplate], which is
 * the only template that hosts a free-text input field on the car surface.
 */
class CarBrowserSearchScreen(
    carContext: CarContext,
    private val initialQuery: String,
) : Screen(carContext) {

    private var pendingText: String = initialQuery

    override fun onGetTemplate(): Template {
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) {
                    pendingText = searchText
                }

                override fun onSearchSubmitted(searchText: String) {
                    submit(searchText)
                }
            }
        )
            .setHeaderAction(Action.BACK)
            .setInitialSearchText(initialQuery)
            .setSearchHint(carContext.getString(R.string.car_browser_search_hint))
            .setShowKeyboardByDefault(true)
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(carContext.getString(R.string.car_browser_search_go))
                            .setOnClickListener { submit(pendingText) }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun submit(text: String) {
        setResult(text.trim())
        screenManager.pop()
    }
}
