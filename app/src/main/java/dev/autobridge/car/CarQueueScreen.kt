package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.bridge.AutoBridgeSessionManager
import dev.autobridge.bridge.BridgeSource
import dev.autobridge.browser.BrowserPlayQueue

/**
 * The full play queue, behind the home dashboard's Queue block.
 *
 * It is a view onto [BrowserPlayQueue] and nothing else: no copy of the list is held, and tapping
 * a row hands the address to [AutoBridgeSessionManager] exactly as the bridge's own "next" does.
 * Playing an item takes it out of the queue first, because that is what the queue means here — it
 * is consumed as it plays rather than kept as a playlist (see [BrowserPlayQueue]).
 *
 * There is no reordering. Dragging rows is not something to ask a driver for, and a car list
 * template has no affordance for it.
 */
class CarQueueScreen(carContext: CarContext, private val page: Int = 0) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val items = BrowserPlayQueue.items(carContext)
        val list = ItemList.Builder()
        if (items.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(R.string.car_queue_empty))
        } else {
            val paged = CarListPaging.page(carContext, items, page)
            paged.items.forEach { list.addItem(row(it)) }
            if (paged.hasMore) {
                list.addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(R.string.car_home_more))
                        .setOnClickListener { screenManager.push(CarQueueScreen(carContext, page + 1)) }
                        .build()
                )
            }
        }

        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.car_queue_title))
            .setStartHeaderAction(Action.BACK)
        if (items.isNotEmpty()) {
            header.addEndHeaderAction(
                Action.Builder()
                    .setIcon(CarIcons.of(carContext, CarIcons.CLEAR))
                    .setOnClickListener {
                        AutoBridgeSessionManager.queueClear(carContext)
                        invalidate()
                    }
                    .build()
            )
        }
        return ListTemplate.Builder().setHeader(header.build()).setSingleList(list.build()).build()
    }

    private fun row(item: BrowserPlayQueue.Item): Row {
        val subtitle = listOfNotNull(
            HomeDashboardSource.label(carContext, item.url),
            HomeDashboardClock.duration(item.durationMs)
        ).joinToString("  •  ")
        val builder = Row.Builder().setTitle(item.title.ifBlank { BridgeSource(item.url).displayHost })
        if (subtitle.isNotBlank()) builder.addText(subtitle)
        builder.setOnClickListener { play(item) }
        return builder.build()
    }

    private fun play(item: BrowserPlayQueue.Item) {
        AutoBridgeSessionManager.queueRemove(carContext, item.url)
        AutoBridgeSessionManager.open(
            carContext,
            BridgeSource(item.url, item.title, origin = BridgeSource.Origin.QUEUE)
        )
    }
}
