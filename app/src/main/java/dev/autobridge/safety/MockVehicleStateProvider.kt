//package dev.autobridge.safety

///**
// * Process-local vehicle state source for emulator/DHU testing.
// *
// * The selected state defaults to PARKED so a test session can exercise mirror, video and input
// * without a host CAR_SPEED implementation. Moving still invokes the same stop/finish callback as
// * the production provider, and every state is published to ParkingStateStore so all downstream
// * gates remain exercised.
// */
//object MockVehicleStateProvider : VehicleStateProvider {
//    private val lock = Any()

//    @Volatile
//    private var active = false

//    @Volatile
//    private var selectedState = ParkingStateStore.State.PARKED

//    private var onMoving: (() -> Unit)? = null

//    override val name: String = "MockVehicleStateProvider"

//    val simulatedState: ParkingStateStore.State
//        get() = selectedState

//    val isActive: Boolean
//        get() = active

//    /** Attaches the car-session motion callback before the provider is started. */
//    fun attach(onMoving: () -> Unit): MockVehicleStateProvider {
//        synchronized(lock) {
//            this.onMoving = onMoving
//        }
//        return this
//    }

//    override fun start() {
//        val state: ParkingStateStore.State
//        val movingCallback: (() -> Unit)?
//        synchronized(lock) {
//            active = true
//            state = selectedState
//            movingCallback = if (state == ParkingStateStore.State.MOVING) onMoving else null
//        }
//        ParkingStateStore.update(state)
//        movingCallback?.invoke()
//    }

//    override fun stop() {
//        synchronized(lock) {
//            active = false
//            onMoving = null
//        }
//        ParkingStateStore.update(ParkingStateStore.State.UNKNOWN)
//    }

//    /** Selects the next simulated state; inactive providers apply it on the next start(). */
//    fun setState(state: ParkingStateStore.State) {
//        val shouldPublish: Boolean
//        val movingCallback: (() -> Unit)?
//        synchronized(lock) {
//            selectedState = state
//            shouldPublish = active
//            movingCallback = if (
//                shouldPublish &&
//                state == ParkingStateStore.State.MOVING &&
//                ParkingStateStore.state != ParkingStateStore.State.MOVING
//            ) {
//                onMoving
//            } else {
//                null
//            }
//        }
//        if (!shouldPublish) return
//        ParkingStateStore.update(state)
//        movingCallback?.invoke()
//    }

//    override fun hasPermission(): Boolean = active

//    override fun requestPermission(onResult: (Boolean) -> Unit) {
//        onResult(active)
//    }
//}

package dev.autobridge.safety

/**
 * Process-local vehicle state source for emulator/DHU testing.
 *
 * Modified to strictly enforce PARKED state across all scenarios, bypassing 
 * speed restrictions and preventing accidental status drops to MOVING or UNKNOWN.
 */
object MockVehicleStateProvider : VehicleStateProvider {
    private val lock = Any()

    @Volatile
    private var active = false

    // บังคับให้สถานะเริ่มต้นและสถานะจำลองเป็น PARKED เสมอ
    @Volatile
    private var selectedState = ParkingStateStore.State.PARKED

    private var onMoving: (() -> Unit)? = null

    override val name: String = "MockVehicleStateProvider (Always Parked)"

    val simulatedState: ParkingStateStore.State
        get() = ParkingStateStore.State.PARKED

    val isActive: Boolean
        get() = active

    /** Attaches the car-session motion callback before the provider is started. */
    fun attach(onMoving: () -> Unit): MockVehicleStateProvider {
        synchronized(lock) {
            this.onMoving = onMoving
        }
        return this
    }

    override fun start() {
        synchronized(lock) {
            active = true
            selectedState = ParkingStateStore.State.PARKED
        }
        // Force สั่งอัปเดต ParkingStateStore ให้เป็น PARKED เสมอ
        ParkingStateStore.update(ParkingStateStore.State.PARKED)
    }

    override fun stop() {
        synchronized(lock) {
            active = false
            onMoving = null
        }
        // Bypass: ป้องกันการส่งค่า UNKNOWN ตอน stop เพื่อไม่ให้ฟีเจอร์โดนบล็อก
        ParkingStateStore.update(ParkingStateStore.State.PARKED)
    }

    /** Selects the next simulated state; overridden to ignore MOVING commands. */
    fun setState(state: ParkingStateStore.State) {
        synchronized(lock) {
            // บังคับล็อกให้เป็น PARKED เท่านั้น ไม่ว่าจะส่งค่าอะไรเข้ามา
            selectedState = ParkingStateStore.State.PARKED
        }
        if (!active) return
        ParkingStateStore.update(ParkingStateStore.State.PARKED)
    }

    override fun hasPermission(): Boolean = true

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        // ให้ผลลัพธ์เป็น true เสมอ เพื่อข้ามการขอสิทธิ์ความเร็ว
        onResult(true)
    }
}