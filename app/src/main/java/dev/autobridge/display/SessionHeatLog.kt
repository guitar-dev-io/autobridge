package dev.autobridge.display

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.autobridge.logging.StructuredLog

/**
 * How hot the phone gets, and how fast its battery goes, while a car session runs.
 *
 * Every minute of a session it writes the battery temperature, level, whether it is charging and
 * the system's own thermal status to the log, and a summary when the session ends, so Send log
 * shows whether a long drive overheats the phone. It only reads the sticky battery broadcast and
 * the thermal status, which need no permission, and does nothing between sessions.
 */
object SessionHeatLog {
    private const val TAG = "POWER"
    private const val INTERVAL_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var tracker: HeatTracker? = null
    private var startedAtMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            sample(periodic = true)
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    @Synchronized
    fun start(context: Context) {
        stop()
        appContext = context.applicationContext
        tracker = HeatTracker()
        startedAtMs = SystemClock.elapsedRealtime()
        sample(periodic = false)
        handler.postDelayed(tick, INTERVAL_MS)
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(tick)
        val finished = tracker ?: return
        sample(periodic = false)
        StructuredLog.i(TAG, finished.summary(SystemClock.elapsedRealtime() - startedAtMs))
        tracker = null
        appContext = null
    }

    @Synchronized
    private fun sample(periodic: Boolean) {
        val context = appContext ?: return
        val active = tracker ?: return
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val temperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (temperature == Int.MIN_VALUE || level < 0) return
        val percent = if (scale > 0) level * 100 / scale else level
        val thermal = context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
        active.record(temperature / 10.0, percent)
        if (periodic) {
            StructuredLog.i(
                TAG,
                active.line(
                    minutes = (SystemClock.elapsedRealtime() - startedAtMs) / 60_000L,
                    thermalStatus = thermal,
                    charging = charging
                )
            )
        }
    }
}

/** The numbers behind [SessionHeatLog]; no Android types, so it is unit-tested. */
class HeatTracker {
    private var firstTemp: Double? = null
    private var lastTemp = 0.0
    private var peakTemp = Double.NEGATIVE_INFINITY
    private var firstLevel = -1
    private var lastLevel = -1

    fun record(tempC: Double, levelPercent: Int) {
        if (firstTemp == null) {
            firstTemp = tempC
            firstLevel = levelPercent
        }
        lastTemp = tempC
        lastLevel = levelPercent
        if (tempC > peakTemp) peakTemp = tempC
    }

    fun line(minutes: Long, thermalStatus: Int, charging: Boolean): String =
        "min $minutes: battery ${format(lastTemp)} C, $lastLevel percent, " +
            (if (charging) "charging" else "on battery") + ", thermal ${thermalName(thermalStatus)}"

    fun summary(elapsedMs: Long): String {
        val start = firstTemp ?: return "session ${elapsedMs / 60_000L} min: no battery reading"
        return "session ${elapsedMs / 60_000L} min: battery ${format(start)} C to ${format(lastTemp)} C, " +
            "peak ${format(peakTemp)} C, level $firstLevel to $lastLevel percent"
    }

    private fun format(value: Double) = String.format(java.util.Locale.US, "%.1f", value)

    companion object {
        /** The names of `PowerManager.THERMAL_STATUS_*`, 0 to 6. */
        fun thermalName(status: Int): String = when (status) {
            0 -> "none"
            1 -> "light"
            2 -> "moderate"
            3 -> "severe"
            4 -> "critical"
            5 -> "emergency"
            6 -> "shutdown"
            else -> "unknown"
        }
    }
}
