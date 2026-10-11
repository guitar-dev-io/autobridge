package dev.autobridge.duoscreen.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import dev.autobridge.duoscreen.R
import dev.autobridge.logging.StructuredLog

/**
 * The quick rail's keyboard: the car's own keyboard, opened straight away. What the driver types
 * goes to [onText] when they press the keyboard's search/enter key, and the screen goes back to
 * the panes, where [dev.autobridge.duoscreen.DuoScreenController.typeText] types it into the
 * pane's focused field.
 */
class DuoScreenKeyboardScreen(
    carContext: CarContext,
    private val paneId: Int,
    private val onText: (String) -> Unit
) : Screen(carContext) {
    private companion object {
        const val TAG = "AutoBridgeDuoKeyboard"
    }

    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                StructuredLog.i(TAG, "Pane $paneId: ${searchText.length} char(s) typed")
                screenManager.pop()
                onText(searchText)
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.duo_keyboard_hint, paneId + 1))
            .setShowKeyboardByDefault(true)
            .setItemList(ItemList.Builder().build())
            .build()
}
