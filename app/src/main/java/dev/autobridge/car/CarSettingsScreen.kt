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
import dev.autobridge.R
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
                    .setTitle(carContext.getString(R.string.car_settings_resume_session))
                    .addText(carContext.getString(R.string.car_settings_resume_session_caption))
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
                    .setTitle(carContext.getString(R.string.car_settings_driving_ui))
                    .addText(carContext.getString(R.string.car_settings_driving_ui_caption))
                    .setToggle(
                        Toggle.Builder { checked ->
                            AppPreferences.setDrivingAwareUi(carContext, checked)
                        }.setChecked(AppPreferences.drivingAwareUi(carContext)).build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_larger_controls))
                    .setToggle(
                        Toggle.Builder { checked ->
                            AppPreferences.setLargerControls(carContext, checked)
                        }.setChecked(AppPreferences.largerControls(carContext)).build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_prefer_voice))
                    .setToggle(
                        Toggle.Builder { checked ->
                            AppPreferences.setPreferVoice(carContext, checked)
                        }.setChecked(AppPreferences.preferVoice(carContext)).build()
                    )
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_reduce_clutter))
                    .setToggle(
                        Toggle.Builder { checked ->
                            AppPreferences.setReduceClutter(carContext, checked)
                        }.setChecked(AppPreferences.reduceClutter(carContext)).build()
                    )
                    .build()
            )
            // The one row in this section that is not about presentation: it is where location
            // becomes readable for the speed display, so it is also where the user is told what
            // that means. Turning it on never grants anything by itself.
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_gps_speed))
                    .addText(carContext.getString(R.string.car_settings_gps_speed_caption))
                    .setToggle(
                        Toggle.Builder { checked ->
                            if (checked) enableGpsSpeed() else AppPreferences.setGpsSpeed(carContext, false)
                        }.setChecked(AppPreferences.gpsSpeed(carContext)).build()
                    )
                    .build()
            )
            .build()

        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_display_profile))
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
                            carContext.getString(
                                R.string.car_settings_profile_toast,
                                SurfaceProfile.active.displayLabel
                            ),
                            CarToast.LENGTH_SHORT
                        ).show()
                        invalidate()
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_renderer))
                    .addText(pipelineSubtitle())
                    .setOnClickListener { cyclePipeline() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_rotation))
                    .addText(rotationSubtitle())
                    .setOnClickListener { cycleRotation() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_scale))
                    .addText(scaleSubtitle())
                    .setOnClickListener { cycleScale() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_landscape_global))
                    .addText(
                        carContext.getString(
                            if (MirrorOrientationController.isEnabled) R.string.car_settings_landscape_on
                            else R.string.car_settings_landscape_off
                        )
                    )
                    .setEnabled(false)
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_touch))
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
                    .setTitle(carContext.getString(R.string.car_settings_screen_off))
                    .addText(
                        when {
                            MirrorSettings.screenOffOnAutoDim && ScreenOffController.panelOffAvailable() ->
                                R.string.car_settings_screen_off_panel
                            ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.AUTO_MIRROR ->
                                R.string.car_settings_screen_off_follows
                            ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN ->
                                R.string.car_settings_screen_off_partial
                            else -> R.string.car_settings_screen_off_unavailable
                        }.let { carContext.getString(it) }
                    )
                    .setEnabled(false)
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_diagnostics))
                    .addText(carContext.getString(R.string.car_settings_diagnostics_caption))
                    .setOnClickListener { CarNavigation.open(screenManager, "CarDiagnosticsScreen") { CarDiagnosticsScreen(carContext) } }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_log))
                    .addText(carContext.getString(R.string.car_settings_log_caption))
                    .setOnClickListener { CarNavigation.open(screenManager, "CarLogScreen") { CarLogScreen(carContext) } }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_speed_safety))
                    .addText(vehicleLabel())
                    .setOnClickListener { onSafetyRequested() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_stop_mirroring))
                    .addText(carContext.getString(R.string.car_settings_stop_mirroring_caption))
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
                    .setTitle(carContext.getString(R.string.car_settings_title))
                    .setStartHeaderAction(androidx.car.app.model.Action.BACK)
                    .build()
            )
            .addSectionedList(SectionedItemList.create(generalList, carContext.getString(R.string.car_settings_section_general)))
            .addSectionedList(SectionedItemList.create(drivingList, carContext.getString(R.string.car_settings_section_driving)))
            .addSectionedList(SectionedItemList.create(list, carContext.getString(R.string.car_settings_section_mirror)))
            .build()
    }

    /**
     * Turns on GPS speed, disclosure first.
     *
     * The explanation is shown every time the user enables this, whether or not the app already
     * holds location permission: the permission may have been granted for something else entirely
     * (a web page asking for a fix), and this is the point at which location starts being read for
     * a new purpose. Only after the user goes on is the preference written, and only then is
     * Android asked for the permission if it is missing - a denial leaves the setting off.
     */
    private fun enableGpsSpeed() {
        screenManager.push(
            CarDisclosureScreen(
                carContext,
                R.string.gps_speed_disclosure_title,
                R.string.gps_speed_disclosure_body
            ) { requestLocationThenEnable() }
        )
        // The toggle flipped itself on screen; redraw it from the preference, which is still off
        // until the disclosure is accepted.
        invalidate()
    }

    private fun requestLocationThenEnable() {
        val permission = android.Manifest.permission.ACCESS_FINE_LOCATION
        if (carContext.checkSelfPermission(permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            AppPreferences.setGpsSpeed(carContext, true)
            invalidate()
            return
        }
        // The car host cannot show the system dialog; it tells the driver to look at their phone.
        carContext.requestPermissions(listOf(permission), carContext.mainExecutor) { granted, _ ->
            val allowed = permission in granted
            AppPreferences.setGpsSpeed(carContext, allowed)
            if (!allowed) {
                CarToast.makeText(
                    carContext,
                    carContext.getString(R.string.gps_speed_denied),
                    CarToast.LENGTH_LONG
                ).show()
            }
            invalidate()
        }
    }

    private fun vehicleLabel(): String = carContext.getString(
        when (RuntimeContextStore.vehicleState) {
            VehicleState.PARKED -> R.string.car_vehicle_parked
            VehicleState.MOVING -> R.string.car_vehicle_moving
            VehicleState.UNKNOWN -> R.string.car_vehicle_unknown
        }
    )

    private fun selfDrawnActive(): Boolean =
        ScreenOffController.pipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN

    private fun pipelineSubtitle(): String = carContext.getString(
        when (ScreenOffController.pipelineMode) {
            ScreenOffController.PipelineMode.AUTO_MIRROR -> R.string.car_pipeline_auto_mirror
            ScreenOffController.PipelineMode.SELF_DRAWN -> R.string.car_pipeline_self_drawn
            ScreenOffController.PipelineMode.OWN_CONTENT -> R.string.car_pipeline_own_content
        }
    )

    private fun rotationSubtitle(): String {
        val value = carContext.getString(
            when (MirrorCoordinator.activeRotationMode) {
                RotationMode.AUTO -> R.string.car_rotation_auto
                RotationMode.PHONE -> R.string.car_rotation_phone
                RotationMode.PORTRAIT -> R.string.car_rotation_portrait
                RotationMode.LANDSCAPE -> R.string.car_rotation_landscape
            }
        )
        return carContext.getString(
            if (selfDrawnActive()) R.string.car_rotation_car_only
            else R.string.car_rotation_needs_self_drawn,
            value
        )
    }

    private fun scaleSubtitle(): String =
        if (selfDrawnActive()) MirrorCoordinator.requestedScale.name
        else carContext.getString(R.string.car_scale_needs_self_drawn)

    private fun cyclePipeline() {
        if (MirrorCoordinator.isProjectionReady) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_stop_before_renderer),
                CarToast.LENGTH_SHORT
            ).show()
            return
        }
        val next = when (ScreenOffController.pipelineMode) {
            ScreenOffController.PipelineMode.AUTO_MIRROR -> ScreenOffController.PipelineMode.SELF_DRAWN
            ScreenOffController.PipelineMode.SELF_DRAWN,
            ScreenOffController.PipelineMode.OWN_CONTENT -> ScreenOffController.PipelineMode.AUTO_MIRROR
        }
        if (!MirrorCoordinator.setPipelineMode(next)) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_renderer_unavailable),
                CarToast.LENGTH_SHORT
            ).show()
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
                carContext.getString(R.string.car_needs_self_drawn_rotation),
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
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_scale_requires_self_drawn, next.name),
                CarToast.LENGTH_SHORT
            ).show()
        }
        invalidate()
    }
}
