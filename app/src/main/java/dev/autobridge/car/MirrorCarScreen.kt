package dev.autobridge.car

import android.graphics.Rect
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.VehicleStateSession
import dev.autobridge.display.ScreenPowerController
import dev.autobridge.input.InputBackend
import dev.autobridge.input.TouchRouter
import dev.autobridge.apps.QuickAppLauncher
import dev.autobridge.mirror.CarSurfaceManager
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.remote.AutoBridgeStateRepository
import dev.autobridge.remote.MirrorStatus
import dev.autobridge.remote.RemoteScreen
import dev.autobridge.settings.MirrorSettings

class MirrorCarScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private companion object {
        const val TAG = "AutoBridgeCarScreen"
    }

    private val appManager = carContext.getCarService(AppManager::class.java)
    private val surfaceManager = CarSurfaceManager()
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Region of the surface actually visible to the driver. */
    private var visibleArea: Rect? = null

    private val vehicleStateSession = VehicleStateSession.acquire(carContext) {
        // Strong safety behavior: stop the projection and leave the car app as soon as motion is detected.
        ProjectionService.stop(carContext)
        carContext.finishCarApp()
    }
    private val speedSubscription = dev.autobridge.speed.SpeedManager.acquire(carContext)

    init {
        Log.i(TAG, "Creating MirrorCarScreen")
        MirrorSurfaceOwnership.claim(this)
        appManager.setSurfaceCallback(this)
        Log.i(TAG, "Surface callback registered")

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Publish mirror status so the Mobile Remote reflects the car screen in real time.
                AutoBridgeStateRepository.setMirrorStatus(
                    if (MirrorCoordinator.isMirroring) MirrorStatus.ACTIVE else MirrorStatus.READY
                )
            }

            override fun onStop(owner: LifecycleOwner) {
                if (AutoBridgeStateRepository.current.currentScreen == RemoteScreen.MIRROR) {
                    AutoBridgeStateRepository.setCurrentScreen(RemoteScreen.HOME)
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                vehicleStateSession.close()
                speedSubscription.close()
                if (!MirrorSurfaceOwnership.release(this@MirrorCarScreen)) return
                appManager.setSurfaceCallback(null)
                surfaceManager.clear()
                ProjectionService.onCarSurfaceDisconnected(carContext)
                AutoBridgeStateRepository.setMirrorStatus(MirrorStatus.INACTIVE)
            }
        })
    }

    override fun onGetTemplate(): Template {
        Log.i(TAG, "Building NavigationTemplate")
        // Keep the live phone image clear. Phone navigation and Stop remain in the Controls panel.
        val topActions = ActionStrip.Builder()
            .addAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_panel))
                    .setTitle("Controls")
                    .setOnClickListener { openControls() }
                    .build()
            )
            .build()

        val safetyAction = Action.Builder()
            .setIcon(
                androidx.car.app.model.CarIcon.Builder(
                    androidx.core.graphics.drawable.IconCompat.createWithResource(
                        carContext,
                        android.R.drawable.ic_lock_lock
                    )
                ).build()
            )
            .setOnClickListener {
                vehicleStateSession.requestPermission { granted ->
                    CarToast.makeText(
                        carContext,
                        if (granted) "Speed safety enabled" else "Speed permission denied",
                        CarToast.LENGTH_SHORT
                    ).show()
                    invalidate()
                }
            }
            .build()

        val mapActions = ActionStrip.Builder()
            .apply { speedAction()?.let { addAction(it) } }
            .addAction(safetyAction)
            .addAction(panelAction())
            .addAction(Action.PAN)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(topActions)
            .setMapActionStrip(mapActions)
            .build()
    }

    private fun panelAction(): Action =
        Action.Builder()
            .setIcon(carIcon(R.drawable.ic_car_panel))
            .setOnClickListener { openControls() }
            .build()

    private fun openControls() {
        screenManager.push(
            MirrorControlScreen(carContext) {
                vehicleStateSession.requestPermission { granted ->
                    CarToast.makeText(
                        carContext,
                        if (granted) "Speed safety enabled" else "Speed permission denied",
                        CarToast.LENGTH_SHORT
                    ).show()
                    invalidate()
                }
            }
        )
    }

    /**
     * Read-only speed readout in the map action strip (e.g. "72 km/h"). Non-invasive: the live
     * mirror surface is OS-owned, so the speed is shown as a strip action rather than drawn over the
     * pixels. Returns null when no valid reading exists so the strip stays clean.
     */
    private fun speedAction(): Action? {
        val sample = dev.autobridge.speed.SpeedManager.speed.value
        if (!sample.valid) return null
        return Action.Builder()
            .setTitle("${sample.kmh.toInt()} km/h")
            .setOnClickListener { invalidate() }
            .build()
    }

    private fun carIcon(@DrawableRes resourceId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resourceId)).build()

    /**
     * A parked-only system navigation button. FeaturePolicy is the entry-point decision and the
     * backend performs a second policy check immediately before injection.
     */
    private fun navAction(
        title: String,
        @DrawableRes iconResourceId: Int,
        action: InputBackend.SystemAction
    ): Action =
        Action.Builder()
            .setIcon(carIcon(iconResourceId))
            .setTitle(title)
            .setOnClickListener {
                ScreenPowerController.userActivity()
                if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) {
                    CarToast.makeText(
                        carContext,
                        FeaturePolicy.app.denialMessage(Feature.TOUCH),
                        CarToast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (!TouchRouter.systemAction(action)) {
                    CarToast.makeText(
                        carContext,
                        "Enable AutoBridge accessibility service or Shizuku for controls",
                        CarToast.LENGTH_SHORT
                    ).show()
                }
            }
            .build()

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        val surface = surfaceContainer.surface ?: return
        ProjectionService.onCarSurfaceConnected()
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        Log.i(TAG, "Surface ${surfaceWidth}x${surfaceHeight} dpi=${surfaceContainer.dpi}")
        val attached = surfaceManager.onSurfaceAvailable(surfaceContainer)
        if (!attached) {
            ProjectionService.onMirrorBindingFailed(
                carContext,
                "Android Auto mirror surface could not be attached"
            )
            return
        }
        if (MirrorSettings.autoLaunchLastApp && MirrorCoordinator.isMirroring) {
            QuickAppLauncher.autoLaunchLastSession(carContext)
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        if (!surfaceManager.onSurfaceDestroyed(surfaceContainer)) return
        ProjectionService.onCarSurfaceDisconnected(carContext)
        surfaceWidth = 0
        surfaceHeight = 0
        visibleArea = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        Log.i(TAG, "Visible area changed to $visibleArea")
        this.visibleArea = visibleArea
        surfaceManager.onVisibleAreaChanged(visibleArea)
    }

    override fun onClick(x: Float, y: Float) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        ScreenPowerController.userActivity()
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) {
            CarToast.makeText(
                carContext,
                FeaturePolicy.app.denialMessage(Feature.TOUCH),
                CarToast.LENGTH_SHORT
            ).show()
            return
        }
        if (visibleArea?.contains(x.toInt(), y.toInt()) == false) {
            return
        }
        if (!TouchRouter.tap(carContext, x, y, surfaceWidth, surfaceHeight)) {
            CarToast.makeText(
                carContext,
                "Enable AutoBridge accessibility service or Shizuku for touch",
                CarToast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        ScreenPowerController.userActivity()
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return
        TouchRouter.pinch(carContext, focusX, focusY, surfaceWidth, surfaceHeight, scaleFactor)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        ScreenPowerController.userActivity()
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return
        TouchRouter.scroll(carContext, distanceX, distanceY)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        ScreenPowerController.userActivity()
        if (!FeaturePolicy.app.isAvailable(Feature.TOUCH)) return
        TouchRouter.scroll(carContext, velocityX / 8f, velocityY / 8f)
    }
}
