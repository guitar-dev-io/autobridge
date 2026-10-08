package dev.autobridge.duoscreen.car

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import dev.autobridge.duoscreen.R
import dev.autobridge.duoscreen.layout.DuoScreenStore
import dev.autobridge.logging.StructuredLog

/**
 * The Arrange card's "Change app": the phone's launchable apps as a car list, the pane's current
 * one first. Picking one stores it for the pane and goes back; [DuoScreenScreen] applies the
 * stored choice as soon as its surface returns, the same path a choice made on the phone takes.
 */
class DuoScreenAppPickerScreen(
    carContext: CarContext,
    private val paneId: Int
) : Screen(carContext) {
    private companion object {
        const val TAG = "AutoBridgeDuoPicker"
        const val ICON_PX = 96
        const val DEFAULT_LIMIT = 30
    }

    private data class App(val packageName: String, val label: String, val icon: CarIcon?)

    /** Read once: a launcher query and the icons are too slow to repeat on every template. */
    private val apps: List<App> by lazy { loadApps() }

    override fun onGetTemplate(): Template {
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(DEFAULT_LIMIT).coerceAtLeast(1)
        val list = ItemList.Builder()
        val current = DuoScreenStore.packages(carContext).getOrNull(paneId)
        val ordered = apps.sortedBy { if (it.packageName == current) 0 else 1 }.take(limit)
        if (ordered.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.duo_pick_empty))
        ordered.forEach { app ->
            list.addItem(
                Row.Builder()
                    .setTitle(app.label)
                    .apply { app.icon?.let { setImage(it) } }
                    .setOnClickListener {
                        DuoScreenStore.setPackage(carContext, paneId, app.packageName)
                        StructuredLog.i(TAG, "Pane $paneId -> ${app.packageName}")
                        screenManager.pop()
                    }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.duo_pick_title, paneId + 1))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(list.build())
            .build()
    }

    private fun loadApps(): List<App> {
        val pm = carContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return found
            .mapNotNull { it.activityInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != carContext.packageName }
            .map { info ->
                App(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = runCatching {
                        val drawable = info.loadIcon(pm)
                        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
                        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
                        drawable.draw(Canvas(bitmap))
                        CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
                    }.getOrNull()
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
