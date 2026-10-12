package dev.autobridge.display

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.edit
import dev.autobridge.display.PerformancePolicy.Level
import dev.autobridge.logging.StructuredLog
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.settings.MirrorSettings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs [PerformancePolicy] while the phone works for the car, and publishes the current [level].
 *
 * Active only while something holds it (a car session, a mirror projection): it listens to the
 * system thermal status and, every [SAMPLE_MS], reads the self-drawn mirror's frame counters. When
 * the level changes it is logged (`PERF`) and listeners are told, so the video player can lower its
 * resolution; the self-drawn mirror and the car browser read [level] on every frame.
 *
 * On by default and switchable ([setEnabled]); switched off, or with nothing holding it, the level
 * is always [Level.NORMAL], which caps nothing. The direct AUTO_MIRROR path is the OS's own
 * zero-copy display and has no frame rate an app can change once created, so it is not throttled
 * here; the browser and video still are.
 */
object PerformanceMode {
    private const val TAG = "PERF"
    private const val PREFS = "autobridge_performance"
    private const val KEY_ENABLED = "auto"
    private const val SAMPLE_MS = 2_000L

    private val handler = Handler(Looper.getMainLooper())
    private val policy = PerformancePolicy()
    private val listeners = CopyOnWriteArrayList<(Level) -> Unit>()
    private val holders = mutableSetOf<String>()

    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var thermalStatus = 0
    private var lastCaptured = 0L
    private var lastRendered = 0L
    private var lastSampleMs = 0L

    @Volatile
    var enabled: Boolean = true
        private set

    /** What consumers apply right now. */
    @Volatile
    var level: Level = Level.NORMAL
        private set

    val reason: String
        get() = if (level == Level.NORMAL) "normal" else policy.reason

    fun restore(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, on) }
        StructuredLog.i(TAG, "auto performance enabled=$on")
        if (!on) publish(Level.NORMAL, "off")
    }

    fun addListener(listener: (Level) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Level) -> Unit) {
        listeners -= listener
    }

    /** Starts watching on the first holder. Main thread. */
    fun acquire(context: Context, holder: String) {
        if (!holders.add(holder) || holders.size > 1) return
        val pm = context.applicationContext.getSystemService(PowerManager::class.java) ?: return
        powerManager = pm
        thermalStatus = pm.currentThermalStatus
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            thermalStatus = status
            sample()
        }.also { runCatching { pm.addThermalStatusListener(it) } }
        resetFrameWindow()
        policy.reset()
        handler.postDelayed(tick, SAMPLE_MS)
        StructuredLog.i(TAG, "watching (thermal $thermalStatus) for $holder")
        sample()
    }

    /** Stops on the last holder and puts everything back to [Level.NORMAL]. Main thread. */
    fun release(holder: String) {
        if (!holders.remove(holder) || holders.isNotEmpty()) return
        handler.removeCallbacks(tick)
        thermalListener?.let { listener -> runCatching { powerManager?.removeThermalStatusListener(listener) } }
        thermalListener = null
        powerManager = null
        policy.reset()
        publish(Level.NORMAL, "idle")
        StructuredLog.i(TAG, "stopped")
    }

    private val tick = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, SAMPLE_MS)
        }
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val struggling = mirrorStruggling(now)
        if (!enabled) return
        if (policy.onSample(PerformancePolicy.Sample(now, thermalStatus, struggling))) {
            publish(policy.level, policy.reason)
        }
    }

    /** Only the self-drawn mirror has app-visible frames; anything else reads as keeping up. */
    private fun mirrorStruggling(now: Long): Boolean {
        val stats = MirrorDiagnostics.frameStats()
        val elapsed = now - lastSampleMs
        val captured = stats.captured - lastCaptured
        val rendered = stats.rendered - lastRendered
        lastCaptured = stats.captured
        lastRendered = stats.rendered
        lastSampleMs = now
        val selfDrawn = MirrorCoordinator.activePipelineMode == ScreenOffController.PipelineMode.SELF_DRAWN &&
            MirrorCoordinator.isMirroring
        if (!selfDrawn || captured < 0 || rendered < 0) return false
        return PerformancePolicy.mirrorStruggling(captured, rendered, elapsed, effectiveMirrorFps(configuredMirrorFps()))
    }

    private fun resetFrameWindow() {
        val stats = MirrorDiagnostics.frameStats()
        lastCaptured = stats.captured
        lastRendered = stats.rendered
        lastSampleMs = SystemClock.elapsedRealtime()
    }

    private fun publish(next: Level, why: String) {
        if (next == level) return
        val previous = level
        level = next
        StructuredLog.i(TAG, "level $previous -> $next ($why)")
        listeners.forEach { runCatching { it(next) } }
    }

    /** The self-drawn mirror's rate before any cap: per-app setting, then the car profile, then 60. */
    fun configuredMirrorFps(): Int =
        MirrorSettings.preferredFps?.coerceIn(1, 60)
            ?: SurfaceProfile.active.preferredFps?.coerceIn(1, 60)
            ?: 60

    /** The self-drawn mirror's target after the cap: never above what was configured. */
    fun effectiveMirrorFps(configured: Int): Int =
        level.mirrorFpsCap?.let { minOf(configured, it) } ?: configured
}
