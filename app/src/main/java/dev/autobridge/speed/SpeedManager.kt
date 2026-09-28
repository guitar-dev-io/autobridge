package dev.autobridge.speed

import android.content.Context
import android.os.SystemClock
import androidx.car.app.CarContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multi-source vehicle speed with automatic best-value selection for DISPLAY purposes.
 *
 * Sources are consulted in priority order: CAR (Android Auto Car Hardware) → GPS → OBD. The most
 * recent valid sample from the highest-priority source within [FRESHNESS_MS] wins. If nothing is
 * fresh/valid, the published sample is [SpeedSample.NONE].
 *
 * This is intentionally decoupled from the safety stack ([dev.autobridge.safety]): it only feeds UI
 * (Home header + Mirror/Browser widget) and never gates features or mutates ParkingStateStore.
 *
 * Process-scoped singleton with reference counting so multiple screens can observe one subscription.
 */
object SpeedManager {
    private const val FRESHNESS_MS = 5_000L

    private val lock = Any()
    private val latestByOrigin = linkedMapOf<SpeedOrigin, SpeedSample>()
    private var sources: List<SpeedSource> = emptyList()
    private var refCount = 0

    private val _speed = MutableStateFlow(SpeedSample.NONE)
    val speed: StateFlow<SpeedSample> = _speed.asStateFlow()

    /**
     * Starts the sources (once) and returns a handle. Call [Subscription.close] when the observer
     * goes away; sources stop after the last subscription closes.
     */
    fun acquire(carContext: CarContext): Subscription {
        synchronized(lock) {
            if (refCount == 0) {
                sources = buildSources(carContext)
                sources.forEach { source ->
                    if (source.isAvailable()) {
                        source.start { sample -> onSample(sample) }
                    }
                }
            }
            refCount++
        }
        return SubscriptionImpl()
    }

    /** Sources currently available (for a settings/diagnostics readout). */
    fun availableOrigins(carContext: CarContext): List<SpeedOrigin> =
        buildSources(carContext).filter { it.isAvailable() }.map { it.origin }

    private fun buildSources(carContext: CarContext): List<SpeedSource> = listOf(
        CarSpeedSource(carContext),
        GpsSpeedSource(carContext as Context),
        ObdSpeedSource()
    )

    private fun onSample(sample: SpeedSample) {
        synchronized(lock) {
            if (sample.valid) {
                latestByOrigin[sample.origin] = sample
            } else {
                // Drop a stale invalid reading for this origin so it stops winning selection.
                latestByOrigin.remove(sample.origin)
            }
            _speed.value = selectBest()
        }
    }

    private fun selectBest(): SpeedSample {
        val now = SystemClock.elapsedRealtime()
        return latestByOrigin.values
            .filter { it.valid && now - it.timestampMs <= FRESHNESS_MS }
            .minByOrNull { it.origin.priority }
            ?: SpeedSample.NONE
    }

    private fun stopIfIdleLocked() {
        if (refCount == 0) {
            sources.forEach { runCatching { it.stop() } }
            sources = emptyList()
            latestByOrigin.clear()
            _speed.value = SpeedSample.NONE
        }
    }

    interface Subscription {
        fun close()
    }

    private class SubscriptionImpl : Subscription {
        private var closed = false
        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                refCount = (refCount - 1).coerceAtLeast(0)
                stopIfIdleLocked()
            }
        }
    }
}
