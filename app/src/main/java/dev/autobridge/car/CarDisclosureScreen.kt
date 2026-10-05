package dev.autobridge.car

import androidx.annotation.StringRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.core.consent.PermissionDisclosure

/**
 * The car-surface counterpart to [PermissionDisclosure]: the explanation a user reads on the head
 * unit before AutoBridge asks for a permission.
 *
 * A car app cannot show a dialog - there is no Activity behind the surface - so the disclosure is a
 * screen of its own, pushed in front of the action that needs it.
 *
 * [LongMessageTemplate] is the right template rather than [MessageTemplate]: the car host truncates
 * a message template's text, and a disclosure that is cut off in the middle is not a disclosure.
 * The host only permits it while parked, which is also where reading several lines of text belongs,
 * and [ParkedOnlyOnClickListener] makes the host enforce that on the action as well.
 */
class CarDisclosureScreen(
    carContext: CarContext,
    @StringRes private val title: Int,
    @StringRes private val body: Int,
    private val onContinue: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        LongMessageTemplate.Builder(carContext.getString(body))
            .setTitle(carContext.getString(title))
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.disclosure_continue))
                    .setOnClickListener(
                        ParkedOnlyOnClickListener.create {
                            // Leave before the system prompt appears: the user answers it on the
                            // phone, and coming back to a stale disclosure would be confusing.
                            screenManager.pop()
                            onContinue()
                        }
                    )
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.disclosure_not_now))
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
}
