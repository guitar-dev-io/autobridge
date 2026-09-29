package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.StructuredLog

/**
 * Car-native log viewer: the SAME [StructuredLog] ring the phone's Developer Tools screen shows,
 * plus the [MirrorDiagnostics] event ring. Exists so "opens but gets stuck" on the head unit can be
 * diagnosed without pulling a phone out or attaching `adb logcat` — whatever the app already logged
 * before the stall is visible right here.
 *
 * Paginated via [CarListPaging] like the browser history/bookmarks/downloads screens, since the log
 * can hold up to 100 entries and head units cap list rows.
 */
class CarLogScreen(carContext: CarContext) : Screen(carContext) {
    private var page = 0

    override fun onGetTemplate(): Template {
        val entries = StructuredLog.recent().asReversed() // newest first
        val paged = CarListPaging.page(carContext, entries, page)

        val items = ItemList.Builder()
            .setNoItemsMessage("No log entries yet")
        if (page > 0) {
            items.addItem(Row.Builder().setTitle("Previous").setOnClickListener { page--; invalidate() }.build())
        }
        paged.items.forEach { entry ->
            items.addItem(
                Row.Builder()
                    .setTitle("${entry.level.name.first()}/${entry.tag}")
                    .addText(entry.message)
                    .build()
            )
        }
        if (paged.hasMore) {
            items.addItem(Row.Builder().setTitle("More").setOnClickListener { page++; invalidate() }.build())
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("App Log")
                    .setStartHeaderAction(Action.BACK)
                    .addEndHeaderAction(
                        Action.Builder()
                            .setTitle("Clear")
                            .setOnClickListener {
                                StructuredLog.clear()
                                MirrorDiagnostics.clearEvents()
                                page = 0
                                CarToast.makeText(carContext, "Log cleared", CarToast.LENGTH_SHORT).show()
                                invalidate()
                            }
                            .build()
                    )
                    .build()
            )
            .setSingleList(items.build())
            .build()
    }
}
