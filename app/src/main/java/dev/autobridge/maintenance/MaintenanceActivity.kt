package dev.autobridge.maintenance

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.fuel.FuelLogStore
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.CarToolForm
import dev.autobridge.ui.CarToolViews
import dev.autobridge.mycar.MyCarFacts
import java.text.NumberFormat
import kotlin.math.roundToLong

/**
 * What the car is due for — oil, tyres, road tax, or for an EV the coolant and the 12 V battery —
 * counted from the odometer and the calendar. Setting items up is a phone job; the car shows only
 * what is due ([dev.autobridge.car.CarMaintenanceScreen]).
 */
class MaintenanceActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, MaintenanceActivity::class.java)

        private const val REQUEST_NOTIFICATIONS = 4103
    }

    private val accent = AutoBridgeDesign.ACCENT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    /** Most urgent first, each with a bar for how much of its interval is gone; adding comes after the list. */
    private fun render() {
        val items = MaintenanceStore.all(this)
        val odometer = MaintenanceStore.currentOdometer(this)
        val body = AutoBridgeDesign.body(this)

        if (items.isEmpty()) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.maint_empty_title),
                    message = getString(R.string.maint_empty_message),
                    accent = accent
                ),
                gap = 12
            )
        } else {
            MaintenanceStats.ordered(items, odometer, System.currentTimeMillis(), kmPerDay = MaintenanceStore.kmPerDay(this)).forEach { status ->
                body.stack(itemCard(status, odometer))
            }
        }
        body.stack(
            CarToolViews.pillRow(this, listOf(
                AutoBridgeDesign.pill(this, getString(R.string.maint_add), primary = true, accent = accent) { showAddDialog() },
                AutoBridgeDesign.pill(this, getString(R.string.maint_add_suggested), accent = accent) { addSuggested() }
            )),
            gap = 8
        )

        if (odometer == null) body.stack(CarToolViews.footnote(this, getString(R.string.maint_odometer_unknown)), gap = 2)

        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.maint_title),
                    subtitle = odometer?.let { getString(R.string.maint_odometer_now, whole(it)) } ?: getString(R.string.maint_odometer_unknown_short),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /** Name, where it stands in the due colour, the bar when there is a scale, then the due date and interval. */
    private fun itemCard(status: MaintenanceStatus, odometer: Double?) = CarToolViews.card(this) {
        val tone = when (MyCarFacts.maintenanceTone(status)) {
            MyCarFacts.Tone.ALERT -> AutoBridgeDesign.DANGER
            MyCarFacts.Tone.WARN -> AutoBridgeDesign.ACCENT_RADIO
            else -> AutoBridgeDesign.ACCENT_FILES
        }
        val measured = status.kmLeft != null || status.daysLeft != null
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(CarToolViews.text(context, status.item.name, 15f, bold = true).apply { setPadding(0, 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            // Unmeasured, the long "odometer unknown" sentence would squeeze the name out; the
            // header and the footnote already say why, so the row only says what it waits for.
            addView(CarToolViews.text(
                context,
                if (measured) MaintenanceText.standing(context, status) else getString(R.string.maint_needs_odometer),
                13f,
                if (measured) tone else AutoBridgeDesign.TEXT_MUTED,
                bold = measured
            ).apply { gravity = android.view.Gravity.END; maxLines = 1 })
        })
        MyCarFacts.intervalUsed(status)?.let { addView(CarToolViews.bar(context, it, tone)) }
        // The interval only, as in the design: "in 364 days" above already says when.
        MaintenanceText.interval(context, status.item).takeIf { it.isNotBlank() }?.let {
            addView(CarToolViews.text(context, it, 12f, AutoBridgeDesign.TEXT_MUTED, top = 8))
        }
        background = AutoBridgeDesign.tappable(context, AutoBridgeDesign.SURFACE, 16, accent, android.graphics.Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { showItemDialog(status.item, odometer) }
    }

    /** Offers the usual list for this vehicle (fuel or EV), skipping names already on the list. */
    private fun addSuggested() {
        val have = MaintenanceStore.all(this).map { it.name }.toSet()
        val odometer = MaintenanceStore.currentOdometer(this)
        var added = 0
        MaintenanceStats.presets(FuelLogStore.isEv(this)).forEach { preset ->
            val name = getString(MaintenanceText.presetName(preset.key))
            if (name in have) return@forEach
            MaintenanceStore.add(this, name, preset.intervalKm, preset.intervalMonths, odometer)
            added++
        }
        if (added == 0) Toast.makeText(this, getString(R.string.maint_nothing_to_add), Toast.LENGTH_SHORT).show() else askForNotifications()
        render()
    }

    /** Reminders are notifications, so the first time there is something to remind of, ask to allow them. */
    private fun askForNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    /**
     * A new item: tap a usual one to fill in its name and intervals, or type your own. The two
     * intervals sit side by side with their units, and a date is only for things that end on one.
     */
    private fun showAddDialog() {
        val odometer = MaintenanceStore.currentOdometer(this)
        val form = CarToolForm(this, getString(R.string.maint_add_title), accent = accent)
        lateinit var name: EditText
        lateinit var km: EditText
        lateinit var months: EditText
        val presets = MaintenanceStats.presets(FuelLogStore.isEv(this))
        val have = MaintenanceStore.all(this).map { it.name }.toSet()
        val offered = presets.filter { getString(MaintenanceText.presetName(it.key)) !in have }
        if (offered.isNotEmpty()) {
            form.chips(getString(R.string.maint_form_presets), offered.map { getString(MaintenanceText.presetName(it.key)) }, null) { picked ->
                val preset = offered.first { getString(MaintenanceText.presetName(it.key)) == picked }
                name.setText(picked)
                km.setText(preset.intervalKm.takeIf { it > 0 }?.toString().orEmpty())
                months.setText(preset.intervalMonths.takeIf { it > 0 }?.toString().orEmpty())
            }
        }
        name = form.field(getString(R.string.maint_form_name), CarToolForm.Kind.NAME)
        km = form.field(getString(R.string.maint_form_every_km), CarToolForm.Kind.NUMBER, unit = getString(R.string.unit_km))
        months = form.field(
            getString(R.string.maint_form_every_months), CarToolForm.Kind.NUMBER,
            unit = getString(R.string.unit_months),
            helper = getString(R.string.maint_form_interval_helper)
        )
        val last = form.field(
            getString(R.string.maint_form_last_km), CarToolForm.Kind.NUMBER,
            initial = odometer?.let { it.roundToLong().toString() }.orEmpty(),
            unit = getString(R.string.unit_km)
        )
        // A date given outright (road tax runs out on the 12th) instead of counting from the last service.
        var dueDateMs = 0L
        form.date(
            getString(R.string.maint_form_due), 0L, allowFuture = true,
            emptyText = getString(R.string.maint_due_date_none),
            helper = getString(R.string.maint_form_due_helper)
        ) { dueDateMs = it }
        form.show(getString(R.string.fuel_save)) {
            val everyKm = km.text.toString().trim().toIntOrNull() ?: 0
            // A date with no interval repeats yearly, which is what a tax or an insurance does.
            val everyMonths = months.text.toString().trim().toIntOrNull() ?: if (dueDateMs > 0 && everyKm <= 0) 12 else 0
            if (name.text.isBlank() || (everyKm <= 0 && everyMonths <= 0)) {
                getString(R.string.maint_invalid)
            } else {
                MaintenanceStore.add(this, name.text.toString(), everyKm, everyMonths, last.text.toString().trim().toDoubleOrNull(), dueDateMs)
                askForNotifications()
                render()
                null
            }
        }
    }

    /** Mark it done at today's odometer (and, if wanted, what it cost), or remove it. */
    private fun showItemDialog(item: MaintenanceItem, odometer: Double?) {
        val form = CarToolForm(this, item.name, MaintenanceText.interval(this, item).ifBlank { null }, accent = accent)
        val reading = form.field(
            getString(R.string.maint_form_done_odometer), CarToolForm.Kind.NUMBER,
            initial = odometer?.let { it.roundToLong().toString() }.orEmpty(),
            unit = getString(R.string.unit_km)
        )
        // What it cost, if the driver wants it in the monthly costs.
        val cost = form.field(
            getString(R.string.maint_form_done_cost), CarToolForm.Kind.DECIMAL,
            unit = getString(R.string.unit_baht),
            helper = getString(R.string.maint_form_done_cost_helper)
        )
        form.show(getString(R.string.maint_mark_done), danger = getString(R.string.maint_form_delete) to {
            MaintenanceStore.delete(this, item.id)
            render()
        }) {
            MaintenanceStore.markDone(this, item.id, reading.text.toString().trim().toDoubleOrNull())
            cost.text.toString().trim().replace(",", "").toDoubleOrNull()?.let {
                dev.autobridge.expense.ExpenseStore.add(this, item.name, it)
            }
            render()
            null
        }
    }

    private fun whole(value: Double): String = NumberFormat.getIntegerInstance().format(value.roundToLong())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
