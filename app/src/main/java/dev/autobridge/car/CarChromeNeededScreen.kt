package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import dev.autobridge.R

/**
 * Shown instead of a page that cannot play: a site that needs Google Chrome for Linux (Netflix and
 * the other protected-video services) was tapped, but Chrome is not installed.
 *
 * The install is a ~1 GB download plus Google's terms, so it cannot be done from the head unit;
 * this screen says where to do it on the phone. [onOpenAnyway] is the old behaviour, offered as a
 * second choice because a site marked "may not play" sometimes still does in the built-in browser.
 */
class CarChromeNeededScreen(
    carContext: CarContext,
    private val siteTitle: String,
    private val onOpenAnyway: () -> Unit
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val title = carContext.getString(R.string.car_chrome_needed_title)
        val message = MessageTemplate.Builder(
            carContext.getString(R.string.car_chrome_needed_message, siteTitle)
        )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_chrome_needed_ok))
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_chrome_needed_open_anyway))
                    .setOnClickListener {
                        screenManager.pop()
                        onOpenAnyway()
                    }
                    .build()
            )
        if (carContext.carAppApiLevel >= 7) {
            message.setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build())
        } else {
            @Suppress("DEPRECATION")
            message.setTitle(title).setHeaderAction(Action.BACK)
        }
        return message.build()
    }
}
