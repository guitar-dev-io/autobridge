package dev.autobridge.car

import androidx.annotation.StringRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import dev.autobridge.R

/**
 * Asks before a list is wiped. A car app cannot show a dialog, so the confirmation is a screen of
 * its own, pushed in front of the clear; it pops itself whichever way the user answers.
 */
class CarConfirmClearScreen(
    carContext: CarContext,
    @StringRes private val title: Int,
    @StringRes private val message: Int,
    private val onConfirm: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(message))
            .setTitle(carContext.getString(title))
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.action_clear))
                    .setOnClickListener {
                        onConfirm()
                        screenManager.pop()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.action_cancel))
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
}
