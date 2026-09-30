package dev.autobridge.safety

/**
 * Turns a raw speed sample into a vehicle state.
 *
 * This classifier reports what the sensor says and nothing else. Whether a MOVING reading is
 * allowed to deny a feature is [SafetyEnforcement]'s decision, not this one's - a classifier that
 * lies about the speed leaves no way to display it either.
 */
object SpeedPolicy {
    // Sensor noise on a stationary vehicle stays well under this; real creeping movement
    // (e.g. parking-lot crawl) is several m/s and stays well above it.
    private const val PARKED_EPSILON_MPS = 0.05f

    fun classify(speed: Float?): ParkingStateStore.State = when {
        speed == null || !speed.isFinite() -> ParkingStateStore.State.UNKNOWN
        kotlin.math.abs(speed) < PARKED_EPSILON_MPS -> ParkingStateStore.State.PARKED
        else -> ParkingStateStore.State.MOVING
    }
}
