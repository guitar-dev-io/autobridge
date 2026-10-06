package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.iptv.ChannelQueue
import dev.autobridge.settings.VideoDisclaimerStore

/**
 * One-time safety warning shown before the very first video playback on the head unit. The user
 * must explicitly tap "I understand" before [CarVideoScreen] is reached; declining returns to
 * wherever the video was launched from without starting playback.
 *
 * This is a warning gate, not a content restriction: once accepted it is remembered
 * ([VideoDisclaimerStore]) and never shown again on this device, and it does not replace or bypass
 * the existing parked-only [dev.autobridge.safety.SafetyEnforcement] check that CarVideoScreen still
 * performs on every frame.
 */
class CarVideoDisclaimerScreen(
    carContext: CarContext,
    private val uri: String,
    private val title: String,
    private val queue: ChannelQueue? = null,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_disclaimer_title))
                    .addText(carContext.getString(R.string.car_disclaimer_body))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_disclaimer_confirm_title))
                    .addText(carContext.getString(R.string.car_disclaimer_confirm_body))
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_disclaimer_continue))
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setOnClickListener { accept() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.action_cancel))
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_disclaimer_header))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun accept() {
        VideoDisclaimerStore.setAccepted(carContext, true)
        // Replace this warning with the video itself, so Back from the video returns to the
        // library/stream screen that launched it rather than back through the warning.
        screenManager.pop()
        screenManager.push(CarVideoScreen(carContext, uri, title, queue))
    }
}
