package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import dev.autobridge.R

/**
 * Lets the user type on the car display and returns the text to [CarBrowserScreen] via
 * [Screen.setResult]. Uses the Car App Library [SearchTemplate], which is the only template that
 * hosts a free-text input field on the car surface.
 *
 * Two uses: the address bar (a URL or search), and, with [field], a text field on the page - a
 * phone number on a sign-in form. The second used to look exactly like the first ("Type a URL or
 * search", an empty "No items" list), so filling a form read as searching. With a field it names
 * the field, says where the text goes, and its button fills rather than goes.
 */
class CarBrowserSearchScreen(
    carContext: CarContext,
    private val initialQuery: String,
    private val field: Field? = null,
) : Screen(carContext) {

    /** The page field being filled: what the page calls it, and its input type. */
    data class Field(val label: String, val type: String)

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
            .setSearchHint(hint())
            .setShowKeyboardByDefault(true)
            .apply { field?.let { setItemList(fieldNote(it)) } }
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(
                                carContext.getString(
                                    if (field != null) R.string.car_field_fill else R.string.car_browser_search_go
                                )
                            )
                            .setOnClickListener { submit(pendingText) }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun hint(): String {
        val target = field ?: return carContext.getString(R.string.car_browser_search_hint)
        if (target.label.isNotBlank()) return target.label
        return carContext.getString(
            when (target.type) {
                "tel" -> R.string.car_field_hint_tel
                "email" -> R.string.car_field_hint_email
                "number" -> R.string.car_field_hint_number
                else -> R.string.car_field_hint_text
            }
        )
    }

    /** One line in place of the empty "No items" list: which field the text goes into. */
    private fun fieldNote(target: Field): ItemList = ItemList.Builder()
        .addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.car_field_note_title))
                .addText(target.label.ifBlank { hint() })
                .build()
        )
        .build()

    private fun submit(text: String) {
        setResult(text.trim())
        screenManager.pop()
    }
}
