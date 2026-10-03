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
import dev.autobridge.R
import dev.autobridge.apps.InstalledApp
import dev.autobridge.apps.InstalledAppRepository
import dev.autobridge.apps.QuickAppLauncher

/**
 * App-icon grid launcher, styled like a car "home" (CarWebGuru-style). Renders launchable phone
 * apps as tappable tiles with their real icons and routes each tap through [QuickAppLauncher],
 * which resolves the right SmartMode (media / mirror / browser / native) per package.
 *
 * The first row is reserved for AutoBridge's own destinations (Now Playing, Media library, Web)
 * so the grid doubles as the primary navigation surface.
 */
class CarLauncherScreen(carContext: CarContext) : Screen(carContext) {
    private data class Shortcut(val title: String, val iconRes: Int, val onClick: () -> Unit)

    // Android Auto caps a single GridTemplate at 6 items on many hosts; keep built-ins + apps
    // under that ceiling and let the user open the full app list for the rest.
    private val maxAppTiles = 6

    override fun onGetTemplate(): Template {
        val apps = InstalledAppRepository.listLaunchableApps(carContext)
        val grid = ItemList.Builder()

        builtinShortcuts().forEach { shortcut ->
            grid.addItem(
                GridItem.Builder()
                    .setTitle(shortcut.title)
                    .setImage(iconFromResource(shortcut.iconRes), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener { shortcut.onClick() }
                    .build()
            )
        }

        val remaining = (maxAppTiles - builtinShortcuts().size).coerceAtLeast(0)
        apps.take(remaining).forEach { app ->
            grid.addItem(appTile(app))
        }

        return GridTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.car_launcher_title))
                    .setStartHeaderAction(Action.APP_ICON)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setIcon(CarIcons.of(carContext, CarIcons.APPS))
                            .setOnClickListener { CarNavigation.open(screenManager, "CarAppsScreen") { CarAppsScreen(carContext) } }
                            .build()
                    )
                    .build()
            )
            .setSingleList(grid.build())
            .build()
    }

    private fun builtinShortcuts(): List<Shortcut> = listOf(
        Shortcut(carContext.getString(R.string.car_now_playing_title), dev.autobridge.R.drawable.ic_car_panel) {
            CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) }
        },
        Shortcut(carContext.getString(R.string.car_media_title), dev.autobridge.R.drawable.ic_car_panel) {
            CarNavigation.open(screenManager, "CarMediaLibraryScreen") { CarMediaLibraryScreen(carContext) }
        },
        Shortcut(carContext.getString(R.string.car_launcher_web_video), dev.autobridge.R.drawable.ic_car_panel) {
            CarNavigation.open(screenManager, "CarWebScreen") { CarWebScreen(carContext) }
        },
        Shortcut(carContext.getString(R.string.car_mirror_intro_title), dev.autobridge.R.drawable.ic_car_home) {
            CarNavigation.open(screenManager, "MirrorCarScreen") { MirrorCarScreen(carContext) }
        }
    )

    private fun appTile(app: InstalledApp): GridItem {
        val builder = GridItem.Builder()
            .setTitle(app.label)
            .setOnClickListener {
                if (!QuickAppLauncher.launch(carContext, app.packageName)) {
                    CarToast.makeText(
                        carContext,
                        carContext.getString(R.string.car_could_not_launch, app.label),
                        CarToast.LENGTH_SHORT
                    ).show()
                }
            }
        val icon = appIcon(app.packageName)
        if (icon != null) {
            builder.setImage(icon)
        } else {
            builder.setImage(iconFromResource(dev.autobridge.R.drawable.ic_car_home), GridItem.IMAGE_TYPE_ICON)
        }
        return builder.build()
    }

    private fun appIcon(packageName: String): CarIcon? = runCatching {
        val drawable: Drawable = carContext.packageManager.getApplicationIcon(packageName)
        val bitmap = drawable.toBitmap(ICON_SIZE_PX, ICON_SIZE_PX) ?: return null
        CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
    }.getOrNull()

    private fun iconFromResource(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()

    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap? {
        if (this is BitmapDrawable && bitmap != null) {
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val safeWidth = if (intrinsicWidth > 0) width else width
        val safeHeight = if (intrinsicHeight > 0) height else height
        return runCatching {
            val bitmap = Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
            bitmap
        }.getOrNull()
    }

    private companion object {
        const val ICON_SIZE_PX = 128
    }
}
