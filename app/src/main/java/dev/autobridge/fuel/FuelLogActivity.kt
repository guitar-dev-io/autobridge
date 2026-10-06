package dev.autobridge.fuel

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
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
        val entries = FuelLogStore.all(this)
        val summary = FuelStats.summarize(entries)
        val body = AutoBridgeDesign.body(this)

        body.stack(summaryCard(summary), gap = 12)
        body.stack(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    AutoBridgeDesign.pill(this@FuelLogActivity, getString(R.string.fuel_add), primary = true, accent = accent) { showAddDialog() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
                )
                addView(
                    AutoBridgeDesign.pill(this@FuelLogActivity, getString(R.string.fuel_export), accent = accent) { exportCsv(entries) },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
            },
            gap = 20
        )

        if (entries.isEmpty()) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.fuel_empty_title),
                    message = getString(R.string.fuel_empty_message),
                    accent = accent
                )
            )
        } else {
            body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.fuel_history)), gap = 8)
            entries.forEach { entry ->
                val kmPerL = FuelStats.kmPerLiterOf(entry, entries)
                body.stack(
                    AutoBridgeDesign.contentRow(
                        context = this,
                        title = getString(R.string.fuel_row_title, decimal(entry.liters), money(entry.totalBaht)),
                        subtitle = listOfNotNull(
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.timeMs)),
                            entry.odometerKm?.let { getString(R.string.fuel_row_odometer, whole(it)) },
                            kmPerL?.let { getString(R.string.fuel_km_per_liter_value, decimal(it)) },
                            entry.station.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        accent = accent,
                        badgeText = "⛽",
                    ) { confirmDelete(entry) }
                )
            }
        }

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.fuel_title),
                    subtitle = getString(R.string.fuel_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun summaryCard(summary: FuelSummary): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AutoBridgeDesign.surface(this@FuelLogActivity, AutoBridgeDesign.SURFACE, 20)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(TextView(this@FuelLogActivity).apply {
            text = summary.averageKmPerLiter?.let { getString(R.string.fuel_km_per_liter_value, decimal(it)) }
                ?: getString(R.string.fuel_average_pending)
            textSize = 28f
            setTextColor(AutoBridgeDesign.TEXT)
        })
        addView(TextView(this@FuelLogActivity).apply {
            text = getString(R.string.fuel_average_label)
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
        })
        val lines = listOfNotNull(
            summary.lastKmPerLiter?.let { getString(R.string.fuel_last_interval, decimal(it)) },
            summary.bahtPerKm?.let { getString(R.string.fuel_baht_per_km, decimal(it)) },
            getString(R.string.fuel_this_month, money(summary.thisMonthBaht)),
            getString(R.string.fuel_totals, summary.fillUps, decimal(summary.totalLiters), money(summary.totalBaht)),
            CarVehicleData.fuelPercent?.let { getString(R.string.fuel_car_level, it.roundToLong()) },
        )
        lines.forEach { line ->
            addView(TextView(this@FuelLogActivity).apply {
                text = line
                textSize = 14f
                setTextColor(AutoBridgeDesign.TEXT)
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun showAddDialog() {
        val carOdometer = CarVehicleData.lastOdometer(this)
            ?.takeIf { System.currentTimeMillis() - it.second < ODOMETER_FRESH_MS }?.first
        fun field(hint: Int, decimal: Boolean, initial: String = "") = EditText(this).apply {
            this.hint = getString(hint)
            inputType = if (decimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }
            setText(initial)
        }
        val liters = field(R.string.fuel_field_liters, decimal = true)
        val baht = field(R.string.fuel_field_baht, decimal = true)
        val odometer = field(
            R.string.fuel_field_odometer, decimal = true, initial = carOdometer?.let { whole(it).replace(",", "") }.orEmpty()
        )
        val station = field(R.string.fuel_field_station, decimal = false)
        val note = TextView(this).apply {
            text = getString(if (carOdometer != null) R.string.fuel_odometer_from_car else R.string.fuel_odometer_type_it)
            textSize = 12f
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            listOf(liters, baht, odometer, note, station).forEach { addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fuel_add_title))
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_save)) { _, _ ->
                val l = number(liters)
                val b = number(baht)
                if (l == null || l <= 0 || b == null || b < 0) {
                    Toast.makeText(this, getString(R.string.fuel_invalid), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                FuelLogStore.add(this, l, b, number(odometer), station.text.toString())
                render()
            }
            .show()
    }

    private fun confirmDelete(entry: FuelEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fuel_delete_title))
            .setMessage(getString(R.string.fuel_row_title, decimal(entry.liters), money(entry.totalBaht)))
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
        val file = File(dir, "fuel-log.csv").apply { writeText(FuelStats.csv(entries)) }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.fuel_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.fuel_export))) }
    }

    private fun number(field: EditText): Double? =
        field.text.toString().trim().replace(",", "").toDoubleOrNull()

    private fun decimal(value: Double): String =
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; minimumFractionDigits = 1 }.format(value)

    private fun whole(value: Double): String = NumberFormat.getIntegerInstance().format(value.roundToLong())

    private fun money(value: Double): String =
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }.format(value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
