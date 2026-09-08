package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.apps.InstalledAppRepository
import dev.autobridge.apps.QuickAppLauncher
import dev.autobridge.apps.QuickAppsStore
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy

/** Parked-only car-native launcher for the phone's configured Quick Apps. */
class CarAppsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val allApps = InstalledAppRepository.listLaunchableApps(carContext)
        QuickAppsStore.syncFavorites(carContext, allApps)
        val apps = QuickAppsStore.enabledInstalledApps(carContext, allApps).take(24)
        val list = ItemList.Builder()
        if (apps.isEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle("No Quick Apps configured")
                    .addText("Enable apps from AutoBridge on the phone")
                    .setEnabled(false)
                    .build()
            )
        } else {
            apps.forEach { app ->
                list.addItem(
                    Row.Builder()
                        .setTitle(app.label)
                        .addText("Quick App • parked only")
                        .setOnClickListener {
                            if (!FeaturePolicy.app.isAvailable(Feature.QUICK_APPS)) {
                                CarToast.makeText(
                                    carContext,
                                    FeaturePolicy.app.denialMessage(Feature.QUICK_APPS),
                                    CarToast.LENGTH_SHORT
                                ).show()
                            } else if (!QuickAppLauncher.launch(carContext, app.packageName)) {
                                CarToast.makeText(
                                    carContext,
                                    "Could not launch ${app.label}",
                                    CarToast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .build()
                )
            }
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Quick Apps")
                    .setStartHeaderAction(androidx.car.app.model.Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }
}
