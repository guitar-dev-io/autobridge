package dev.autobridge.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import dev.autobridge.R
import dev.autobridge.expense.CostStats
import dev.autobridge.expense.CostsActivity
import dev.autobridge.expense.ExpenseStore
import dev.autobridge.fuel.FuelLogStore

/** What the car costs, at a glance: this month, last month and the usual month. Entered on the phone. */
class CarCostsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val costs = CostStats.monthly(FuelLogStore.all(carContext), ExpenseStore.all(carContext))
        val now = System.currentTimeMillis()
        val thisMonth = CostStats.thisMonth(costs, now)
        val pane = Pane.Builder()
        pane.addRow(
            Row.Builder()
                .setTitle(carContext.getString(R.string.costs_baht, CostsActivity.money(thisMonth.totalBaht)))
                .addText(carContext.getString(R.string.costs_this_month, CostsActivity.monthLabel(thisMonth.month)))
                .addText(carContext.getString(R.string.costs_split, CostsActivity.money(thisMonth.fuelBaht), CostsActivity.money(thisMonth.otherBaht)))
                .build()
        )
        costs.firstOrNull { it.month < thisMonth.month }?.let { last ->
            pane.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.costs_baht, CostsActivity.money(last.totalBaht)))
                    .addText(CostsActivity.monthLabel(last.month))
                    .build()
            )
        }
        CostStats.average(costs, now)?.let { average ->
            pane.addRow(
                Row.Builder()
                    .setTitle(carContext.getString(R.string.costs_average, CostsActivity.money(average.totalBaht)))
                    .build()
            )
        }
        if (costs.isEmpty()) {
            pane.addRow(Row.Builder().setTitle(carContext.getString(R.string.costs_empty_title)).addText(carContext.getString(R.string.costs_car_empty)).build())
        }
        return PaneTemplate.Builder(pane.build())
            .setHeader(
                Header.Builder()
                    .setTitle(carContext.getString(R.string.costs_title))
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }
}
