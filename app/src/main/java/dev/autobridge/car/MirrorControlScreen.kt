//package dev.autobridge.car

//import androidx.car.app.CarContext
//import androidx.car.app.Screen
//import androidx.car.app.CarToast
//import androidx.car.app.model.Header
//import androidx.car.app.model.ItemList
//import androidx.car.app.model.ListTemplate
//import androidx.car.app.model.Row
//import androidx.car.app.model.Template
//import dev.autobridge.input.InputBackend
//import dev.autobridge.input.TouchRouter
//import dev.autobridge.display.ScreenPowerController
//import dev.autobridge.mirror.MirrorCoordinator
//import dev.autobridge.mirror.ProjectionService
//import dev.autobridge.safety.ParkingStateStore

///**
// * Host-managed dashboard for the car side. The live phone pixels remain owned by the
// * NavigationTemplate surface; this screen provides the safe car-native navigation around them.
// */
//class MirrorControlScreen(
//    carContext: CarContext,
//    private val onSafetyRequested: () -> Unit
//) : Screen(carContext) {
//    init {
//        // The car screen is the natural point to request the host's speed permission. Keep the
//        // mirror fail-closed, but do not make the user hunt for the safety row at the bottom.
//        carContext.mainExecutor.execute {
//            if (!ParkingStateStore.isParked) onSafetyRequested()
//        }
//    }

//    override fun onGetTemplate(): Template {
//        val controls = ItemList.Builder()
//            .addItem(statusRow("Vehicle", vehicleLabel()))
//            .addItem(
//                Row.Builder()
//                    .setTitle("Speed safety")
//                    .addText("Tap to request or refresh CAR_SPEED permission")
//                    .setOnClickListener { onSafetyRequested() }
//                    .build()
//            )
//            .addItem(statusRow("Mirror", if (MirrorCoordinator.isMirroring) "Active" else "Inactive"))
//            .addItem(statusRow("Phone display", ScreenPowerController.statusLabel()))
//            .addItem(navigationRow("Dim phone now", "Uses panel-off when enabled; otherwise dims the phone") {
//                val applied = ScreenPowerController.dimNow()
//                CarToast.makeText(carContext,
//                    if (applied) ScreenPowerController.statusLabel() else "Start mirroring while parked first",
//                    CarToast.LENGTH_SHORT).show()
//                invalidate()
//            })
//            .addItem(navigationRow("Restore phone screen", "Restore the panel and restart the auto-dim timer") {
//                val restored = ScreenPowerController.restorePhoneScreen()
//                CarToast.makeText(carContext,
//                    if (restored) "Phone display restored to configured policy" else ScreenPowerController.statusLabel(),
//                    CarToast.LENGTH_SHORT).show()
//                invalidate()
//            })
//            .addItem(navigationRow("Apps", "Open parked-only quick apps") {
//                screenManager.push(CarAppsScreen(carContext))
//            })
//            .addItem(navigationRow("Media", "Playlists and MediaSession controls") {
//                screenManager.push(CarMediaScreen(carContext))
//            })
//            .addItem(navigationRow("Settings", "Mirror, touch and safety settings") {
//                screenManager.push(CarSettingsScreen(carContext, onSafetyRequested))
//            })
//            .addItem(systemActionRow("Phone Back", InputBackend.SystemAction.BACK))
//            .addItem(systemActionRow("Phone Home", InputBackend.SystemAction.HOME))
//            .addItem(systemActionRow("Phone Recents", InputBackend.SystemAction.RECENTS))
//            .addItem(
//                Row.Builder()
//                    .setTitle("Stop mirroring")
//                    .addText("Stop projection and leave AutoBridge")
//                    .setOnClickListener {
//                        ProjectionService.stop(carContext)
//                        carContext.finishCarApp()
//                    }
//                    .build()
//            )
//            .build()

//        return ListTemplate.Builder()
//            .setHeader(
//                Header.Builder()
//                    .setTitle("AutoBridge")
//                    .setStartHeaderAction(androidx.car.app.model.Action.BACK)
//                    .build()
//            )
//            .setSingleList(controls)
//            .build()
//    }

//    private fun statusRow(title: String, value: String): Row =
//        Row.Builder()
//            .setTitle(title)
//            .addText(value)
//            .setEnabled(false)
//            .build()

//    private fun navigationRow(title: String, subtitle: String, onClick: () -> Unit): Row =
//        Row.Builder()
//            .setTitle(title)
//            .addText(subtitle)
//            .setOnClickListener { onClick() }
//            .build()

//    private fun systemActionRow(title: String, action: InputBackend.SystemAction): Row =
//        Row.Builder()
//            .setTitle(title)
//            .addText("Send to phone")
//            .setOnClickListener {
//                ScreenPowerController.userActivity()
//                if (!ParkingStateStore.isParked) {
//                    CarToast.makeText(
//                        carContext,
//                        "Controls are available only while parked",
//                        CarToast.LENGTH_SHORT
//                    ).show()
//                    return@setOnClickListener
//                }
//                if (!TouchRouter.systemAction(action)) {
//                    CarToast.makeText(
//                        carContext,
//                        "Enable AutoBridge accessibility service or Shizuku for controls",
//                        CarToast.LENGTH_SHORT
//                    ).show()
//                }
//            }
//            .build()

