package dev.autobridge.emergency

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * The emergency card: numbers to ring and facts to quote (the insurance hotline, the policy number,
 * roadside assistance, someone to call). Entered here on the phone; the car shows them and a tap
 * dials ([dev.autobridge.car.CarEmergencyScreen]), since typing is locked on the car.
 *
 * The suggested numbers are Thailand's public emergency lines; they are added only when asked for
 * and are the driver's to change.
 */
class EmergencyActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, EmergencyActivity::class.java)

        /** Label and number of the lines worth having on a card (Thailand). */
        private val SUGGESTED = listOf(
            R.string.emergency_police to "191",
            R.string.emergency_medical to "1669",
            R.string.emergency_highway_police to "1193",
            R.string.emergency_fire to "199",
            R.string.emergency_tourist_police to "1155",
        )
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
        val entries = EmergencyStore.all(this)
        val body = AutoBridgeDesign.body(this)
        body.stack(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    AutoBridgeDesign.pill(this@EmergencyActivity, getString(R.string.emergency_add), primary = true, accent = accent) { showAddDialog() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                )
                addView(
                    AutoBridgeDesign.pill(this@EmergencyActivity, getString(R.string.emergency_add_suggested), accent = accent) { addSuggested() },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
            },
            gap = 20
        )
        if (entries.isEmpty()) {
            body.stack(
                AutoBridgeDesign.emptyState(
                    context = this,
                    title = getString(R.string.emergency_empty_title),
                    message = getString(R.string.emergency_empty_message),
                    accent = accent
                )
            )
        } else {
            entries.forEach { entry ->
                body.stack(
                    AutoBridgeDesign.contentRow(
                        context = this,
                        title = entry.label,
                        subtitle = entry.value,
                        accent = accent,
                        badgeText = if (entry.dialable != null) "📞" else "📄",
                    ) { showEntryActions(entry) }
                )
            }
        }
        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.emergency_title),
                    subtitle = getString(R.string.emergency_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /** Adds the public emergency lines that are not on the card yet. */
    private fun addSuggested() {
        val have = EmergencyStore.all(this).map { it.value }.toSet()
        SUGGESTED.filter { (_, number) -> number !in have }.forEach { (label, number) ->
            EmergencyStore.add(this, getString(label), number)
        }
        render()
    }

    private fun showAddDialog() {
        val label = EditText(this).apply {
            hint = getString(R.string.emergency_field_label)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val value = EditText(this).apply {
            hint = getString(R.string.emergency_field_value)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(value)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.emergency_add))
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.fuel_save)) { _, _ ->
                if (label.text.isBlank() || value.text.isBlank()) {
                    Toast.makeText(this, getString(R.string.emergency_invalid), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                EmergencyStore.add(this, label.text.toString(), value.text.toString())
                render()
            }
            .show()
    }

    /** Tap: ring it (when it is a number) or delete it. */
    private fun showEntryActions(entry: EmergencyEntry) {
        val dial = entry.dialable
        val items = listOfNotNull(dial?.let { getString(R.string.emergency_call) }, getString(R.string.fuel_delete))
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setMessage(entry.value)
            .setItems(items.toTypedArray()) { _, which ->
                if (dial != null && which == 0) {
                    // The dialer opens with the number filled in; the driver presses call.
                    runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dial"))) }
                } else {
                    EmergencyStore.delete(this, entry.id)
                    render()
                }
            }
            .show()
    }
}
