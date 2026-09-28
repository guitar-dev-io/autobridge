package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import dev.autobridge.settings.AppPreferences
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.ScaleMode
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.display.MirrorOrientationController
import dev.autobridge.display.ScreenOffController
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.input.AccessibilityInputBackend
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.input.TouchRouter
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.settings.MirrorSettings
import dev.autobridge.settings.SettingsStore

/** Car-native settings summary; invasive phone permissions remain phone-side actions. */
class CarSettingsScreen(
    carContext: CarContext,
    private val onSafetyRequested: () -> Unit
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val generalList = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Resume last session")
                    .addText("Restore the last screen and state on launch")
                    .setToggle(
                        Toggle.Builder { checked ->
                            AppPreferences.setResumeLastSession(carContext, checked)
                        }.setChecked(AppPreferences.resumeLastSession(carContext)).build()
                    )
                    .build()
            )
            .build()

        // Driving-aware UI toggles. IMPORTANT: these adjust PRESENTATION only. There is intentionally
        // NO setting to block/disable Browser, Mirror, Media, etc. while driving — AutoBridge's
        // driving mode is not a restriction system.
        val drivingList = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Enable driving-aware UI")
                    .addText("Bigger, decluttered controls while driving")
                    .setToggle(
                        Toggle.Builder { checked ->
                            AppPreferences.setDrivingAwareUi(carContext, checked)
                        }.setChecked(AppPreferences.drivingAwareUi(carContext)).build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Larger controls")
                    .setToggle(
                        Toggle.Builder { checked ->
                            AppPreferences.setLargerControls(carContext, checked)
                        }.setChecked(AppPreferences.largerControls(carContext)).build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Prefer voice actions")
                    .setToggle(
                        Toggle.Builder { checked ->
                            AppPreferences.setPreferVoice(carContext, checked)
                        }.setChecked(AppPreferences.preferVoice(carContext)).build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Reduce visual clutter")
                    .setToggle(
                        Toggle.Builder { checked ->
                            AppPreferences.setReduceClutter(carContext, checked)
                        }.setChecked(AppPreferences.reduceClutter(carContext)).build()
                    )
                    .build()
            )
            .build()

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
                    .setTitle("Renderer pipeline")
                    .addText(pipelineSubtitle())
                    .setOnClickListener { cyclePipeline() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Mirror rotation")
                    .addText(rotationSubtitle())
                    .setOnClickListener { cycleRotation() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Scale mode")
                    .addText(scaleSubtitle())
                    .setOnClickListener { cycleScale() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Landscape mirror (global)")
                    .addText(if (MirrorOrientationController.isEnabled) "Enabled: rotates the whole phone" else "Disabled")
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
                            else -> TouchRouter.multiTouchStatusLabel()
                        }
                    )
                    .setEnabled(false)
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Screen-off behavior")
                    .addText(
                        when {
                            MirrorSettings.screenOffOnAutoDim && ScreenOffController.panelOffAvailable() ->
                                "Panel off on auto-dim (privileged)"
                            ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.AUTO_MIRROR ->
                                "Pauses with phone display"
                            ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN ->
                                "Not guaranteed; source still follows phone display"
                            else -> "Unavailable; dedicated display required"
                        }
                    )
                    .setEnabled(false)
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Diagnostics")
                    .addText("Live FPS, uptime, reconnects and frame stats")
                    .setOnClickListener { CarNavigation.open(screenManager, "CarDiagnosticsScreen") { CarDiagnosticsScreen(carContext) } }
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
            .addSectionedList(SectionedItemList.create(generalList, "General"))
            .addSectionedList(SectionedItemList.create(drivingList, "Driving Mode"))
            .addSectionedList(SectionedItemList.create(list, "Mirror & Display"))
            .build()
    }

    private fun vehicleLabel(): String = when (RuntimeContextStore.vehicleState) {
        VehicleState.PARKED -> "PARKED"
        VehicleState.MOVING -> "MOVING (blocked)"
        VehicleState.UNKNOWN -> "UNKNOWN (blocked)"
    }

    private fun selfDrawnActive(): Boolean =
        ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN

    private fun pipelineSubtitle(): String = when (ScreenOffController.pipelineMode) {
        ScreenOffController.PipelineMode.AUTO_MIRROR -> "AUTO_MIRROR • zero-copy, FIT only"
        ScreenOffController.PipelineMode.SELF_DRAWN -> "SELF_DRAWN • app-controlled rotation/scale"
        ScreenOffController.PipelineMode.OWN_CONTENT -> "OWN_CONTENT • unavailable"
    }

    private fun rotationSubtitle(): String {
        val value = when (MirrorCoordinator.activeRotationMode) {
            RotationMode.AUTO -> "Auto"
            RotationMode.PHONE -> "Follow phone"
            RotationMode.PORTRAIT -> "Portrait"
            RotationMode.LANDSCAPE -> "Landscape"
        }
        return if (selfDrawnActive()) "$value • car only" else "$value • needs SELF_DRAWN"
    }

    private fun scaleSubtitle(): String =
        if (selfDrawnActive()) MirrorCoordinator.requestedScale.name else "FIT • needs SELF_DRAWN"

    private fun cyclePipeline() {
        if (MirrorCoordinator.isProjectionReady) {
            CarToast.makeText(carContext, "Stop mirroring before changing the renderer", CarToast.LENGTH_SHORT).show()
            return
        }
        val next = when (ScreenOffController.pipelineMode) {
            ScreenOffController.PipelineMode.AUTO_MIRROR -> ScreenOffController.PipelineMode.SELF_DRAWN
            ScreenOffController.PipelineMode.SELF_DRAWN,
            ScreenOffController.PipelineMode.OWN_CONTENT -> ScreenOffController.PipelineMode.AUTO_MIRROR
        }
        if (!MirrorCoordinator.setPipelineMode(next)) {
            CarToast.makeText(carContext, "Renderer pipeline is not available", CarToast.LENGTH_SHORT).show()
            return
        }
        invalidate()
    }

    private fun cycleRotation() {
        val next = when (MirrorCoordinator.activeRotationMode) {
            RotationMode.AUTO -> RotationMode.LANDSCAPE
            RotationMode.LANDSCAPE -> RotationMode.PORTRAIT
            RotationMode.PORTRAIT -> RotationMode.PHONE
            RotationMode.PHONE -> RotationMode.AUTO
        }
        MirrorCoordinator.setRotationMode(next)
        if (!selfDrawnActive() && next != RotationMode.AUTO && next != RotationMode.PHONE) {
            CarToast.makeText(
                carContext,
                "Switch renderer to SELF_DRAWN so rotation applies to the car only",
                CarToast.LENGTH_LONG
            ).show()
        }
        invalidate()
    }

    private fun cycleScale() {
        val modes = ScaleMode.entries
        val next = modes[(modes.indexOf(MirrorCoordinator.requestedScale) + 1) % modes.size]
        MirrorCoordinator.setScaleMode(next)
        if (!selfDrawnActive() && next != ScaleMode.FIT) {
            CarToast.makeText(carContext, "${next.name} requires the SELF_DRAWN renderer", CarToast.LENGTH_SHORT).show()
        }
        invalidate()
    }
}
