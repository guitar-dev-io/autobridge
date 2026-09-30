package dev.autobridge.core.state

import androidx.car.app.CarContext
import dev.autobridge.safety.SafetyEnforcement
import dev.autobridge.safety.VehicleStateProvider
import dev.autobridge.safety.VehicleStateProviderFactory

/**
 * Shares one vehicle-state subscription across the dashboard and mirror screens.
 * Each screen receives a lease; the provider is stopped only after the last lease closes.
 */
object VehicleStateSession {
    private val lock = Any()
    private var provider: VehicleStateProvider? = null
    private val leases = linkedSetOf<LeaseImpl>()
    private val movingCallbacks = linkedMapOf<LeaseImpl, () -> Unit>()

    fun acquire(carContext: CarContext, onMoving: () -> Unit): Lease {
        synchronized(lock) {
            // Register the lease before start(). A simulator that is already MOVING must not be
            // able to publish its initial unsafe state before the new screen can receive it.
            val lease = LeaseImpl(onMoving)
            leases += lease
            movingCallbacks[lease] = onMoving
            if (provider == null) {
                val created = VehicleStateProviderFactory.create(carContext) { dispatchMoving() }
                provider = created
                runCatching { created.start() }.onFailure { error ->
                    leases.remove(lease)
                    movingCallbacks.remove(lease)
                    provider = null
                    throw error
                }
            }
            return lease
        }
    }

    interface Lease {
        val provider: VehicleStateProvider
        fun requestPermission(onResult: (Boolean) -> Unit)
        fun close()
    }

    private class LeaseImpl(private val onMoving: () -> Unit) : Lease {
        private var closed = false

        override val provider: VehicleStateProvider
            get() = synchronized(lock) {
                check(!closed) { "Vehicle state lease is closed" }
                VehicleStateSession.provider ?: error("Vehicle state provider is unavailable")
            }

        override fun requestPermission(onResult: (Boolean) -> Unit) {
            val current = synchronized(lock) {
                if (closed) null else VehicleStateSession.provider
            }
            if (current == null) {
                onResult(false)
            } else {
                current.requestPermission(onResult)
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                leases.remove(this)
                movingCallbacks.remove(this)
                if (leases.isEmpty()) {
                    VehicleStateSession.provider?.stop()
                    VehicleStateSession.provider = null
                }
            }
        }
    }

    /**
     * The screens' motion callbacks stop the projection and finish the car app, which is an action
     * on the reading rather than a display of it. They fire only while the gate is enforcing
     * ([SafetyEnforcement]); with it reporting, the provider still publishes MOVING to
     * [dev.autobridge.safety.ParkingStateStore] so every status row updates, and nothing is torn
     * down underneath the driver.
     */
    private fun dispatchMoving() {
        if (!SafetyEnforcement.isBlocking) return
        val callbacks = synchronized(lock) { movingCallbacks.values.toList() }
        callbacks.forEach { callback -> runCatching { callback() } }
    }
}
