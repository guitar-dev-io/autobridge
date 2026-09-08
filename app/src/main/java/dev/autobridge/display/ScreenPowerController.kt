package dev.autobridge.display

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import dev.autobridge.settings.AutoDimDelay

/**
 * Best-effort screen power policy for a consented mirror session.
 *
 * This intentionally uses public WakeLock APIs. It can keep the phone panel awake and can let the
 * panel dim after an idle delay, but it cannot turn a third-party window off or provide the
 * privileged panel-off behavior implemented by some other products.
 */
object ScreenPowerController {
    private const val TAG = "AutoBridgeScreenPower"
    private const val WAKE_LOCK_TAG = "AutoBridge:MirrorScreen"

    private val handler = Handler(Looper.getMainLooper())
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dimApplied = false
    private var preventScreenSleep = false
    private var autoDimDelay = AutoDimDelay.OFF

    private val dimRunnable = Runnable { acquire(dim = true) }

    @Synchronized
    fun start(context: Context, preventScreenSleep: Boolean, autoDimDelay: AutoDimDelay) {
        stop()
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
        if (powerManager == null || (!preventScreenSleep && autoDimDelay == AutoDimDelay.OFF)) return
        acquire(dim = false)
        scheduleDim()
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(dimRunnable)
        release()
        powerManager = null
        dimApplied = false
        preventScreenSleep = false
        autoDimDelay = AutoDimDelay.OFF
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
        release()
        val next = manager.newWakeLock(level, WAKE_LOCK_TAG)
        next.setReferenceCounted(false)
        next.acquire()
        wakeLock = next
        dimApplied = dim
        Log.i(TAG, "Screen power policy applied: ${if (dim) "dim" else "awake"}")
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
