package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.core.model.ScaleMode
import dev.autobridge.core.state.RecentActivityStore
import dev.autobridge.mirror.MirrorCoordinator

/**
 * Mirror status / setup surface matching the reference: device + status, a primary "Start
 * Mirroring" action, and quick options (Fit to screen, Quality, Include audio). It reuses the
 * existing mirror engine — the actual pixels are still owned by [MirrorCarScreen]'s
 * NavigationTemplate surface, and screen-capture consent is granted on the phone. This screen only
 * presents state and routes into the live mirror; it never starts capture silently.
 */
class CarMirrorIntroScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val statusRow = Row.Builder()
            .setTitle(statusTitle())
            .addText(deviceLine())
            .build()

        val fitRow = Row.Builder()
            .setTitle(carContext.getString(R.string.car_mirror_intro_fit))
            .addText(carContext.getString(if (MirrorCoordinator.requestedScale == ScaleMode.FIT) R.string.car_mirror_intro_fit_on else R.string.car_mirror_intro_fit_off))
            .setOnClickListener {
                val next = if (MirrorCoordinator.requestedScale == ScaleMode.FIT) ScaleMode.FILL else ScaleMode.FIT
                MirrorCoordinator.setScaleMode(next)
                invalidate()
            }
            .build()

        val qualityRow = Row.Builder()
            .setTitle(carContext.getString(R.string.car_mirror_intro_quality))
            .addText(carContext.getString(R.string.car_mirror_intro_quality_value))
            .setOnClickListener {
                CarNavigation.open(screenManager, "CarSettingsScreen") { CarSettingsScreen(carContext) {} }
            }
            .build()

        // ListTemplate, not PaneTemplate: Fit and Quality are rows the user taps, and a Pane row
        // may not carry a click listener. PaneTemplate.Builder.build() threw
        // "A click listener is not allowed on the row" out of onGetTemplate, which the host
        // rethrows on the main thread - the whole process died and Android Auto restarted the app
        // at its root screen, so the tile looked like it blanked and bounced back to Home.
        // The primary action becomes the first row rather than a header action or an action strip:
        // a header action must carry an icon (ACTIONS_CONSTRAINTS_MULTI_HEADER sets
        // requireActionIcons), and ListTemplate.setActionStrip is deprecated. Both alternatives
        // risk the same kind of constraint violation this screen is being fixed for.
        val startRow = Row.Builder()
            .setTitle(carContext.getString(R.string.car_mirror_intro_start))
            .addText(carContext.getString(if (MirrorCoordinator.isMirroring) R.string.car_mirror_intro_running else R.string.car_mirror_intro_start_caption))
            .setOnClickListener { startMirroring() }
            .build()

        val items = ItemList.Builder()
            .addItem(startRow)
            .addItem(statusRow)
            .addItem(fitRow)
            .addItem(qualityRow)
            .build()

        return ListTemplate.Builder()
            .setSingleList(items)
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_mirror_intro_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun startMirroring() {
        RecentActivityStore.record(
            carContext,
            RecentActivityStore.Entry(
                RecentActivityStore.Kind.MIRROR,
                carContext.getString(R.string.car_intro_phone_screen),
                carContext.getString(R.string.section_mirror)
            )
        )
        if (!MirrorCoordinator.isProjectionReady) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_mirror_intro_grant),
                CarToast.LENGTH_LONG
            ).show()
        }
        CarNavigation.open(screenManager, "MirrorCarScreen") { MirrorCarScreen(carContext) }
    }

    private fun statusTitle(): String = carContext.getString(
        if (MirrorCoordinator.isMirroring) R.string.car_mirror_intro_status_active
        else R.string.car_mirror_intro_status_ready
    )

    private fun deviceLine(): String {
        val connected = dev.autobridge.core.state.RuntimeContextStore.context.value.connected
        return carContext.getString(
            if (connected) R.string.car_mirror_intro_device_connected
            else R.string.car_mirror_intro_device_disconnected
        )
    }
}