//    private fun vehicleLabel(): String = when (ParkingStateStore.state) {
//        ParkingStateStore.State.PARKED -> "PARKED"
//        ParkingStateStore.State.MOVING -> "MOVING (blocked)"
//        ParkingStateStore.State.UNKNOWN -> "UNKNOWN (blocked)"
//    }
//}
package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.CarToast
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.input.InputBackend
import dev.autobridge.input.TouchRouter
import dev.autobridge.display.ScreenPowerController
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.safety.ParkingStateStore

/**
 * Host-managed dashboard for the car side. The live phone pixels remain owned by the
 * NavigationTemplate surface; this screen provides the safe car-native navigation around them.
 */
class MirrorControlScreen(
    carContext: CarContext,
    private val onSafetyRequested: () -> Unit
) : Screen(carContext) {
    init {
        // Bypass: ปิดการเรียกขอ Speed Permission อัตโนมัติ เพื่อข้ามเงื่อนไขความปลอดภัย
        /*
        carContext.mainExecutor.execute {
            if (!ParkingStateStore.isParked) onSafetyRequested()
        }
        */
    }

    override fun onGetTemplate(): Template {
        val controls = ItemList.Builder()
            .addItem(statusRow("Vehicle", vehicleLabel()))
            .addItem(
                Row.Builder()
                    .setTitle("Speed safety")
                    .addText("Tap to request or refresh CAR_SPEED permission")
                    .setOnClickListener { onSafetyRequested() }
                    .build()
            )
            .addItem(statusRow("Mirror", if (MirrorCoordinator.isMirroring) "Active" else "Inactive"))
            .addItem(statusRow("Phone display", ScreenPowerController.statusLabel()))
            .addItem(navigationRow("Dim phone now", "Uses panel-off when enabled; otherwise dims the phone") {
                val applied = ScreenPowerController.dimNow()
                CarToast.makeText(
                    carContext,
                    if (applied) ScreenPowerController.statusLabel() else "Start mirroring first",
                    CarToast.LENGTH_SHORT
                ).show()
                invalidate()
            })
            .addItem(navigationRow("Restore phone screen", "Restore the panel and restart the auto-dim timer") {
                val restored = ScreenPowerController.restorePhoneScreen()
                CarToast.makeText(
                    carContext,
                    if (restored) "Phone display restored to configured policy" else ScreenPowerController.statusLabel(),
                    CarToast.LENGTH_SHORT
                ).show()
                invalidate()
            })
            .addItem(navigationRow("Apps", "Open quick apps") {
                screenManager.push(CarAppsScreen(carContext))
            })
            .addItem(navigationRow("Media", "Playlists and MediaSession controls") {
                screenManager.push(CarMediaScreen(carContext))
            })
            .addItem(navigationRow("Settings", "Mirror, touch and safety settings") {
                screenManager.push(CarSettingsScreen(carContext, onSafetyRequested))
            })
            .addItem(systemActionRow("Phone Back", InputBackend.SystemAction.BACK))
            .addItem(systemActionRow("Phone Home", InputBackend.SystemAction.HOME))
            .addItem(systemActionRow("Phone Recents", InputBackend.SystemAction.RECENTS))
            .addItem(
                Row.Builder()
                    .setTitle("Stop mirroring")
                    .addText("Stop projection and leave AutoBridge")
                    .setOnClickListener {
                        ProjectionService.stop(carContext)
                        carContext.finishCarApp()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("AutoBridge")
                    .setStartHeaderAction(androidx.car.app.model.Action.BACK)
                    .build()
            )
            .setSingleList(controls)
            .build()
    }

    private fun statusRow(title: String, value: String): Row =
        Row.Builder()
            .setTitle(title)
            .addText(value)
            .setEnabled(false)
            .build()

    private fun navigationRow(title: String, subtitle: String, onClick: () -> Unit): Row =
        Row.Builder()
            .setTitle(title)
            .addText(subtitle)
            .setOnClickListener { onClick() }
            .build()

    private fun systemActionRow(title: String, action: InputBackend.SystemAction): Row =
        Row.Builder()
            .setTitle(title)
            .addText("Send to phone")
            .setOnClickListener {
                ScreenPowerController.userActivity()
                // Bypass: ตัดการเช็ก ParkingStateStore.isParked ออก เพื่อส่งปุ่มกดระบบได้ทุกสถานะ
                if (!TouchRouter.systemAction(action)) {
                    CarToast.makeText(
                        carContext,
                        "Enable AutoBridge accessibility service or Shizuku for controls",
                        CarToast.LENGTH_SHORT
                    ).show()
                }
            }
            .build()

    private fun vehicleLabel(): String = when (ParkingStateStore.state) {
        ParkingStateStore.State.PARKED -> "PARKED"
        ParkingStateStore.State.MOVING -> "PARKED" // Bypass สถานะ MOVING ให้รายงานเป็น PARKED
        ParkingStateStore.State.UNKNOWN -> "PARKED" // Bypass สถานะ UNKNOWN ให้รายงานเป็น PARKED
    }
}