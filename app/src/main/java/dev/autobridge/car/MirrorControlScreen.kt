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
//                CarNavigation.open(screenManager, "CarAppsScreen") { CarAppsScreen(carContext) }
//            })
//            .addItem(navigationRow("Media", "Playlists and MediaSession controls") {
//                CarNavigation.open(screenManager, "CarMediaScreen") { CarMediaScreen(carContext) }
//            })
//            .addItem(navigationRow("Settings", "Mirror, touch and safety settings") {
//                CarNavigation.open(screenManager, "CarSettingsScreen") { CarSettingsScreen(carContext, onSafetyRequested) }
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.input.InputBackend
import dev.autobridge.input.TouchRouter
import dev.autobridge.display.ScreenPowerController
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.safety.ParkingStateStore
import dev.autobridge.safety.SafetyEnforcement

/**
 * Host-managed dashboard for the car side. The live phone pixels remain owned by the
 * NavigationTemplate surface; this screen provides the safe car-native navigation around them.
 */
class MirrorControlScreen(
    carContext: CarContext,
    private val onSafetyRequested: () -> Unit
) : Screen(carContext) {
    /** Redraws the Vehicle row; a state nobody repaints is not a display. */
    private val parkingListener: (ParkingStateStore.State) -> Unit = {
        carContext.mainExecutor.execute { invalidate() }
    }

    init {
        // Asked for on UNKNOWN rather than on "not parked": the gate only reports now
        // (SafetyEnforcement), so the reason to hold CAR_SPEED is that there is no reading to show
        // at all. A car that reports MOVING is already answering, and is not re-prompted.
        carContext.mainExecutor.execute {
            if (ParkingStateStore.state == ParkingStateStore.State.UNKNOWN) onSafetyRequested()
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = ParkingStateStore.addListener(parkingListener)

            override fun onStop(owner: LifecycleOwner) = ParkingStateStore.removeListener(parkingListener)
        })
    }

    override fun onGetTemplate(): Template {
        val controls = ItemList.Builder()
            .addItem(statusRow(carContext.getString(R.string.car_mirror_vehicle), vehicleLabel()))
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_mirror_speed_safety))
                    .addText(carContext.getString(R.string.car_mirror_speed_safety_caption))
                    .setOnClickListener { onSafetyRequested() }
                    .build()
            )
            .addItem(
                statusRow(
                    carContext.getString(R.string.car_mirror_status),
                    carContext.getString(
                        if (MirrorCoordinator.isMirroring) R.string.car_diag_active
                        else R.string.car_diag_inactive
                    )
                )
            )
            .addItem(statusRow(carContext.getString(R.string.car_mirror_phone_display), ScreenPowerController.statusLabel()))
            .addItem(
                navigationRow(
                    carContext.getString(R.string.car_mirror_dim_now),
                    carContext.getString(R.string.car_mirror_dim_now_caption)
                ) {
                val applied = ScreenPowerController.dimNow()
                CarToast.makeText(
                    carContext,
                    if (applied) ScreenPowerController.statusLabel()
                    else carContext.getString(R.string.mirror_start_first),
                    CarToast.LENGTH_SHORT
                ).show()
                invalidate()
            })
            .addItem(
                navigationRow(
                    carContext.getString(R.string.car_mirror_restore),
                    carContext.getString(R.string.car_mirror_restore_caption)
                ) {
                val restored = ScreenPowerController.restorePhoneScreen()
                CarToast.makeText(
                    carContext,
                    if (restored) carContext.getString(R.string.mirror_display_restored)
                    else ScreenPowerController.statusLabel(),
                    CarToast.LENGTH_SHORT
                ).show()
                invalidate()
            })
            .addItem(
                navigationRow(carContext.getString(R.string.car_mirror_apps), carContext.getString(R.string.car_mirror_apps_caption)) {
                CarNavigation.open(screenManager, "CarAppsScreen") { CarAppsScreen(carContext) }
                }
            )
            .addItem(
                navigationRow(carContext.getString(R.string.car_mirror_media), carContext.getString(R.string.car_mirror_media_caption)) {
                CarNavigation.open(screenManager, "CarMediaScreen") { CarMediaScreen(carContext) }
                }
            )
            .addItem(
                navigationRow(carContext.getString(R.string.car_mirror_settings), carContext.getString(R.string.car_mirror_settings_caption)) {
                CarNavigation.open(screenManager, "CarSettingsScreen") {
                        CarSettingsScreen(carContext, onSafetyRequested)
                    }
                }
            )
            .addItem(systemActionRow(carContext.getString(R.string.car_mirror_phone_back), InputBackend.SystemAction.BACK))
            .addItem(systemActionRow(carContext.getString(R.string.car_mirror_phone_home), InputBackend.SystemAction.HOME))
            .addItem(
                systemActionRow(
                    carContext.getString(R.string.car_mirror_phone_recents),
                    InputBackend.SystemAction.RECENTS
                )
            )
            .addItem(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.car_settings_stop_mirroring))
                    .addText(carContext.getString(R.string.car_settings_stop_mirroring_caption))
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
                    .setTitle(carContext.getString(R.string.app_name))
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
            .addText(carContext.getString(R.string.car_mirror_send_to_phone))
            .setOnClickListener {
                ScreenPowerController.userActivity()
                // Bypass: ตัดการเช็ก ParkingStateStore.isParked ออก เพื่อส่งปุ่มกดระบบได้ทุกสถานะ
                if (!TouchRouter.systemAction(action)) {
                    CarToast.makeText(
                        carContext,
                        carContext.getString(R.string.car_mirror_needs_input_backend),
                        CarToast.LENGTH_SHORT
                    ).show()
                }
            }
            .build()

    /**
     * The real reading. This row exists to tell the driver what the car is reporting, so it must
     * never print PARKED over a MOVING sample - that was the one thing the previous bypass broke
     * that no feature gate would have noticed.
     */
    private fun vehicleLabel(): String = SafetyEnforcement.statusLabel(ParkingStateStore.state)
}