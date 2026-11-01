package dev.autobridge.car

import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.display.CarSurfaceCanvas
import dev.autobridge.logging.StructuredLog
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.obd.Gauge
import dev.autobridge.obd.GaugeFormat
import dev.autobridge.obd.GaugeLabels
import dev.autobridge.obd.GaugeSpec
import dev.autobridge.obd.ObdConnection
import dev.autobridge.obd.ObdPermissions
import dev.autobridge.obd.ObdSettings
import dev.autobridge.obd.ObdState
import dev.autobridge.obd.Zone

/**
 * The car's live gauges: engine and drive readings from the OBD2 adapter, drawn on the template
 * surface. Tapping the surface (or the page button) turns the page.
 *
 * These are read-only numbers a driver glances at, like the speedometer, so they are not parked-only
 * and the screen does not take a vehicle-state lease: moving does not close it. The adapter is
 * chosen and the Bluetooth permission granted on the phone; this screen only starts the connection
 * and shows what it finds, or says what is missing.
 */
class CarGaugesScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private val appManager = carContext.getCarService(AppManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val renderer = GaugeCarRenderer { gauge -> carContext.getString(GaugeLabels.of(gauge)) }

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var page = 0
    private var active = false

    private val frame = object : Runnable {
        override fun run() {
            if (!active) return
            draw()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                active = true
                MirrorSurfaceOwnership.claim(this@CarGaugesScreen)
                appManager.setSurfaceCallback(this@CarGaugesScreen)
                startConnection()
                handler.post(frame)
            }

            override fun onStop(owner: LifecycleOwner) {
                active = false
                handler.removeCallbacksAndMessages(null)
                releaseSurface()
                if (MirrorSurfaceOwnership.release(this@CarGaugesScreen)) appManager.setSurfaceCallback(null)
            }
        })
    }

    override fun onGetTemplate(): Template =
        // A NavigationTemplate needs a non-empty action strip, with icon-only actions: no titles,
        // and never Action.APP_ICON (see CarBrowserScreen for what that does to the host).
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, R.drawable.ic_car_home))
                            .setOnClickListener { screenManager.popToRoot() }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.CONTROLS))
                            .setOnClickListener { nextPage() }
                            .build()
                    )
                    .build()
            )
            .build()

    private fun nextPage() {
        page = (page + 1) % ObdSettings.pages(carContext).size
        draw()
    }

    private fun startConnection() {
        ObdSettings.applyProfile(carContext)
        if (ObdConnection.isRunning) return
        val adapter = ObdSettings.adapter(carContext) ?: return
        if (ObdPermissions.missing(carContext).isNotEmpty()) return
        ObdConnection.start(carContext, adapter, ObdSettings.transmission(carContext))
    }

    // --- Surface ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        val next = surfaceContainer.surface ?: return
        if (next !== surface) releaseSurface()
        surface = next
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        StructuredLog.i(TAG, "surface ${surfaceWidth}x$surfaceHeight dpi=$surfaceDpi")
        draw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        releaseSurface()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        this.stableArea = Rect(stableArea)
        draw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        this.visibleArea = Rect(visibleArea)
        draw()
    }

    override fun onClick(x: Float, y: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        nextPage()
    }

    private fun releaseSurface() {
        surface?.let { runCatching { it.release() } }
        surface = null
    }

    /** The host's stable area, else its visible area, else the whole surface. */
    private fun safeArea(): RectF {
        val full = Rect(0, 0, surfaceWidth, surfaceHeight)
        val inset = listOfNotNull(stableArea, visibleArea)
            .firstOrNull { !it.isEmpty && full.contains(it) && it.width() > surfaceWidth / 2 && it.height() > surfaceHeight / 2 }
            ?: full
        return RectF(inset)
    }

    // --- Drawing ---

    private fun draw() {
        val target = surface ?: return
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || !target.isValid) return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val density = (if (surfaceDpi > 0) surfaceDpi else BASELINE_DPI) / BASELINE_DPI.toFloat()
        val now = SystemClock.elapsedRealtime()
        val telemetry = ObdConnection.telemetry.value
        val imperial = ObdSettings.imperial(carContext)
        val state = ObdConnection.state.value
        // Read each frame: the pages are arranged on the phone while the car is showing them.
        val pages = ObdSettings.pages(carContext)
        val index = page.coerceIn(0, pages.size - 1)
        val shown = GaugeCarRenderer.Frame(
            telemetry = telemetry,
            nowMs = now,
            imperial = imperial,
            page = pages[index],
            pageIndex = index,
            pageCount = pages.size,
            pageTitle = carContext.getString(R.string.car_gauges_page_n, (index + 1).toString(), pages.size.toString()),
            status = statusText(state),
            notice = notice(),
            alert = worstAlert(telemetry, now, imperial, pages),
            density = density,
        )
        CarSurfaceCanvas.draw(carContext, target) { canvas ->
            renderer.draw(canvas, surfaceWidth, surfaceHeight, safeArea(), shown)
        }
    }

    /** What stops the gauges from working, in words the driver can act on; null when nothing does. */
    private fun notice(): String? = when {
        ObdSettings.adapter(carContext) == null -> carContext.getString(R.string.car_gauges_pick_on_phone)
        ObdPermissions.missing(carContext).isNotEmpty() -> carContext.getString(R.string.car_gauges_allow_bluetooth)
        else -> null
    }

    private fun statusText(state: ObdState): String = when (state.status) {
        ObdState.Status.IDLE -> carContext.getString(R.string.obd_status_idle)
        ObdState.Status.CONNECTING -> carContext.getString(R.string.obd_status_connecting)
        ObdState.Status.INITIALIZING -> carContext.getString(R.string.obd_status_init)
        ObdState.Status.LIVE -> carContext.getString(R.string.obd_status_live)
        ObdState.Status.RETRYING -> carContext.getString(R.string.obd_status_retry, state.message.orEmpty())
    }

    /** The first reading in the danger zone, written like "Coolant 108 °C", or null. */
    private fun worstAlert(
        telemetry: dev.autobridge.obd.Telemetry,
        now: Long,
        imperial: Boolean,
        pages: List<dev.autobridge.obd.GaugePage>,
    ): String? {
        // Only what some page shows, so a warning is never about a gauge the driver cannot see. Speed
        // is left out: a speed limit is the driver's business, not an engine warning.
        val gauges: List<Gauge> = pages.flatMap { it.gauges }.distinct().filter { it != Gauge.SPEED }
        for (gauge in gauges) {
            val value = telemetry.value(gauge, now) ?: continue
            if (GaugeSpec.of(gauge).zone(value) != Zone.DANGER) continue
            val name = carContext.getString(GaugeLabels.of(gauge))
            return "$name ${GaugeFormat.text(gauge, value, imperial)} ${GaugeFormat.unit(gauge, imperial)}"
        }
        return null
    }

    private companion object {
        const val TAG = "CarGaugesScreen"
        const val FRAME_MS = 200L
        const val BASELINE_DPI = 160
    }
}
