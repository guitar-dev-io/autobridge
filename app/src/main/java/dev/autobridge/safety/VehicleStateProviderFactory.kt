package dev.autobridge.safety

import androidx.car.app.CarContext

object VehicleStateProviderFactory {
    fun create(carContext: CarContext, onMoving: () -> Unit): VehicleStateProvider =
        if (DevMode.isEnabled) {
            MockVehicleStateProvider.attach(onMoving)
        } else {
            SpeedGate(carContext, onMoving)
        }
}
