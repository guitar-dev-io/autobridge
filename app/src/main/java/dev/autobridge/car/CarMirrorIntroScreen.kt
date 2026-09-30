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
            .setTitle("Fit to screen")
            .addText(if (MirrorCoordinator.requestedScale == ScaleMode.FIT) "On • letterboxed" else "Off • fill")
            .setOnClickListener {
                val next = if (MirrorCoordinator.requestedScale == ScaleMode.FIT) ScaleMode.FILL else ScaleMode.FIT
                MirrorCoordinator.setScaleMode(next)
                invalidate()
            }
            .build()

        val qualityRow = Row.Builder()
            .setTitle("Quality")
            .addText("High (1080p)")
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
            .setTitle("Start Mirroring")
            .addText(if (MirrorCoordinator.isMirroring) "Mirroring is running" else "Show the phone screen on the car")
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
                    .setTitle("Mirror")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }

    private fun startMirroring() {
        RecentActivityStore.record(
            carContext,
            RecentActivityStore.Entry(RecentActivityStore.Kind.MIRROR, "Phone screen", "Mirror")
        )
        if (!MirrorCoordinator.isProjectionReady) {
            CarToast.makeText(
                carContext,
                "Grant screen sharing on your phone to start mirroring",
                CarToast.LENGTH_LONG
            ).show()
        }
        CarNavigation.open(screenManager, "MirrorCarScreen") { MirrorCarScreen(carContext) }
    }

    private fun statusTitle(): String = when {
        MirrorCoordinator.isMirroring -> "Mirroring active"
        MirrorCoordinator.isProjectionReady -> "Ready to mirror"
        else -> "Ready to mirror"
    }

    private fun deviceLine(): String {
        val connected = dev.autobridge.core.state.RuntimeContextStore.context.value.connected
        return if (connected) "Phone connected • show your phone on Android Auto"
        else "Show your phone screen on Android Auto"
    }
}
