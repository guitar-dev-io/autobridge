package dev.autobridge.maintenance

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.fuel.FuelLogStore
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
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

    private fun render() {
        val items = MaintenanceStore.all(this)
        val odometer = MaintenanceStore.currentOdometer(this)
        val body = AutoBridgeDesign.body(this)

        body.stack(
            TextView(this).apply {
                text = odometer?.let { getString(R.string.maint_odometer_now, whole(it)) } ?: getString(R.string.maint_odometer_unknown)
                textSize = 14f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
            },
            gap = 12
        )
        body.stack(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    AutoBridgeDesign.pill(this@MaintenanceActivity, getString(R.string.maint_add), primary = true, accent = accent) { showAddDialog() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
                )
                addView(
                    AutoBridgeDesign.pill(this@MaintenanceActivity, getString(R.string.maint_add_suggested), accent = accent) { addSuggested() },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
            },
            gap = 20
        )

        if (items.isEmpty()) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.maint_empty_title),
                    message = getString(R.string.maint_empty_message),
                    accent = accent
                )
            )
        } else {
            MaintenanceStats.ordered(items, odometer, System.currentTimeMillis(), kmPerDay = MaintenanceStore.kmPerDay(this)).forEach { status ->
                body.stack(
                    AutoBridgeDesign.contentRow(
                        context = this,
                        title = status.item.name,
                        subtitle = listOfNotNull(
                            MaintenanceText.standing(this, status),
                            MaintenanceText.due(this, status),
                            MaintenanceText.interval(this, status.item),
                        ).joinToString("\n"),
                        accent = accent,
                        badgeText = MaintenanceText.badge(status.state),
                    ) { showItemDialog(status.item, odometer) }
                )
            }
        }

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.maint_title),
                    subtitle = getString(if (FuelLogStore.isEv(this)) R.string.maint_subtitle_ev else R.string.maint_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
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

    private fun showAddDialog() {
        val odometer = MaintenanceStore.currentOdometer(this)
        fun field(hint: Int, numeric: Boolean, initial: String = "") = EditText(this).apply {
            this.hint = getString(hint)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(initial)
        }
        val name = field(R.string.maint_field_name, numeric = false)
        val km = field(R.string.maint_field_km, numeric = true)
        val months = field(R.string.maint_field_months, numeric = true)
        val last = field(R.string.maint_field_last_km, numeric = true, initial = odometer?.let { it.roundToLong().toString() }.orEmpty())
        // A date given outright (road tax runs out on the 12th) instead of counting from the last service.
        var dueDateMs = 0L
        val dateButton = Button(this).apply {
            isAllCaps = false
            fun refresh() {
                text = getString(
                    R.string.maint_due_date_button,
                    if (dueDateMs > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dueDateMs)) else getString(R.string.maint_due_date_none)
                )
            }
            refresh()
            setOnClickListener {
                val now = Calendar.getInstance().apply { if (dueDateMs > 0) timeInMillis = dueDateMs }
                DatePickerDialog(
                    this@MaintenanceActivity,
                    { _, year, month, day ->
                        dueDateMs = Calendar.getInstance().apply { set(year, month, day, 9, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                        refresh()
                    },
                    now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            listOf(name, km, months, last, dateButton).forEach { addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.maint_add))
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_save)) { _, _ ->
                val everyKm = km.text.toString().trim().toIntOrNull() ?: 0
                // A date with no interval repeats yearly, which is what a tax or an insurance does.
                val everyMonths = months.text.toString().trim().toIntOrNull() ?: if (dueDateMs > 0 && everyKm <= 0) 12 else 0
                if (name.text.isBlank() || (everyKm <= 0 && everyMonths <= 0)) {
                    Toast.makeText(this, getString(R.string.maint_invalid), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                MaintenanceStore.add(this, name.text.toString(), everyKm, everyMonths, last.text.toString().trim().toDoubleOrNull(), dueDateMs)
                askForNotifications()
                render()
            }
            .show()
    }

    /** Mark it done at today's odometer, or remove it. */
    private fun showItemDialog(item: MaintenanceItem, odometer: Double?) {
        val reading = EditText(this).apply {
            hint = getString(R.string.fuel_field_odometer)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(odometer?.let { it.roundToLong().toString() }.orEmpty())
        }
        // What it cost, if the driver wants it in the monthly costs.
        val cost = EditText(this).apply {
            hint = getString(R.string.maint_field_cost)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(reading)
            addView(cost)
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(getString(R.string.maint_done_message))
            .setView(form)
            .setNegativeButton(getString(R.string.fuel_delete)) { _, _ ->
                MaintenanceStore.delete(this, item.id)
                render()
            }
            .setPositiveButton(getString(R.string.maint_mark_done)) { _, _ ->
                MaintenanceStore.markDone(this, item.id, reading.text.toString().trim().toDoubleOrNull())
                cost.text.toString().trim().replace(",", "").toDoubleOrNull()?.let {
                    dev.autobridge.expense.ExpenseStore.add(this, item.name, it)
                }
                render()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun whole(value: Double): String = NumberFormat.getIntegerInstance().format(value.roundToLong())

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
