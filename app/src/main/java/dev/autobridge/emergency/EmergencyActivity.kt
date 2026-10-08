package dev.autobridge.emergency

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
        val publicNumbers = SUGGESTED.map { it.second }.toSet()
        val (lines, mine) = entries.partition { it.value.trim() in publicNumbers }
        val missingLines = SUGGESTED.any { (_, number) -> entries.none { it.value.trim() == number } }

        val body = AutoBridgeDesign.body(this)
        body.stack(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    AutoBridgeDesign.pill(this@EmergencyActivity, getString(R.string.emergency_add), primary = true, accent = accent) { showAddDialog() },
                    LinearLayout.LayoutParams(0, -2, 1f)
                )
                // Once every public line is on the card the button has nothing left to add.
                if (missingLines) addView(
                    AutoBridgeDesign.pill(this@EmergencyActivity, getString(R.string.emergency_add_suggested), accent = accent) { addSuggested() },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) }
                )
            },
            gap = 12
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
        }
        // Your own numbers first (insurance, roadside help, family): they are what the public
        // lines cannot do for you. The public lines follow, each in its service's colour.
        if (mine.isNotEmpty()) {
            body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.emergency_section_mine)), gap = 2)
            mine.forEach { body.stack(entryRow(it, accent), gap = 10) }
        }
        if (lines.isNotEmpty()) {
            body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.emergency_section_lines)), gap = 2)
            lines.forEach { body.stack(entryRow(it, lineColor(it.value.trim())), gap = 10) }
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

    /** Each public line in a colour of its own, so the right one is found at a glance. */
    private fun lineColor(number: String): Int = when (number) {
        "191" -> 0xFF6EA8FF.toInt()  // police
        "1669" -> 0xFFFF7A8A.toInt() // medical
        "1193" -> 0xFF5BD98A.toInt() // highway police
        "199" -> 0xFFFFB35C.toInt()  // fire
        "1155" -> 0xFFB39DFF.toInt() // tourist police
        else -> accent
    }

    /**
     * One line of the card: a coloured edge, the name in large type, and on the right the number
     * itself as the call button (or Copy, for text such as a policy number). Tapping the rest of
     * the line offers delete.
     */
    private fun entryRow(entry: EmergencyEntry, color: Int): View {
        val dial = entry.dialable
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            background = AutoBridgeDesign.tappable(this@EmergencyActivity, AutoBridgeDesign.SURFACE, 18, color)
            setPadding(0, dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { showEntryActions(entry) }

            addView(View(this@EmergencyActivity).apply {
                background = AutoBridgeDesign.surface(this@EmergencyActivity, color, 3, android.graphics.Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(5), dp(44)).apply { marginStart = dp(10); marginEnd = dp(14) })

            addView(LinearLayout(this@EmergencyActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@EmergencyActivity).apply {
                    text = entry.label
                    textSize = 18f
                    setTextColor(AutoBridgeDesign.TEXT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                // A number reads on its button; text (a policy number) is shown here in full.
                if (dial == null) addView(TextView(this@EmergencyActivity).apply {
                    text = entry.value
                    textSize = 15f
                    setTextColor(AutoBridgeDesign.TEXT_MUTED)
                    setTextIsSelectable(true)
                    setPadding(0, dp(2), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))

            addView(TextView(this@EmergencyActivity).apply {
                text = if (dial != null) "☎  ${entry.value.trim()}" else getString(R.string.emergency_copy)
                textSize = if (dial != null) 20f else 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (dial != null) AutoBridgeDesign.INK else color)
                background = if (dial != null) {
                    AutoBridgeDesign.tappable(this@EmergencyActivity, color, 14, AutoBridgeDesign.INK, android.graphics.Color.TRANSPARENT)
                } else {
                    AutoBridgeDesign.tappable(this@EmergencyActivity, AutoBridgeDesign.tint(color, 0.16f), 14, color, AutoBridgeDesign.tint(color, 0.4f))
                }
                setPadding(dp(16), 0, dp(16), 0)
                minWidth = dp(96)
                contentDescription = if (dial != null) "${getString(R.string.emergency_call)} ${entry.label} ${entry.value}" else getString(R.string.emergency_copy)
                isClickable = true
                setOnClickListener { if (dial != null) dialNumber(dial) else copy(entry) }
            }, LinearLayout.LayoutParams(-2, dp(52)))
        }
    }

    private fun dialNumber(number: String) {
        // The dialer opens with the number filled in; the driver presses call.
        val opened = runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }.isSuccess
        if (!opened) Toast.makeText(this, getString(R.string.emergency_cannot_dial), Toast.LENGTH_SHORT).show()
    }

    private fun copy(entry: EmergencyEntry) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(entry.label, entry.value))
        Toast.makeText(this, getString(R.string.emergency_copied), Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
                    dialNumber(dial)
                } else {
                    EmergencyStore.delete(this, entry.id)
                    render()
                }
            }
            .show()
    }
}
