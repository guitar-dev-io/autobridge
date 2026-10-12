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
import dev.autobridge.ui.CarToolForm
import dev.autobridge.ui.CarToolViews

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
        // The numbers come first: someone opening this page in a hurry needs a number, not the add button.
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
     * One line of the card, as in the design: the name, and under it the number or the text with
     * what to tell whoever answers; on the right a round 56 dp button that calls (or copies text
     * such as a policy number). The colour of each public line is only in its button, lightly, so
     * the lines are told apart without the page shouting. Tapping the rest of the line edits it.
     */
    private fun entryRow(entry: EmergencyEntry, color: Int): View {
        val dial = entry.dialable
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            background = AutoBridgeDesign.tappable(this@EmergencyActivity, AutoBridgeDesign.SURFACE, 16, color, android.graphics.Color.TRANSPARENT)
            setPadding(dp(16), dp(10), dp(10), dp(10))
            isClickable = true
            setOnClickListener { showEntryActions(entry) }

            addView(LinearLayout(this@EmergencyActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@EmergencyActivity).apply {
                    text = entry.label
                    textSize = 17f
                    setTextColor(AutoBridgeDesign.TEXT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(this@EmergencyActivity).apply {
                    text = listOf(entry.value.trim(), entry.detail).filter { it.isNotBlank() }.joinToString(" · ")
                    textSize = 14f
                    setTextColor(AutoBridgeDesign.TEXT_MUTED)
                    fontFeatureSettings = "tnum"
                    setPadding(0, dp(2), dp(8), 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))

            addView(
                if (dial != null) {
                    CarToolViews.roundButton(this@EmergencyActivity, R.drawable.ic_car_emergency, color,
                        "${getString(R.string.emergency_call)} ${entry.label} ${entry.value}") { dialNumber(dial) }
                } else {
                    CarToolViews.roundButton(this@EmergencyActivity, R.drawable.ic_copy, color,
                        "${getString(R.string.emergency_copy)} ${entry.label}") { copy(entry) }
                }
            )
        }
    }

    private fun dialNumber(number: String) {
        // The dialer opens with the number filled in; the driver presses call.
        val opened = runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }.isSuccess
        if (!opened) Toast.makeText(this, getString(R.string.emergency_cannot_dial), Toast.LENGTH_SHORT).show()
    }

    private fun copy(entry: EmergencyEntry) =
        copyText(entry.label, listOf(entry.value.trim(), entry.detail).filter { it.isNotBlank() }.joinToString(" · "))

    private fun copyText(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
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

    private fun showAddDialog() = showEntryForm(null)

    /**
     * Adds an entry, or edits [existing]. The usual kinds are chips that fill the name; the third
     * field holds what to tell whoever answers, so an insurer is one entry with its claim line
     * and its policy number.
     */
    private fun showEntryForm(existing: EmergencyEntry?) {
        val form = CarToolForm(this, getString(if (existing == null) R.string.emergency_add_title else R.string.emergency_edit_title), accent = accent)
        lateinit var label: EditText
        if (existing == null) {
            form.chips(
                null,
                listOf(R.string.emergency_kind_insurance, R.string.emergency_kind_roadside, R.string.emergency_kind_family).map(::getString),
                null
            ) { label.setText(it); label.setSelection(it.length) }
        }
        label = form.field(getString(R.string.emergency_form_name), CarToolForm.Kind.NAME, initial = existing?.label.orEmpty(), hint = getString(R.string.emergency_form_name_hint))
        val value = form.field(
            getString(R.string.emergency_form_value), CarToolForm.Kind.PHONE,
            initial = existing?.value.orEmpty(),
            hint = getString(R.string.emergency_form_value_hint),
            helper = getString(R.string.emergency_form_value_helper)
        )
        val detail = form.field(
            getString(R.string.emergency_form_detail), CarToolForm.Kind.TEXT,
            initial = existing?.detail.orEmpty(),
            hint = getString(R.string.emergency_form_detail_hint)
        )
        val delete = existing?.let { entry ->
            getString(R.string.emergency_form_delete) to {
                EmergencyStore.delete(this, entry.id)
                render()
            }
        }
        form.show(getString(R.string.fuel_save), danger = delete) {
            if (label.text.isBlank() || value.text.isBlank()) {
                getString(R.string.emergency_invalid)
            } else {
                if (existing == null) {
                    EmergencyStore.add(this, label.text.toString(), value.text.toString(), detail.text.toString())
                } else {
                    EmergencyStore.update(this, existing.id, label.text.toString(), value.text.toString(), detail.text.toString())
                }
                render()
                null
            }
        }
    }

    /** Tap: edit it, or remove it. Calling and copying are the button on the row itself. */
    private fun showEntryActions(entry: EmergencyEntry) = showEntryForm(entry)
}
