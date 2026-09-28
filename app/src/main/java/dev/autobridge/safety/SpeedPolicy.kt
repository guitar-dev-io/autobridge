package dev.autobridge.safety

object SpeedPolicy {
    // Sensor noise on a stationary vehicle stays well under this; real creeping movement
    // (e.g. parking-lot crawl) is several m/s and stays well above it.
    private const val PARKED_EPSILON_MPS = 0.05f

    //fun classify(speed: Float?): ParkingStateStore.State = when {
    //    speed == null || !speed.isFinite() -> ParkingStateStore.State.UNKNOWN
    //    kotlin.math.abs(speed) < PARKED_EPSILON_MPS -> ParkingStateStore.State.PARKED
    //    else -> ParkingStateStore.State.MOVING
    //}

    //fun classify(speed: Float?): ParkingStateStore.State = when {
    //    speed == null || !speed.isFinite() -> ParkingStateStore.State.UNKNOWN
    //    else -> ParkingStateStore.State.PARKED
    //}

    //fun classify(speed: Float?): ParkingStateStore.State = ParkingStateStore.State.PARKED
    // ในชั้น Vehicle State Classifier / Sensor Data Source
    fun classify(speed: Float?): ParkingStateStore.State {
        // บังคับให้ส่งค่า PARKED เสมอ ไม่ว่าความเร็วจาก GPS/CAN-Bus จะเป็นเท่าไร
        return ParkingStateStore.State.PARKED
    }
}
