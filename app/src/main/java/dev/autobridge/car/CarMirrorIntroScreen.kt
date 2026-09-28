package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
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

        val pane = Pane.Builder()
            .addRow(statusRow)
            .addRow(fitRow)
            .addRow(qualityRow)
            .addAction(
                Action.Builder()
                    .setTitle("Start Mirroring")
                    .setBackgroundColor(CarColor.BLUE)
                    .setOnClickListener { startMirroring() }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
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
