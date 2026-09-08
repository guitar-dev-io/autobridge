package dev.autobridge.safety

/**
 * Source of truth for vehicle safety state used by the Android Auto session.
 *
 * Production implementations must fail closed when speed data is missing or invalid. Test
 * implementations are selected only by [DevMode] and still publish through [ParkingStateStore],
 * so every rendering and input boundary keeps the same safety checks.
 */
interface VehicleStateProvider {
    val name: String

    fun start()

    fun stop()

    fun hasPermission(): Boolean

    fun requestPermission(onResult: (Boolean) -> Unit)
}
