package dev.autobridge.display

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.logging.StructuredLog
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.settings.AutoDimDelay
import dev.autobridge.settings.MirrorSettings

/**
 * Best-effort screen power policy for a consented mirror session.
 *
 * The normal path uses public WakeLock APIs. When the user explicitly enables the ScreenOnAuto-
 * style option and the Shizuku user service proves the hidden display-power backend, auto-dim
 * turns only the physical panel off instead of locking/sleeping the device. Every stop path
 * restores the panel before releasing the service, and failure falls back to public dimming.
 */
object ScreenPowerController {
    private const val TAG = "AutoBridgeScreenPower"
    private const val WAKE_LOCK_TAG = "AutoBridge:MirrorScreen"

    private val handler = Handler(Looper.getMainLooper())
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dimApplied = false
    private val panel = PanelPowerLease(
        keepAwake = { acquire(dim = true) },
        turnOff = { ShizukuInputBackend.setPanelPower(on = false) },
        turnOn = { ShizukuInputBackend.restorePanelPower() }
    )
    private var preventScreenSleep = false
    private var autoDimDelay = AutoDimDelay.OFF

    private val dimRunnable = Runnable { applyIdlePolicy() }

    @Synchronized
    fun start(context: Context, preventScreenSleep: Boolean, autoDimDelay: AutoDimDelay) {
        stop()
        if (panel.restoreFailed) return
        powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
        this.preventScreenSleep = preventScreenSleep
        this.autoDimDelay = autoDimDelay

        if (!preventScreenSleep && autoDimDelay == AutoDimDelay.OFF) {
            StructuredLog.w(
                TAG,
                "Phone display wake lock disabled; AUTO_MIRROR may turn black after screen timeout"
            )
            return
        }
        acquire(dim = false)
        scheduleDim()
    }

    /** Treat a car-side input event as activity and restart the auto-dim countdown. */
    @Synchronized
    fun userActivity() {
        if (panel.isOff) {
            // Do not wake the phone panel for every car-side gesture. Stop/disconnect restores it.
            return
        }
        if (powerManager == null) return
        if (!preventScreenSleep && autoDimDelay == AutoDimDelay.OFF && !dimApplied) return
        if (preventScreenSleep || autoDimDelay != AutoDimDelay.OFF) acquire(dim = false) else release()
        scheduleDim()
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(dimRunnable)
        // Restore independently of FeaturePolicy: MOVING/UNKNOWN teardown must never leave the
        // physical panel off, even though normal input calls are then denied.
        if (!panel.restore()) {
            StructuredLog.w(TAG, "Panel restore failed; reconnect Shizuku and use Restore phone screen")
        }
        release()
        powerManager = null
        dimApplied = false
        preventScreenSleep = false
        autoDimDelay = AutoDimDelay.OFF
    }

    @Synchronized
    fun statusLabel(): String = when {
        panel.restoreFailed -> "Screen restore failed; reconnect Shizuku and retry"
        panel.isOff -> "Phone panel off (requested)"
        powerManager == null -> "Inactive"
        dimApplied -> "Phone dimmed (requested)"
        wakeLock?.isHeld == true -> "Phone kept awake"
        else -> "Phone follows system timeout"
    }

    /** Manual idle action is available only for an active, permitted mirror. */
    @Synchronized
    fun dimNow(): Boolean {
        if (powerManager == null || !MirrorCoordinator.isMirroring ||
            !FeaturePolicy.app.isAvailable(Feature.SCREEN_OFF)) return false
        handler.removeCallbacks(dimRunnable)
        applyIdlePolicy()
        return panel.isOff || dimApplied
    }

    /** Restoration is always permitted, including after speed loss or failed teardown. */
    @Synchronized
    fun restorePhoneScreen(): Boolean {
        handler.removeCallbacks(dimRunnable)
        if (!panel.restore()) return false
        if (powerManager != null) {
            if (preventScreenSleep || autoDimDelay != AutoDimDelay.OFF) acquire(dim = false) else release()
            scheduleDim()
        }
        return true
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        if (autoDimDelay != AutoDimDelay.OFF) {
            handler.postDelayed(dimRunnable, autoDimDelay.seconds * 1_000L)
        }
    }

    @Suppress("DEPRECATION", "Wakelock", "WakelockTimeout")
    @Synchronized
    private fun acquire(dim: Boolean) {
        val manager = powerManager ?: return
        val level = if (dim) PowerManager.SCREEN_DIM_WAKE_LOCK else PowerManager.SCREEN_BRIGHT_WAKE_LOCK
        val current = wakeLock
        if (current?.isHeld == true && dimApplied == dim) return
        val next = manager.newWakeLock(level, WAKE_LOCK_TAG)
        next.setReferenceCounted(false)
        next.acquire()
        // Avoid a moment with no lock while changing brightness levels.
        if (current?.isHeld == true) runCatching { current.release() }
        wakeLock = next
        dimApplied = dim
        Log.i(TAG, "Screen power policy applied: ${if (dim) "dim" else "awake"}")
    }

    @Synchronized
    private fun applyIdlePolicy() {
        if (powerManager == null || panel.isOff) return
        if (!MirrorCoordinator.isMirroring || !FeaturePolicy.app.isAvailable(Feature.SCREEN_OFF)) {
            scheduleDim()
            return
        }
        if (MirrorSettings.screenOffOnAutoDim) {
            if (panel.hide()) {
                StructuredLog.i(TAG, "Privileged panel-only screen-off applied after auto-dim")
                return
            }
            StructuredLog.w(
                TAG,
                "Privileged panel-off unavailable; falling back to public dimming"
            )
        }
        acquire(dim = true)
    }

    @Suppress("DEPRECATION")
    private fun release() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
        dimApplied = false
    }
}
