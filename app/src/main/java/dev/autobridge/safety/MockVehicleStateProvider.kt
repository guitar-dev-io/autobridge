package dev.autobridge.safety

/**
 * Process-local vehicle state source for emulator/DHU testing.
 *
 * The selected state defaults to PARKED so a test session can exercise mirror, video and input
 * without a host CAR_SPEED implementation. Moving still invokes the same stop/finish callback as
 * the production provider, and every state is published to ParkingStateStore so all downstream
 * gates remain exercised.
 */
object MockVehicleStateProvider : VehicleStateProvider {
    private val lock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var selectedState = ParkingStateStore.State.PARKED

    private var onMoving: (() -> Unit)? = null

    override val name: String = "MockVehicleStateProvider"

    val simulatedState: ParkingStateStore.State
        get() = selectedState

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
        val state: ParkingStateStore.State
        val movingCallback: (() -> Unit)?
        synchronized(lock) {
            active = true
            state = selectedState
            movingCallback = if (state == ParkingStateStore.State.MOVING) onMoving else null
        }
        ParkingStateStore.update(state)
        movingCallback?.invoke()
    }

    override fun stop() {
        synchronized(lock) {
            active = false
            onMoving = null
        }
        ParkingStateStore.update(ParkingStateStore.State.UNKNOWN)
    }

    /** Selects the next simulated state; inactive providers apply it on the next start(). */
    fun setState(state: ParkingStateStore.State) {
        val shouldPublish: Boolean
        val movingCallback: (() -> Unit)?
        synchronized(lock) {
            selectedState = state
            shouldPublish = active
            movingCallback = if (
                shouldPublish &&
                state == ParkingStateStore.State.MOVING &&
                ParkingStateStore.state != ParkingStateStore.State.MOVING
            ) {
                onMoving
            } else {
                null
            }
        }
        if (!shouldPublish) return
        ParkingStateStore.update(state)
        movingCallback?.invoke()
    }

    override fun hasPermission(): Boolean = active

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        onResult(active)
    }
}
