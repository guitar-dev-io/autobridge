package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.apps.InstalledAppRepository
import dev.autobridge.apps.QuickAppLauncher
import dev.autobridge.core.datastore.SessionRestoreStore
import dev.autobridge.core.datastore.SessionSnapshot
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.core.state.VehicleStateSession
import dev.autobridge.display.SurfaceProfile
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.settings.MirrorSettings
import dev.autobridge.vehicle.VehicleProfiles

/**
 * Car-native entry screen shown by DHU before the live mirror is opened.
 *
 * The phone UI and the Android Auto surface are separate products: this dashboard is deliberately
 * a host-managed Car App template, while [MirrorCarScreen] owns the projected phone pixels after
 * the driver selects Mirror. Keeping that boundary means the dashboard is visible immediately
 * and the existing MediaProjection/SurfaceCallback/speed-gate pipeline remains unchanged.
 */
class CarDashboardScreen(carContext: CarContext) : Screen(carContext) {
    private val vehicleStateSession = VehicleStateSession.acquire(carContext) {
        ProjectionService.stop(carContext)
        carContext.finishCarApp()
    }

    private var resumeSnapshot: SessionSnapshot? = null
    private var autoStartScheduled = false

    init {
        RuntimeContextStore.setConnected(true)
        RuntimeContextStore.setVehicleProfile(VehicleProfiles.GENERIC_ANDROID_AUTO)
        val snapshot = SessionRestoreStore.restore(carContext)
        if (snapshot != null && carContext.packageManager.getLaunchIntentForPackage(snapshot.packageName) != null) {
            resumeSnapshot = snapshot
            RuntimeContextStore.setCurrentFeature(snapshot.feature, snapshot.packageName)
            RuntimeContextStore.setDisplayPreferences(
                scaleMode = snapshot.scaleMode,
                rotationMode = snapshot.rotationMode,
                audioMode = snapshot.audioMode,
                fullscreen = snapshot.fullscreen
            )
        } else if (snapshot != null) {
            // Do not keep an unlaunchable package as a misleading resume action.
            SessionRestoreStore.clear(carContext)
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                vehicleStateSession.close()
                RuntimeContextStore.setConnected(false)
            }
        })
    }

    override fun onGetTemplate(): Template {
        scheduleAutoStartIfReady()
        val dashboard = ItemList.Builder()
            .addItem(statusRow("Android Auto", "Connected"))
            .addItem(statusRow("Head unit", SurfaceProfile.active.displayLabel))
            .addItem(statusRow("Vehicle", vehicleLabel()))

        resumeSnapshot?.let { snapshot ->
            val label = appLabel(snapshot.packageName)
            dashboard
                .addItem(
                    navigationRow(
                        "Resume $label",
                        "Reopen the last app; screen capture consent is never replayed"
                    ) {
                        if (!QuickAppLauncher.launch(carContext, snapshot.packageName, snapshot.profileId)) {
                            CarToast.makeText(carContext, "Could not resume $label", CarToast.LENGTH_SHORT).show()
                        }
                    }
                )
                .addItem(
                    Row.Builder()
                        .setTitle("Forget last app")
                        .addText("Remove the saved session shortcut")
                        .setOnClickListener {
                            SessionRestoreStore.clear(carContext)
                            resumeSnapshot = null
                            invalidate()
                        }
                        .build()
                )
        }

        dashboard
            .addItem(navigationRow("Mirror", "Open the live phone display") {
                if (FeaturePolicy.app.isAvailable(Feature.MIRROR)) {
                    screenManager.push(MirrorCarScreen(carContext))
                } else {
                    CarToast.makeText(carContext, FeaturePolicy.app.denialMessage(Feature.MIRROR), CarToast.LENGTH_SHORT).show()
                }
            })
            .addItem(navigationRow("Apps", "Open parked-only quick apps") {
                if (FeaturePolicy.app.isAvailable(Feature.QUICK_APPS)) {
                    screenManager.push(CarAppsScreen(carContext))
                } else {
                    CarToast.makeText(carContext, FeaturePolicy.app.denialMessage(Feature.QUICK_APPS), CarToast.LENGTH_SHORT).show()
                }
            })
            .addItem(navigationRow("Media", "Open MediaSession controls") {
                screenManager.push(CarMediaScreen(carContext))
            })
            .addItem(navigationRow("Settings", "Mirror, touch and safety settings") {
                screenManager.push(CarSettingsScreen(carContext, ::requestSafety))
            })
            .addItem(
                Row.Builder()
                    .setTitle("Speed safety")
                    .addText("Request or refresh CAR_SPEED permission")
                    .setOnClickListener { requestSafety() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Stop AutoBridge")
                    .addText("Leave the car app")
                    .setOnClickListener { carContext.finishCarApp() }
                    .build()
            )

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("AutoBridge • Car Home")
                    .build()
            )
            .setSingleList(dashboard.build())
            .build()
    }

    private fun scheduleAutoStartIfReady() {
        if (!MirrorSettings.autoStartMirror ||
            autoStartScheduled ||
            !MirrorCoordinator.isProjectionReady ||
            !FeaturePolicy.app.isAvailable(Feature.MIRROR)
        ) return

        autoStartScheduled = true
        carContext.mainExecutor.execute {
            screenManager.push(MirrorCarScreen(carContext))
        }
    }

    private fun requestSafety() {
        vehicleStateSession.requestPermission { granted ->
            CarToast.makeText(
                carContext,
                if (granted) "Speed safety enabled" else "Speed permission denied",
                CarToast.LENGTH_SHORT
            ).show()
            invalidate()
        }
    }

    private fun navigationRow(title: String, subtitle: String, onClick: () -> Unit): Row =
        Row.Builder()
            .setTitle(title)
            .addText(subtitle)
            .setOnClickListener { onClick() }
            .build()

    private fun statusRow(title: String, value: String): Row =
        Row.Builder()
            .setTitle(title)
            .addText(value)
            .setEnabled(false)
            .build()

    private fun appLabel(packageName: String): String = runCatching {
        carContext.packageManager.getApplicationLabel(
            carContext.packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    }.getOrDefault(packageName)

    private fun vehicleLabel(): String = when (RuntimeContextStore.vehicleState) {
        VehicleState.PARKED -> "PARKED"
        VehicleState.MOVING -> "MOVING (blocked)"
        VehicleState.UNKNOWN -> "UNKNOWN (blocked)"
    }
}
