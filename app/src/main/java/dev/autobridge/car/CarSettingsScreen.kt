package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.display.MirrorOrientationController
import dev.autobridge.display.ScreenOffController
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.input.AccessibilityInputBackend
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.settings.SettingsStore

/** Car-native settings summary; invasive phone permissions remain phone-side actions. */
class CarSettingsScreen(
    carContext: CarContext,
    private val onSafetyRequested: () -> Unit
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Display profile")
                    .addText(SurfaceProfile.active.displayLabel)
                    .setOnClickListener {
                        SurfaceProfile.active = if (SurfaceProfile.active == SurfaceProfile.DEFAULT) {
                            SurfaceProfile.FORD_NEXT_GEN
                        } else {
                            SurfaceProfile.DEFAULT
                        }
                        SettingsStore.persistSurfaceProfile(carContext, SurfaceProfile.active)
                        CarToast.makeText(
                            carContext,
                            "Profile: ${SurfaceProfile.active.displayLabel}",
                            CarToast.LENGTH_SHORT
                        ).show()
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Scale mode")
                    .addText("FIT • direct AUTO_MIRROR pipeline")
                    .setEnabled(false)
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Landscape mirror")
                    .addText(if (MirrorOrientationController.isEnabled) "Enabled on phone" else "Disabled")
                    .setEnabled(false)
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Touch control")
                    .addText(
                        when {
                            !FeaturePolicy.app.isAvailable(Feature.TOUCH) ->
                                FeaturePolicy.app.denialMessage(Feature.TOUCH)
                            ShizukuInputBackend.isPermissionGranted -> "Shizuku ready"
                            AccessibilityInputBackend.isAvailable -> "Accessibility ready"
                            else -> "Needs phone setup"
                        }
                    )
                    .setEnabled(false)
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Screen-off behavior")
                    .addText(if (ScreenOffController.survivesScreenOff()) "Continues" else "Pauses with phone display")
                    .setEnabled(false)
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Speed safety")
                    .addText(vehicleLabel())
                    .setOnClickListener { onSafetyRequested() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Stop mirroring")
                    .addText("Stop projection and leave AutoBridge")
                    .setOnClickListener {
                        ProjectionService.stop(carContext)
                        carContext.finishCarApp()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Settings")
                    .setStartHeaderAction(androidx.car.app.model.Action.BACK)
                    .build()
            )
            .setSingleList(list)
            .build()
    }

    private fun vehicleLabel(): String = when (RuntimeContextStore.vehicleState) {
        VehicleState.PARKED -> "PARKED"
        VehicleState.MOVING -> "MOVING (blocked)"
        VehicleState.UNKNOWN -> "UNKNOWN (blocked)"
    }
}
