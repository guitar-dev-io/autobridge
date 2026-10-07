package dev.autobridge.duoscreen.car

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
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.duoscreen.R
import dev.autobridge.logging.StructuredLog
import dev.autobridge.duoscreen.DuoScreenController
import dev.autobridge.duoscreen.DuoScreenHost
import dev.autobridge.duoscreen.layout.DuoScreenPreset
import dev.autobridge.duoscreen.system.DuoScreenShizukuOps

/**
 * The car side of a Duo Screen session: hands the host's Surface and gestures to
 * [dev.autobridge.duoscreen.DuoScreenController] and does nothing else itself.
 *
 * Panes start from the preset chosen in [dev.autobridge.duoscreen.layout.DuoScreenStore] and are
 * rearranged by hand in edit mode; [DEFAULT_PANES] fills them only until an app has been picked
 * on the phone.
 */
class DuoScreenScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private companion object {
        const val TAG = "AutoBridgeDuoScreen"

        /** Placeholder until the phone-side picker exists. */
        val DEFAULT_PANES = listOf<String?>("com.miui.calculator", "com.android.settings")
    }

    // The session belongs to the process, not to this Screen: see DuoScreenHost for why leaving
    // and coming back must not relaunch every pane.
    private val controller = DuoScreenHost.controller(carContext)

    /** The layout button on the surface names the layout it switched to, as the host button did. */
    private val presetToast = object : DuoScreenController.ControlListener {
        override fun onPresetApplied(preset: DuoScreenPreset) {
            CarToast.makeText(carContext, preset.label(carContext), CarToast.LENGTH_SHORT).show()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java)
                    .setSurfaceCallback(this@DuoScreenScreen)
                controller.controlListener = presetToast
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (controller.controlListener === presetToast) controller.controlListener = null
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
                DuoScreenHost.onScreenGone()
            }
        })
    }

    /**
     * Only Exit is a host action. Layout, swap, reload and arrange are drawn by the app on the car
     * surface itself, on the seam between the panes (or as one floating button in picture in
     * picture) — see [dev.autobridge.duoscreen.layout.DuoScreenControlsGeometry]. Host buttons are
     * drawn at a size the app cannot change, and on a portrait head unit the map strip covered the
     * right-hand pane. Exit has to stay: [NavigationTemplate] refuses to build without an action
     * strip, an empty strip is invalid, and it keeps the way out apart from the buttons a driver
     * taps by the dozen.
     */
    override fun onGetTemplate(): Template {
        // Leaving the screen only detaches (DuoScreenHost.KEEP_ALIVE_MS), by design, so
        // without this there is no way to get the panes off the car display short of waiting the
        // keep-alive out: every pane app keeps running on its own display behind whatever the driver
        // looked at next. This ends the session and then hands the car host back to its own
        // launcher, which is as far as an app can take "disconnect" — only the driver can end the
        // phone's Android Auto connection itself.
        val exit = Action.Builder()
            .setIcon(icon(R.drawable.ic_duo_exit))
            .setOnClickListener {
                val ended = DuoScreenHost.release()
                StructuredLog.i(TAG, "Exit requested; session ended=$ended")
                carContext.finishCarApp()
            }
            .build()

        // Without the Shizuku grant the panes cannot take touch and go black when the phone sleeps,
        // and a toast is gone in seconds, so the reason stays on the car screen until it is granted.
        val builder = NavigationTemplate.Builder()
        if (!DuoScreenShizukuOps.isAvailable) {
            builder.setNavigationInfo(
                MessageInfo.Builder(carContext.getString(R.string.duo_screen_banner_title))
                    .setText(carContext.getString(R.string.duo_screen_banner_text))
                    .build()
            )
        }
        return builder
            .setActionStrip(ActionStrip.Builder().addAction(exit).build())
            .build()
    }

    private fun icon(@DrawableRes resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        val started = controller.start(
            surface,
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            DEFAULT_PANES
        )
        if (!started) {
            StructuredLog.e(TAG, "Could not start the Duo Screen session on the car surface")
            return
        }
        // The template shows the banner while the grant is missing; redraw it for this state.
        invalidate()
        if (!DuoScreenShizukuOps.isAvailable) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.duo_screen_needs_shizuku),
                CarToast.LENGTH_LONG
            ).show()
        }
    }

    // Hands the Surface back without ending the session: the pane displays and the apps in them
    // stay, and the next onSurfaceAvailable resumes them rather than launching them again.
    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) = controller.detach()

    override fun onClick(x: Float, y: Float) = controller.onClick(x.toInt(), y.toInt())

    override fun onScroll(distanceX: Float, distanceY: Float) =
        controller.onScroll(distanceX.toInt(), distanceY.toInt())

    override fun onFling(velocityX: Float, velocityY: Float) =
        controller.onFling(velocityX.toInt(), velocityY.toInt())

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) =
        controller.onScale(focusX.toInt(), focusY.toInt(), scaleFactor)
}
