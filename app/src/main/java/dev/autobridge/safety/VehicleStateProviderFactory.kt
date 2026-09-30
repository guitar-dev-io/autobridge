package dev.autobridge.safety

import androidx.car.app.CarContext
import dev.autobridge.BuildConfig

/**
 * Picks the vehicle state source for the session.
 *
 * A real car reads CAR_SPEED through [SpeedGate]; the DHU and dev builds have no such signal, so
 * they get [MockVehicleStateProvider] and can simulate one. Both publish to [ParkingStateStore],
 * which is what every status row reads.
 *
 * Selecting the mock no longer means "bypass the gate": the gate itself is off by default
 * ([SafetyEnforcement]), and the provider only decides where the reading comes from.
 */
object VehicleStateProviderFactory {
    fun create(carContext: CarContext, onMoving: () -> Unit): VehicleStateProvider =
        if (BuildConfig.DHU_TEST_MODE || DevMode.isEnabled) {
            MockVehicleStateProvider.attach(onMoving)
        } else {
            SpeedGate(carContext, onMoving)
        }
}
