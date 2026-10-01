package dev.autobridge.ui

import dev.autobridge.core.model.RuntimeContext
import dev.autobridge.core.model.VehicleState

/**
 * The user-facing wording for the Android Auto connection, derived from the single source of
 * truth ([dev.autobridge.core.state.RuntimeContextStore]). Home, Control and Car & Connection all
 * render this, so the three can never disagree.
 *
 * Internal values (environment such as REAL_CAR/DHU, build mode) are deliberately absent; they are
 * only shown on Settings > Advanced > Debug.
 */
object ConnectionStatusText {

    data class Status(val connected: Boolean, val summary: String)

    fun of(runtime: RuntimeContext): Status = of(runtime.connected, runtime.vehicleState)

    fun of(connected: Boolean, vehicleState: VehicleState): Status {
        if (!connected) return Status(false, "Not connected")
        val drive = when (vehicleState) {
            VehicleState.PARKED -> "Parked"
            VehicleState.MOVING -> "Driving"
            VehicleState.UNKNOWN -> "Checking vehicle state"
        }
        return Status(true, "Connected · $drive")
    }
}
