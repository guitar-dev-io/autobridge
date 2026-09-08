package dev.autobridge.safety

import dev.autobridge.core.model.VehicleState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Compatibility bridge for existing Android Auto integrations. New feature decisions should use
 * RuntimeContextStore/FeaturePolicy; this store keeps old low-level callbacks source-compatible.
 */
object ParkingStateStore {
    enum class State { UNKNOWN, PARKED, MOVING }

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile
    var vehicleState: VehicleState = VehicleState.UNKNOWN
        private set

    @Volatile
    var state: State = State.UNKNOWN
        private set

    val isParked: Boolean
        get() = vehicleState == VehicleState.PARKED

    fun update(newState: State) {
        update(
            when (newState) {
                State.PARKED -> VehicleState.PARKED
                State.MOVING -> VehicleState.MOVING
                State.UNKNOWN -> VehicleState.UNKNOWN
            }
        )
    }

    fun update(newState: VehicleState) {
        if (newState == vehicleState) return
        vehicleState = newState
        state = when (newState) {
            VehicleState.PARKED -> State.PARKED
            VehicleState.MOVING -> State.MOVING
            VehicleState.UNKNOWN -> State.UNKNOWN
        }
        listeners.forEach { it(state) }
    }

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }
}
