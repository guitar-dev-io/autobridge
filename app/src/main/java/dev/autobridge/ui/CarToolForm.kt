package dev.autobridge.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.autobridge.ui.AutoBridgeDesign.dp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * The add and edit forms of the My Car tools, as a sheet from the bottom in the app's own colours.
 *
 * What the system dialogs it replaces got wrong: a field's only label was its hint, which vanishes
 * on the first keystroke; units were inside the hint ("Every (km), 0 if unused"); dates and fuel
 * grades were grey buttons opening a second dialog; and a mistake closed the form with a toast,
 * losing what was typed. Here every field keeps a label above it and its unit beside it, choices
 * are chips in place, and a mistake is said inside the form, which stays open.
 */
class CarToolForm(
    private val activity: Activity,
    title: String,
    subtitle: String? = null,
    private val accent: Int = AutoBridgeDesign.ACCENT
) {
    enum class Kind { TEXT, NAME, NUMBER, DECIMAL, PHONE }

    private val fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val errorLine = TextView(activity).apply {
        textSize = 14f
        setTextColor(AutoBridgeDesign.DANGER)
        visibility = View.GONE
        setPadding(0, activity.dp(10), 0, 0)
    }
    private val header = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(activity).apply {
            text = title
            textSize = 20f
            setTextColor(AutoBridgeDesign.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        if (!subtitle.isNullOrBlank()) addView(TextView(activity).apply {
            text = subtitle
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            setPadding(0, activity.dp(2), 0, 0)
        })
    }
    private var dialog: Dialog? = null

    /** A labelled input with its [unit] on the right and an optional [helper] line under it. */
    fun field(
        label: String,
        kind: Kind = Kind.TEXT,
        initial: String = "",
        unit: String? = null,
        hint: String? = null,
        helper: String? = null
    ): EditText {
        val input = EditText(activity).apply {
            setText(initial)
            textSize = 17f
            setTextColor(AutoBridgeDesign.TEXT)
            setHintTextColor(AutoBridgeDesign.tint(AutoBridgeDesign.TEXT_MUTED, 0.6f))
            this.hint = hint
            background = null
            setPadding(0, activity.dp(12), 0, activity.dp(12))
            setSingleLine()
            inputType = when (kind) {
                Kind.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                Kind.NAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                Kind.NUMBER -> InputType.TYPE_CLASS_NUMBER
                Kind.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                Kind.PHONE -> InputType.TYPE_CLASS_TEXT
            }
            fontFeatureSettings = "tnum"
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), 0, activity.dp(14), 0)
            background = boxBackground(false)
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            if (unit != null) addView(TextView(activity).apply {
                text = unit
                textSize = 15f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
                setPadding(activity.dp(8), 0, 0, 0)
            })
            setOnClickListener { input.requestFocus() }
        }
        input.setOnFocusChangeListener { _, focused -> box.background = boxBackground(focused) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { errorLine.visibility = View.GONE }
        })
        addLabelled(label, box, helper)
        return input
    }

    /** A line under a field that the caller updates, such as a price per litre worked out as it is typed. */
    fun liveHelper(): TextView = helperText("").also { line ->
        // Takes no room while it has nothing to say.
        line.visibility = View.GONE
        line.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { line.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
        })
        fields.addView(line)
    }

    /**
     * One-tap choices in a row. [onPick] gets the chosen label; the chip stays highlighted until
     * another is picked. [selected] is highlighted at the start.
     */
    fun chips(label: String?, options: List<String>, selected: String?, onPick: (String) -> Unit) {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<TextView>()
        fun paint(chosen: String?) = views.forEach { chip ->
            val on = chip.text.toString() == chosen
            chip.setTextColor(if (on) AutoBridgeDesign.INK else AutoBridgeDesign.TEXT)
            chip.background = AutoBridgeDesign.surface(activity, if (on) accent else AutoBridgeDesign.SURFACE_RAISED, 18, if (on) accent else AutoBridgeDesign.HAIRLINE)
        }
        options.forEach { option ->
            val chip = TextView(activity).apply {
                text = option
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = activity.dp(40)
                setPadding(activity.dp(14), activity.dp(8), activity.dp(14), activity.dp(8))
                setOnClickListener {
                    paint(option)
                    errorLine.visibility = View.GONE
                    onPick(option)
                }
            }
            views += chip
            row.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = activity.dp(8) })
        }
        paint(selected)
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        if (label == null) {
            fields.addView(scroller, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
        } else {
            addLabelled(label, scroller, null)
        }
    }

    /**
     * A date shown as a field; tapping it opens the calendar. [allowFuture] false keeps the picker
     * to today and before (a fill-up or a cost already happened). [onClear], when given, offers to
     * remove the date, and [initialMs] 0 shows [emptyText].
     */
    fun date(
        label: String,
        initialMs: Long,
        allowFuture: Boolean,
        emptyText: String = "",
        helper: String? = null,
        onPick: (Long) -> Unit
    ) {
        var current = initialMs
        val value = TextView(activity).apply {
            textSize = 17f
            setPadding(0, activity.dp(12), 0, activity.dp(12))
        }
        fun refresh() {
            value.text = if (current > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(current)) else emptyText
            value.setTextColor(if (current > 0) AutoBridgeDesign.TEXT else AutoBridgeDesign.tint(AutoBridgeDesign.TEXT_MUTED, 0.6f))
        }
        refresh()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(14), 0, activity.dp(14), 0)
            background = AutoBridgeDesign.tappable(activity, AutoBridgeDesign.SURFACE_RAISED, 12, accent)
            addView(value, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(activity).apply {
                text = activity.getString(dev.autobridge.R.string.form_change)
                textSize = 14f
                setTextColor(accent)
            })
            setOnClickListener {
                val shown = Calendar.getInstance().apply { if (current > 0) timeInMillis = current }
                DatePickerDialog(activity, { _, year, month, day ->
                    // The time of day is kept (now for a new entry), so same-day entries stay in order.
                    current = Calendar.getInstance().apply {
                        timeInMillis = if (current > 0) current else System.currentTimeMillis()
                        set(year, month, day)
                    }.timeInMillis
                    refresh()
                    onPick(current)
                }, shown.get(Calendar.YEAR), shown.get(Calendar.MONTH), shown.get(Calendar.DAY_OF_MONTH)).apply {
                    if (!allowFuture) datePicker.maxDate = System.currentTimeMillis()
                }.show()
            }
        }
        addLabelled(label, box, helper)
    }

    /**
     * A list to pick one from, each a full-width row with a radio mark; picking applies at once
     * and closes the sheet, so it is shown with [showChoices] (no Save button).
     */
    fun choices(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            fields.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = activity.dp(56)
                setPadding(activity.dp(16), 0, activity.dp(16), 0)
                background = AutoBridgeDesign.tappable(
                    activity,
                    if (on) AutoBridgeDesign.tint(accent, 0.14f) else AutoBridgeDesign.SURFACE_RAISED,
                    14, accent,
                    if (on) accent else Color.TRANSPARENT
                )
                addView(TextView(activity).apply {
                    text = label
                    textSize = 16f
                    setTextColor(AutoBridgeDesign.TEXT)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(View(activity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        if (on) setColor(accent) else setColor(Color.TRANSPARENT)
                        setStroke(activity.dp(2), if (on) accent else AutoBridgeDesign.TEXT_MUTED)
                    }
                }, LinearLayout.LayoutParams(activity.dp(20), activity.dp(20)))
                setOnClickListener {
                    onPick(index)
                    dialog?.dismiss()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
        }
    }

    /** Shows a sheet of [choices]: no Save, the pick is the action; Back or a tap outside cancels. */
    fun showChoices() = build(null, null, null)

    /** A plain sentence in the form, for what a section means. */
    fun note(text: String) {
        fields.addView(helperText(text), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
    }

    /** Hides or shows a field added earlier, with its label and helper. */
    fun setVisible(input: View, visible: Boolean) {
        var view: View = input
        while (view.parent != null && view.parent != fields) view = view.parent as View
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Shows the sheet. [onSave] returns null to close, or the sentence to show when something is
     * missing. [danger], when given, is a quiet red action at the bottom (delete).
     */
    fun show(saveLabel: String, danger: Pair<String, () -> Unit>? = null, onSave: () -> String?) =
        build(saveLabel, danger, onSave)

    private fun build(saveLabel: String?, danger: Pair<String, () -> Unit>?, onSave: (() -> String?)?) {
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(16))
            background = AutoBridgeDesign.surface(activity, AutoBridgeDesign.SURFACE, 24, Color.TRANSPARENT).apply {
                val r = activity.dp(24).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        // The grab handle says "this is a sheet"; it is not draggable.
        sheet.addView(View(activity).apply {
            background = AutoBridgeDesign.surface(activity, AutoBridgeDesign.HAIRLINE, 3, Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(activity.dp(40), activity.dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = activity.dp(14)
        })
        sheet.addView(header)
        sheet.addView(errorLine)
        sheet.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(fields)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        val d = Dialog(activity)
        if (saveLabel != null && onSave != null) {
            val save = AutoBridgeDesign.pill(activity, saveLabel, primary = true, accent = accent) {
                val problem = onSave()
                if (problem == null) {
                    d.dismiss()
                } else {
                    errorLine.text = problem
                    errorLine.visibility = View.VISIBLE
                }
            }.apply { (this as TextView).textSize = 16f; minimumHeight = activity.dp(52) }
            val cancel = AutoBridgeDesign.pill(activity, activity.getString(android.R.string.cancel), accent = accent) { d.dismiss() }
                .apply { (this as TextView).textSize = 16f; minimumHeight = activity.dp(52) }
            sheet.addView(CarToolViews.pillRow(activity, listOf(cancel, save)).apply {
                // Save gets the wider side: it is the button the form is for.
                (getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight = 2f
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(18) })
        }
        if (danger != null) sheet.addView(TextView(activity).apply {
            text = danger.first
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.DANGER)
            minHeight = activity.dp(48)
            setPadding(0, activity.dp(12), 0, 0)
            setOnClickListener {
                d.dismiss()
                danger.second()
            }
        }, LinearLayout.LayoutParams(-1, -2))

        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(sheet)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setDimAmount(0.6f)
        }
        dialog = d
        d.show()
    }

    private fun addLabelled(label: String, view: View, helper: String?) {
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = label
                textSize = 13f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(activity.dp(2), 0, 0, activity.dp(6))
            })
            addView(view, LinearLayout.LayoutParams(-1, -2))
            if (helper != null) addView(helperText(helper))
        }
        fields.addView(group, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(16) })
    }

    private fun helperText(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(AutoBridgeDesign.TEXT_MUTED)
        setPadding(activity.dp(2), activity.dp(6), 0, 0)
    }

    private fun boxBackground(focused: Boolean) =
        AutoBridgeDesign.surface(activity, AutoBridgeDesign.SURFACE_RAISED, 12, if (focused) accent else AutoBridgeDesign.HAIRLINE)
}
