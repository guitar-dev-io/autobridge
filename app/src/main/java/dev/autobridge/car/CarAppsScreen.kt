package dev.autobridge.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import dev.autobridge.apps.InstalledApp
import dev.autobridge.apps.InstalledAppRepository
import dev.autobridge.apps.QuickAppLauncher
import dev.autobridge.apps.QuickAppsStore
import dev.autobridge.R
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy

/** Car-native launcher for the phone's configured Quick Apps, rendered as an icon grid. */
class CarAppsScreen(carContext: CarContext) : Screen(carContext) {
    private companion object {
        // Android Auto hosts cap grids around 24 items; keep well within that.
        const val MAX_APPS = 24
        const val ICON_SIZE_PX = 128
    }

    override fun onGetTemplate(): Template {
        val allApps = InstalledAppRepository.listLaunchableApps(carContext)
        QuickAppsStore.syncFavorites(carContext, allApps)
        val apps = QuickAppsStore.enabledInstalledApps(carContext, allApps).take(MAX_APPS)

        val grid = ItemList.Builder()
        if (apps.isEmpty()) {
            grid.addItem(
                GridItem.Builder()
                    .setTitle(carContext.getString(R.string.car_apps_empty_title))
                    .setText(carContext.getString(R.string.car_apps_empty_text))
                    .setImage(fallbackIcon())
                    .build()
            )
        } else {
            apps.forEach { app ->
                grid.addItem(
                    GridItem.Builder()
                        .setTitle(app.label)
                        .setImage(appIcon(app))
                        .setOnClickListener { launch(app) }
                        .build()
                )
            }
        }

        return GridTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_apps_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(grid.build())
            .build()
    }

    private fun launch(app: InstalledApp) {
        if (!FeaturePolicy.app.isAvailable(Feature.QUICK_APPS)) {
            CarToast.makeText(
                carContext,
                FeaturePolicy.app.denialMessage(Feature.QUICK_APPS),
                CarToast.LENGTH_SHORT
            ).show()
            return
        }
        if (!QuickAppLauncher.launch(carContext, app.packageName)) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_could_not_launch, app.label),
                CarToast.LENGTH_SHORT
            ).show()
        }
    }

    private fun appIcon(app: InstalledApp): CarIcon {
        val drawable = runCatching {
            carContext.packageManager.getApplicationIcon(app.packageName)
        }.getOrNull()
        val bitmap = drawable?.let { toBitmap(it) } ?: return fallbackIcon()
        return CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }

    private fun fallbackIcon(): CarIcon =
        CarIcon.Builder(
            IconCompat.createWithResource(carContext, android.R.drawable.sym_def_app_icon)
        ).build()

    private fun toBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, ICON_SIZE_PX, ICON_SIZE_PX, true)
        }
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
