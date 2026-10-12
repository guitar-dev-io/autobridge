package dev.autobridge.expense

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.fuel.FuelLogStore
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.CarToolForm
import dev.autobridge.ui.CarToolViews
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.time.YearMonth
import java.time.ZoneId

/**
 * What the car costs: this month, the average of the last full months, month by month, split into
 * fuel (or charging, from the fuel log) and everything else entered here (a service, a wash,
 * tolls, parking, tax). A service marked done in Maintenance can add its cost here too.
 */
class CostsActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, CostsActivity::class.java)

        /** "October 2026" in the app's language, in the calendar the language uses. */
        fun monthLabel(month: YearMonth): String {
            val start = Calendar.getInstance().apply { clear(); set(month.year, month.monthValue - 1, 1, 12, 0, 0) }.time
            return SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(start)
        }

        fun money(value: Double): String =
            NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }.format(Math.round(value))
    }

    private val accent = AutoBridgeDesign.ACCENT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    /**
     * As the design: this month as one big figure with fuel against everything else as a split
     * bar, then each cost as a line with its amount on the right, the past months, and Add.
     */
    private fun render() {
        val expenses = ExpenseStore.all(this)
        val costs = CostStats.monthly(FuelLogStore.all(this), expenses)
        val now = System.currentTimeMillis()
        val thisMonth = CostStats.thisMonth(costs, now)
        val body = AutoBridgeDesign.body(this)

        if (costs.isEmpty()) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.costs_empty_title),
                    message = getString(R.string.costs_empty_message),
                    accent = accent
                ),
                gap = 12
            )
        } else {
            body.stack(summaryCard(thisMonth, CostStats.average(costs, now)), gap = 12)
            expenses.take(40).forEach { expense ->
                body.stack(
                    CarToolViews.row(
                        this,
                        title = expense.label.ifBlank { getString(R.string.costs_unnamed) },
                        subtitle = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expense.timeMs)),
                        amount = getString(R.string.money_baht, money(expense.baht)),
                        accent = accent
                    ) { confirmDelete(expense) }
                )
            }
            val past = costs.filter { it.month != thisMonth.month }.take(12)
            if (past.isNotEmpty()) {
                body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.costs_by_month)), gap = 8)
                past.forEach { month ->
                    body.stack(
                        CarToolViews.row(
                            this,
                            title = monthLabel(month.month),
                            subtitle = getString(R.string.costs_split, money(month.fuelBaht), money(month.otherBaht)),
                            amount = getString(R.string.money_baht, money(month.totalBaht))
                        )
                    )
                }
            }
        }
        body.stack(AutoBridgeDesign.pill(this, getString(R.string.costs_add), primary = true, accent = accent) { showAddDialog() }, gap = 8)

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.costs_title),
                    subtitle = monthLabel(thisMonth.month),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun summaryCard(thisMonth: MonthCost, average: MonthCost?) = CarToolViews.card(this) {
        addView(CarToolViews.text(context, getString(R.string.money_baht, money(thisMonth.totalBaht)), 32f, bold = true))
        if (thisMonth.totalBaht > 0) {
            addView(CarToolViews.splitBar(context, thisMonth.fuelBaht, thisMonth.otherBaht, AutoBridgeDesign.ACCENT_FILES, AutoBridgeDesign.ACCENT_SYSTEM))
            addView(CarToolViews.legend(context, listOf(
                AutoBridgeDesign.ACCENT_FILES to getString(R.string.costs_legend_fuel, money(thisMonth.fuelBaht)),
                AutoBridgeDesign.ACCENT_SYSTEM to getString(R.string.costs_legend_other, money(thisMonth.otherBaht))
            )))
        }
        average?.let { addView(CarToolViews.text(context, getString(R.string.costs_average_short, money(it.totalBaht)), 13f, AutoBridgeDesign.TEXT_MUTED, top = 8)) }
    }

    /** What it was (a chip for the usual ones, or typed), how much, and the day. */
    private fun showAddDialog() {
        val form = CarToolForm(this, getString(R.string.costs_add_title), accent = accent)
        lateinit var label: EditText
        form.chips(
            getString(R.string.costs_form_what),
            listOf(R.string.costs_quick_toll, R.string.costs_quick_parking, R.string.costs_quick_wash,
                R.string.costs_quick_repair, R.string.costs_quick_insurance, R.string.costs_quick_tax).map(::getString),
            null
        ) { label.setText(it); label.setSelection(it.length) }
        label = form.field(getString(R.string.costs_form_label), CarToolForm.Kind.TEXT, hint = getString(R.string.costs_field_label))
        val baht = form.field(getString(R.string.costs_form_amount), CarToolForm.Kind.DECIMAL, unit = getString(R.string.unit_baht))
        var timeMs = System.currentTimeMillis()
        form.date(getString(R.string.form_label_date), timeMs, allowFuture = false) { timeMs = it }
        form.show(getString(R.string.fuel_save)) {
            val amount = baht.text.toString().trim().replace(",", "").toDoubleOrNull()
            if (amount == null || !ExpenseStore.add(this, label.text.toString(), amount, timeMs)) {
                getString(R.string.costs_invalid)
            } else {
                render()
                null
            }
        }
    }

    private fun confirmDelete(expense: Expense) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.costs_delete_title))
            .setMessage("${expense.label} · ${getString(R.string.costs_baht, money(expense.baht))}")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_delete)) { _, _ ->
                ExpenseStore.delete(this, expense.id)
                render()
            }
            .show()
    }
}
