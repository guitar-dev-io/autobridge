package dev.autobridge.safety

object SpeedPolicy {
    fun classify(speed: Float?): ParkingStateStore.State = when {
        speed == null || !speed.isFinite() -> ParkingStateStore.State.UNKNOWN
        speed == 0f -> ParkingStateStore.State.PARKED
        else -> ParkingStateStore.State.MOVING
    }
}
