package dev.autobridge.fuel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.CarToolForm
import dev.autobridge.ui.CarToolViews
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToLong

/**
 * The fuel log on the phone: what the fill-ups add up to, the list of them, adding one, and an
 * export for a spreadsheet. Entering a fill-up is a phone job on purpose — the car shows only the
 * summary ([dev.autobridge.car.CarFuelScreen]), since typing is locked on the car while driving.
 */
class FuelLogActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, FuelLogActivity::class.java)

        /** A car odometer reading older than this is not offered as "now" when logging a fill-up. */
        private const val ODOMETER_FRESH_MS = 6 * 60 * 60 * 1000L

        /** The grades sold in Thailand, offered when logging a fill-up; anything else is typed. */
        private val FUEL_TYPES = listOf(
            R.string.fuel_type_gasohol95,
            R.string.fuel_type_gasohol91,
            R.string.fuel_type_e20,
            R.string.fuel_type_e85,
            R.string.fuel_type_benzine95,
            R.string.fuel_type_diesel,
            R.string.fuel_type_diesel_premium,
            R.string.fuel_type_lpg,
            R.string.fuel_type_ngv,
        )
    }

    private val accent: Int get() = AutoBridgeDesign.ACCENT

    /** An EV counts kWh charged where a petrol or diesel car counts litres; read on each render. */
    private var ev = false

    private fun unit(fuel: Int, electric: Int): String = getString(if (ev) electric else fuel)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System and Auto modes depend on night mode and the clock, which may have moved on.
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        dev.autobridge.settings.ScreenAppearance.applyWindow(this)
        render()
    }

    /**
     * As the design: the three figures, then each fill-up as a dated line with its amount on the
     * right, then Add + Export. Fuel or EV changes rarely, so the current one is the header's
     * subtitle and the other a small pill beside it.
     */
    private fun render() {
        ev = FuelLogStore.isEv(this)
        val entries = FuelLogStore.all(this)
        val summary = FuelStats.summarize(entries)
        val body = AutoBridgeDesign.body(this)

        // With nothing logged the figures would all be zeros; the empty state says it better.
        if (entries.isEmpty()) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = unit(R.string.fuel_empty_title, R.string.fuel_empty_title_ev),
                    message = unit(R.string.fuel_empty_message, R.string.fuel_empty_message_ev),
                    accent = accent
                ),
                gap = 12
            )
        } else {
            body.stack(summaryCard(summary), gap = 12)
            entries.forEach { entry ->
                val kmPerL = FuelStats.kmPerLiterOf(entry, entries)
                body.stack(
                    CarToolViews.row(
                        this,
                        title = getString(
                            if (ev) R.string.fuel_row_line_ev else R.string.fuel_row_line,
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.timeMs)),
                            decimal(entry.liters)
                        ),
                        subtitle = listOfNotNull(
                            entry.odometerKm?.let { getString(R.string.fuel_row_odometer_line, whole(it)) },
                            kmPerL?.let { getString(kmPerUnitRes(), decimal(it)) },
                            entry.fuelType.takeIf { it.isNotBlank() },
                            entry.station.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        amount = getString(R.string.money_baht, money(entry.totalBaht)),
                        accent = accent
                    ) { showEntryActions(entry) }
                )
            }
        }
        body.stack(
            CarToolViews.pillRow(this, listOfNotNull(
                AutoBridgeDesign.pill(this, unit(R.string.fuel_add, R.string.fuel_add_ev), primary = true, accent = accent) { showAddDialog() },
                if (entries.isNotEmpty()) AutoBridgeDesign.pill(this, getString(R.string.fuel_export), accent = accent) { exportCsv(entries) } else null
            )),
            gap = 12
        )
        body.stack(CarToolViews.footnote(this, unit(R.string.fuel_subtitle, R.string.fuel_subtitle_ev)))

        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = CarToolViews.headerChip(
                    this,
                    AutoBridgeDesign.header(
                        context = this,
                        title = unit(R.string.fuel_title, R.string.fuel_title_ev),
                        subtitle = unit(R.string.fuel_mode_fuel, R.string.fuel_mode_ev),
                        onBack = { finish() }
                    ),
                    unit(R.string.fuel_mode_ev_short, R.string.fuel_mode_fuel_short),
                    accent
                ) { setVehicle(!ev) },
                body = body
            )
        )
    }

    private fun setVehicle(electric: Boolean) {
        if (electric == ev) return
        FuelLogStore.setEv(this, electric)
        render()
    }

    private fun rowTitleRes() = if (ev) R.string.fuel_row_title_ev else R.string.fuel_row_title

    private fun kmPerUnitRes() = if (ev) R.string.fuel_km_per_kwh_value else R.string.fuel_km_per_liter_value

    /** Three figures that are used day to day, then the rest as quiet lines; "—" until there is data for one. */
    private fun summaryCard(summary: FuelSummary): View = CarToolViews.card(this) {
        val none = getString(R.string.my_car_no_reading)
        addView(CarToolViews.stats(context, listOf(
            CarToolViews.Stat(
                getString(R.string.fuel_stat_average),
                summary.averageKmPerLiter?.let(::decimal) ?: none,
                summary.averageKmPerLiter?.let { unit(R.string.fuel_stat_km_per_liter, R.string.fuel_stat_km_per_kwh) }
            ),
            CarToolViews.Stat(getString(R.string.fuel_stat_per_km), summary.bahtPerKm?.let { getString(R.string.money_baht, decimal(it)) } ?: none),
            CarToolViews.Stat(getString(R.string.fuel_stat_this_month), getString(R.string.money_baht, money(summary.thisMonthBaht)))
        )))
        val lines = listOfNotNull(
            if (summary.averageKmPerLiter == null) unit(R.string.fuel_average_pending, R.string.fuel_average_pending_ev) else null,
            summary.lastKmPerLiter?.let { getString(if (ev) R.string.fuel_last_interval_ev else R.string.fuel_last_interval, decimal(it)) },
            getString(if (ev) R.string.fuel_totals_ev else R.string.fuel_totals, summary.fillUps, decimal(summary.totalLiters), money(summary.totalBaht)),
            if (ev) {
                CarVehicleData.batteryPercent?.let { getString(R.string.fuel_car_battery, it.roundToLong()) }
            } else {
                CarVehicleData.fuelPercent?.let { getString(R.string.fuel_car_level, it.roundToLong()) }
            },
        )
        lines.forEachIndexed { index, line ->
            addView(CarToolViews.text(context, line, 13f, AutoBridgeDesign.TEXT_MUTED, top = if (index == 0) 12 else 4))
        }
    }

    /**
     * Adds a fill-up or charge, or edits [existing] (any date, so a missed one can be entered
     * later). The grade is a row of chips, the last one used already picked; the price per litre is
     * worked out as the amounts are typed, so a typo shows before it is saved.
     */
    private fun showAddDialog(existing: FuelEntry? = null) {
        val carOdometer = CarVehicleData.lastOdometer(this)
            ?.takeIf { System.currentTimeMillis() - it.second < ODOMETER_FRESH_MS }?.first
        val form = CarToolForm(
            this,
            if (existing != null) unit(R.string.fuel_edit_title, R.string.fuel_edit_title_ev)
            else unit(R.string.fuel_add_title, R.string.fuel_add_title_ev),
            accent = accent
        )
        var timeMs = existing?.timeMs ?: System.currentTimeMillis()
        form.date(getString(R.string.form_label_date), timeMs, allowFuture = false) { timeMs = it }

        // Petrol and diesel cars name the grade; the last one used is offered again.
        var fuelType = existing?.fuelType ?: if (ev) "" else FuelLogStore.lastFuelType(this)
        var otherType: EditText? = null
        if (!ev) {
            val grades = FUEL_TYPES.map { getString(it) }
            val other = getString(R.string.fuel_type_other)
            val known = fuelType.isBlank() || fuelType in grades
            form.chips(getString(R.string.fuel_type_title), grades + other, if (known) fuelType else other) { picked ->
                fuelType = if (picked == other) otherType?.text?.toString().orEmpty().trim() else picked
                otherType?.let { form.setVisible(it, picked == other) }
                if (picked == other) otherType?.requestFocus()
            }
            otherType = form.field(getString(R.string.fuel_type_other_hint), CarToolForm.Kind.NAME, initial = if (known) "" else fuelType)
            form.setVisible(otherType, !known)
        }
        val amount = form.field(
            getString(R.string.fuel_form_amount), CarToolForm.Kind.DECIMAL,
            initial = existing?.liters?.let { plain(it) }.orEmpty(),
            unit = unit(R.string.unit_liters, R.string.unit_kwh)
        )
        val paid = form.field(
            getString(R.string.fuel_form_paid), CarToolForm.Kind.DECIMAL,
            initial = existing?.totalBaht?.let { plain(it) }.orEmpty(),
            unit = getString(R.string.unit_baht)
        )
        val price = form.liveHelper()
        fun refreshPrice() {
            val l = number(amount)
            val b = number(paid)
            price.text = if (l != null && l > 0 && b != null && b > 0) {
                getString(if (ev) R.string.fuel_form_price_per_kwh else R.string.fuel_form_price_per_liter, decimal(b / l))
            } else ""
        }
        listOf(amount, paid).forEach { it.doAfterTextChanged { refreshPrice() } }
        refreshPrice()
        val odometer = form.field(
            getString(R.string.fuel_form_odometer), CarToolForm.Kind.DECIMAL,
            initial = if (existing != null) {
                existing.odometerKm?.let { whole(it).replace(",", "") }.orEmpty()
            } else {
                carOdometer?.let { whole(it).replace(",", "") }.orEmpty()
            },
            unit = getString(R.string.unit_km),
            helper = getString(if (existing == null && carOdometer != null) R.string.fuel_odometer_from_car else R.string.fuel_odometer_type_it)
        )
        val station = form.field(getString(R.string.fuel_field_station), CarToolForm.Kind.NAME, initial = existing?.station.orEmpty())

        form.show(getString(R.string.fuel_save)) {
            val l = number(amount)
            val b = number(paid)
            if (!ev && fuelType == getString(R.string.fuel_type_other)) fuelType = ""
            otherType?.takeIf { it.isShown }?.let { fuelType = it.text.toString().trim() }
            when {
                l == null || l <= 0 || b == null || b < 0 -> unit(R.string.fuel_invalid, R.string.fuel_invalid_ev)
                !ev && fuelType.isBlank() -> getString(R.string.fuel_type_required)
                else -> {
                    if (existing != null) {
                        FuelLogStore.update(this, existing.id, l, b, number(odometer), station.text.toString(), fuelType, timeMs)
                    } else {
                        FuelLogStore.add(this, l, b, number(odometer), station.text.toString(), fuelType, timeMs)
                    }
                    render()
                    null
                }
            }
        }
    }

    /** Tapping a row: edit it (including its date) or delete it. */
    private fun showEntryActions(entry: FuelEntry) {
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(getString(rowTitleRes(), decimal(entry.liters), money(entry.totalBaht)))
            .setItems(arrayOf(getString(R.string.fuel_edit), getString(R.string.fuel_delete))) { _, which ->
                if (which == 0) showAddDialog(entry) else confirmDelete(entry)
            }
            .show()
    }

    private fun confirmDelete(entry: FuelEntry) {
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(unit(R.string.fuel_delete_title, R.string.fuel_delete_title_ev))
            .setMessage(getString(rowTitleRes(), decimal(entry.liters), money(entry.totalBaht)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_delete)) { _, _ ->
                FuelLogStore.delete(this, entry.id)
                render()
            }
            .show()
    }

    /** Shares the log as a CSV file, read access granted to the chosen app only. */
    private fun exportCsv(entries: List<FuelEntry>) {
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.fuel_empty_title), Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "fuel-log.csv").apply { writeText(FuelStats.csv(entries, ev = ev)) }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.fuel_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.fuel_export))) }
    }

    /** A figure as it is typed back into a field: no thousands separators, no needless ".0". */
    private fun plain(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun number(field: EditText): Double? =
        field.text.toString().trim().replace(",", "").toDoubleOrNull()

    private fun decimal(value: Double): String =
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; minimumFractionDigits = 1 }.format(value)

    private fun whole(value: Double): String = NumberFormat.getIntegerInstance().format(value.roundToLong())

    private fun money(value: Double): String =
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }.format(value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
