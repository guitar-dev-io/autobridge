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
            }

            override fun onDestroy(owner: LifecycleOwner) {
                CarScreenController.unregister(this@AutoBridgeSession)
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
        dev.autobridge.display.StructuredLog.i(TAG, "onCreateScreen action=${intent.action}")
        // The combined home is content-bearing on connect (clock, now-playing, vehicle state) and
        // links to the grid launcher, media library and web surfaces, so the head unit is never a
        // blank screen. Back from any child returns here.
        return try {
            CarHomeDashboardScreen(carContext)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to create CarHomeDashboardScreen", error)
            dev.autobridge.display.StructuredLog.e(TAG, "CarHomeDashboardScreen failed: ${error.message}")
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
