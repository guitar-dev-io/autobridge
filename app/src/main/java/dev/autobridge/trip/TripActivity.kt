package dev.autobridge.trip

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.fuel.FuelLogStore
import dev.autobridge.fuel.FuelStats
import dev.autobridge.i18n.AppLocale
import dev.autobridge.maintenance.MaintenanceStore
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

/**
 * Trips: start one with the number of people, end it, and see what it cost per person — fuel (or
 * charging) from the log's cost per km, plus tolls and parking typed in at the end. Distance comes
 * from the odometer, the car's or the last one logged; with neither, it is typed at the end.
 */
class TripActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, TripActivity::class.java)

        /** Current fuel or charging cost per km, from the log; null until it can be worked out. */
        fun currentBahtPerKm(context: Context): Double? = FuelStats.summarize(FuelLogStore.all(context)).bahtPerKm
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
        val trips = TripStore.all(this)
        val active = trips.firstOrNull { it.active }
        val odometer = MaintenanceStore.currentOdometer(this)
        val now = System.currentTimeMillis()
        val body = AutoBridgeDesign.body(this)

        if (active != null) {
            body.stack(activeCard(active, odometer, now), gap = 12)
            body.stack(
                AutoBridgeDesign.pill(this, getString(R.string.trip_end), primary = true, accent = accent) { showEndDialog(active, odometer) },
                gap = 20
            )
        } else {
            body.stack(
                AutoBridgeDesign.pill(this, getString(R.string.trip_start), primary = true, accent = accent) { showStartDialog(odometer) },
                gap = 20
            )
        }

        val done = trips.filterNot { it.active }
        if (done.isEmpty() && active == null) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.trip_empty_title),
                    message = getString(R.string.trip_empty_message),
                    accent = accent
                )
            )
        } else if (done.isNotEmpty()) {
            body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.trip_history)), gap = 8)
            done.forEach { trip ->
                body.stack(
                    AutoBridgeDesign.contentRow(
                        context = this,
                        title = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(trip.startMs)) + " · " +
                            getString(R.string.trip_people, trip.people),
                        subtitle = listOfNotNull(TripText.summary(this, trip, null, now), TripText.cost(this, trip, null)).joinToString("\n"),
                        accent = accent,
                        badgeText = "🧳",
                    ) { confirmDelete(trip) }
                )
            }
        }

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.trip_title),
                    subtitle = getString(R.string.trip_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun activeCard(trip: Trip, odometer: Double?, now: Long) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AutoBridgeDesign.surface(this@TripActivity, AutoBridgeDesign.SURFACE, 20)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(TextView(this@TripActivity).apply {
            text = getString(R.string.trip_in_progress, trip.people)
            textSize = 18f
            setTextColor(AutoBridgeDesign.TEXT)
        })
        listOfNotNull(
            TripText.summary(this@TripActivity, trip, odometer, now),
            TripText.cost(this@TripActivity, trip, odometer, currentBahtPerKm(this@TripActivity)),
        ).forEach { line ->
            addView(TextView(this@TripActivity).apply {
                text = line
                textSize = 14f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun showStartDialog(odometer: Double?) {
        val people = EditText(this).apply {
            hint = getString(R.string.trip_field_people)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(TripStore.lastPeople(this@TripActivity).toString())
        }
        val start = EditText(this).apply {
            hint = getString(R.string.fuel_field_odometer)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(odometer?.let { it.roundToLong().toString() }.orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trip_start))
            .setView(form(people, start))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.trip_start)) { _, _ ->
                TripStore.start(this, start.text.toString().trim().toDoubleOrNull(), people.text.toString().trim().toIntOrNull() ?: 1)
                render()
            }
            .show()
    }

    private fun showEndDialog(trip: Trip, odometer: Double?) {
        val end = EditText(this).apply {
            hint = getString(R.string.fuel_field_odometer)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(odometer?.let { it.roundToLong().toString() }.orEmpty())
        }
        val other = EditText(this).apply {
            hint = getString(R.string.trip_field_other)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trip_end))
            .setView(form(end, other))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_save)) { _, _ ->
                val endKm = end.text.toString().trim().toDoubleOrNull()
                if (trip.startKm != null && (endKm == null || endKm <= trip.startKm)) {
                    Toast.makeText(this, getString(R.string.trip_invalid_end), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                TripStore.finish(this, trip.id, endKm, other.text.toString().trim().toDoubleOrNull() ?: 0.0, currentBahtPerKm(this))
                render()
            }
            .show()
    }

    private fun confirmDelete(trip: Trip) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trip_delete_title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_delete)) { _, _ ->
                TripStore.delete(this, trip.id)
                render()
            }
            .show()
    }

    private fun form(vararg fields: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        fields.forEach { addView(it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
