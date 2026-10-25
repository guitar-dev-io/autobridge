package dev.autobridge.display

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/**
 * How hard the phone is working, for the car home's status line and the car diagnostics.
 *
 * What an app may read on current Android decides what this shows:
 *  - **CPU** is this app's own load: its CPU time over the wall time, as a share of all cores. The
 *    whole-device figure (`/proc/stat`, load average) is blocked for apps, so it is not offered
 *    under the name "CPU" for the whole phone.
 *  - **RAM** is the system's: used share of total, from the memory info an app may ask for.
 *  - **Temperature** is the battery's, the one temperature an app can read without a special
 *    permission, next to the system's own thermal status (which is what throttles the phone).
 */
data class SystemReading(
    /** This app's CPU use, 0..100 % of all cores; null until two samples exist to compare. */
    val cpuPercent: Int?,
    val ramPercent: Int,
    val ramUsedMb: Int,
    val ramTotalMb: Int,
    /** Battery temperature in °C, or null when the device does not report one. */
    val tempC: Float?,
    /** [PowerManager] thermal status, 0 (none) to 6 (shutdown). */
    val thermalStatus: Int
) {
    val heat: SystemStats.Heat get() = SystemStats.heat(tempC, thermalStatus)
}

object SystemStats {
    enum class Heat { OK, WARM, HOT }

    /** How soon a new sample is worth taking; calls inside it return the last reading. */
    const val MIN_INTERVAL_MS = 1_500L

    /** The app's CPU use as a share of all [cores], 0..100; null when no time has passed. */
    fun cpuPercent(cpuMsDelta: Long, wallMsDelta: Long, cores: Int): Int? {
        if (wallMsDelta <= 0L || cores <= 0 || cpuMsDelta < 0L) return null
        return (cpuMsDelta * 100f / (wallMsDelta * cores)).toInt().coerceIn(0, 100)
    }

    /** The used share of memory, 0..100. */
    fun ramPercent(totalBytes: Long, availBytes: Long): Int {
        if (totalBytes <= 0L) return 0
        return ((totalBytes - availBytes.coerceIn(0L, totalBytes)) * 100f / totalBytes).toInt().coerceIn(0, 100)
    }

    /**
     * How worried to look. The system's thermal status wins (it is what slows the phone down);
     * without it, a battery above 40 °C is warm and above 45 °C hot.
     */
    fun heat(tempC: Float?, thermalStatus: Int): Heat = when {
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> Heat.HOT
        thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> Heat.WARM
        tempC != null && tempC >= HOT_C -> Heat.HOT
        tempC != null && tempC >= WARM_C -> Heat.WARM
        else -> Heat.OK
    }

    private const val WARM_C = 40f
    private const val HOT_C = 45f

    private var lastCpuMs = -1L
    private var lastWallMs = -1L
    private var last: SystemReading? = null
    private var lastAt = 0L

    /** A reading now, or the previous one when it is younger than [MIN_INTERVAL_MS]. */
    @Synchronized
    fun read(context: Context): SystemReading {
        val now = SystemClock.elapsedRealtime()
        last?.let { if (now - lastAt < MIN_INTERVAL_MS) return it }

        val cpuMs = Process.getElapsedCpuTime()
        val cpu = if (lastCpuMs < 0L) null else {
            cpuPercent(cpuMs - lastCpuMs, now - lastWallMs, Runtime.getRuntime().availableProcessors())
        }
        lastCpuMs = cpuMs
        lastWallMs = now

        val app = context.applicationContext
        val info = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)

        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val thermal = runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
        }.getOrNull() ?: 0

        val reading = SystemReading(
            cpuPercent = cpu,
            ramPercent = ramPercent(info.totalMem, info.availMem),
            ramUsedMb = ((info.totalMem - info.availMem) / BYTES_PER_MB).toInt(),
            ramTotalMb = (info.totalMem / BYTES_PER_MB).toInt(),
            tempC = if (tenths == Int.MIN_VALUE || tenths <= 0) null else tenths / 10f,
            thermalStatus = thermal
        )
        last = reading
        lastAt = now
        return reading
    }

    private const val BYTES_PER_MB = 1024L * 1024L
}
