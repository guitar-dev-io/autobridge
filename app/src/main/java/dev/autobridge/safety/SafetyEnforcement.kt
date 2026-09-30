package dev.autobridge.safety

/**
 * Whether the parked-only gate blocks a feature, or only reports what the vehicle is doing.
 *
 * ## Why this exists
 *
 * The motion signal used to be disabled by editing the things that produce it: [SpeedPolicy]
 * returned PARKED for every speed, [MockVehicleStateProvider] refused to publish anything else, and
 * `FeaturePolicy.decide()` rewrote its own argument before evaluating. That silenced the gate, but
 * it also destroyed the reading, so nothing in the app could say whether the car was moving - not
 * even a status row that only wanted to display it.
 *
 * The two concerns are now separate. The sensors, the classifier and the policy all report the
 * truth again; this one flag decides whether that truth is allowed to deny a feature.
 *
 * [BLOCK_WHEN_MOVING] is false: AutoBridge observes motion and shows it, and blocks nothing. The
 * gate logic it would use is still present and still tested, so turning enforcement back on is this
 * flag and nothing else.
 */
object SafetyEnforcement {

    /**
     * Not a `const`: a compile-time constant would let the compiler fold every gate below into a
     * literal, and the branch that enforces would stop being compiled at all.
     */
    private val BLOCK_WHEN_MOVING: Boolean = false

    /** True when a moving vehicle denies parked-only features. */
    val isBlocking: Boolean
        get() = BLOCK_WHEN_MOVING

    /**
     * The parked flag a *gate* should consult. While enforcement is off this is always true, so the
     * caller's own `&& parked` reads as written and keeps working if the flag is ever flipped.
     *
     * Anything that displays the vehicle state must read [ParkingStateStore] directly instead, or
     * it will report PARKED to the driver while the car is moving.
     */
    fun gateParked(actual: Boolean = ParkingStateStore.isParked): Boolean = actual || !BLOCK_WHEN_MOVING

    /** How the current state reads in a status row, including whether it is being acted on. */
    fun statusLabel(state: ParkingStateStore.State = ParkingStateStore.state): String = when (state) {
        ParkingStateStore.State.PARKED -> "Parked"
        ParkingStateStore.State.MOVING -> if (isBlocking) "Moving (blocked)" else "Moving"
        ParkingStateStore.State.UNKNOWN -> if (isBlocking) "Unknown (blocked)" else "Unknown"
    }
}
