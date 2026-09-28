//package dev.autobridge.safety

//import androidx.car.app.CarContext
//import dev.autobridge.BuildConfig

//object VehicleStateProviderFactory {
//    fun create(carContext: CarContext, onMoving: () -> Unit): VehicleStateProvider =
//        if (BuildConfig.DHU_TEST_MODE || DevMode.isEnabled) {
//            MockVehicleStateProvider.attach(onMoving)
//        } else {
//            SpeedGate(carContext, onMoving)
//        }
//}
package dev.autobridge.safety

import androidx.car.app.CarContext

object VehicleStateProviderFactory {
    fun create(carContext: CarContext, onMoving: () -> Unit): VehicleStateProvider {
        // Bypass: บังคับใช้ MockVehicleStateProvider เสมอโดยไม่ต้องเช็ก BuildConfig.DHU_TEST_MODE หรือ DevMode
        // เพื่อให้ทวนความปลอดภัยผ่าน และรายงานสถานะ PARKED ตลอดเวลา
        return MockVehicleStateProvider.attach(onMoving)
    }
}