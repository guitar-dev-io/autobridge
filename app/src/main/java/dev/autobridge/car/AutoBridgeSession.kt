package dev.autobridge.car

import android.content.Intent
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.browser.CarBrowserRuntime
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.remote.CarScreenController
import dev.autobridge.remote.RemoteRuntime

class AutoBridgeSession : Session(), CarScreenController.Host {
    private companion object {
        const val TAG = "AutoBridgeCarSession"
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                // Register this session so the Mobile Remote's command router can drive the car app
                // (navigation) through the SAME ScreenManager the car UI already uses.
                RemoteRuntime.ensureStarted(carContext)
                CarScreenController.register(this@AutoBridgeSession)
                // A live Session IS the Android Auto connection; nothing else observes the host
                // handshake. Without this the runtime context stayed false for the whole drive, so
                // the phone UI read "Not connected" and the Mobile Remote showed the car offline
                // while the head unit was plainly running our screens.
                CarConnectionMonitor.publish()
                // Odometer and fuel level for the fuel log, when the car reports them and the
                // driver has allowed it (CarFuelScreen asks); nothing happens otherwise.
                dev.autobridge.fuel.CarVehicleData.start(carContext)
                // What maintenance is due, as a phone notification: the drive is about to start.
                runCatching { dev.autobridge.maintenance.MaintenanceReminder.check(carContext) }
                dev.autobridge.logging.StructuredLog.i(TAG, "car session connected")
                // The bridge learns about the connection here rather than polling for it, and
                // this is where a Send-to-Car that arrived while nothing was plugged in gets
                // replayed onto the head unit.
                dev.autobridge.bridge.AutoBridgeSessionManager.onCarConnected(carContext)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                dev.autobridge.fuel.CarVehicleData.stop()
                CarScreenController.unregister(this@AutoBridgeSession)
                // A replacement session can be created before the outgoing one is destroyed, so the
                // registered host - not this callback - decides whether anything is still attached.
                // The host's own connection signal still counts after this session is gone: the car
                // stays connected while the driver is in Bridge Web, Duo Screen or Maps.
                CarConnectionMonitor.publish()
                val stillConnected = RuntimeContextStore.context.value.connected
                dev.autobridge.logging.StructuredLog.i(TAG, "car session destroyed connected=$stillConnected")
                dev.autobridge.bridge.AutoBridgeSessionManager.onCarDisconnected(carContext)
                // The browser renderer outlives individual screens on purpose, so the session is
                // the only correct place to tear its WebView down.
                CarBrowserRuntime.release()
                // The stack goes away with the session; its marker bookkeeping must go with it.
                CarNavigation.reset()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i(TAG, "onCreateScreen action=${intent.action} data=${intent.data}")
        dev.autobridge.logging.StructuredLog.i(TAG, "onCreateScreen action=${intent.action}")
        // The combined home is content-bearing on connect (clock, now-playing, vehicle state) and
        // links to the grid launcher, media library and web surfaces, so the head unit is never a
        // blank screen. Back from any child returns here.
        return try {
            CarHomeDashboardScreen(carContext)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to create CarHomeDashboardScreen", error)
            dev.autobridge.logging.StructuredLog.e(TAG, "CarHomeDashboardScreen failed: ${error.message}")
            throw error
        }
    }

    // --- CarScreenController.Host: navigation performed via the existing ScreenManager ---

    private val screens: ScreenManager get() = carContext.getCarService(ScreenManager::class.java)

    override fun pushBrowser(): CarScreenController.BrowserTarget {
        val browser = CarBrowserScreen(carContext)
        carContext.mainExecutor.execute {
            CarNavigation.open(screens, "CarBrowserScreen") { browser }
        }
        return browser
    }

    override fun pushMirror() {
        carContext.mainExecutor.execute { screens.push(MirrorCarScreen(carContext)) }
    }

    override fun pushMedia() {
        carContext.mainExecutor.execute { screens.push(CarMediaCenterScreen(carContext)) }
    }

    override fun pushVideo(url: String, title: String) {
        carContext.mainExecutor.execute { CarVideoLauncher.open(screens, carContext, url, title) }
    }

    override fun pushBridgePlayer() {
        carContext.mainExecutor.execute {
            // Through CarNavigation like every other screen, so re-sending while the player is
            // already up returns to it instead of stacking a second copy whose surface callback
            // would fight the first one's.
            CarNavigation.open(screens, "CarBridgePlayerScreen") {
                dev.autobridge.bridge.CarBridgePlayerScreen(carContext)
            }
        }
    }

    override fun pushAgent() {
        carContext.mainExecutor.execute { screens.push(CarAgentScreen(carContext)) }
    }

    override fun popToHome() {
        carContext.mainExecutor.execute { screens.popToRoot() }
    }

    override fun pushSettings() {
        carContext.mainExecutor.execute { screens.push(CarSettingsScreen(carContext) {}) }
    }

    override fun showFeedback(message: String) {
        carContext.mainExecutor.execute {
            CarToast.makeText(carContext, message, CarToast.LENGTH_SHORT).show()
        }
    }
}
