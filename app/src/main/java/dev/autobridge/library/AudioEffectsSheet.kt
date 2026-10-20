package dev.autobridge.library

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.browser.CappedScrollView
import dev.autobridge.browser.CarMenuList
import dev.autobridge.design.DesignTokens
import dev.autobridge.media.AudioEffectsService
import dev.autobridge.media.AudioEq
import dev.autobridge.media.EffectScope
import dev.autobridge.media.EqMath
import dev.autobridge.media.EqPreset
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp

/**
 * The sound effects, as a sheet from the bottom of the screen — the radio player, the video
 * player, or Settings: an on switch, the one-tap presets, five bands to set by hand, bass boost,
 * virtualizer, volume boost, and which sound it all reaches ([EffectScope]).
 *
 * Drawn after the radio design's EQ sheet: amber chips and controls on a dark sheet, the five
 * bands as vertical sliders side by side (dB above, Hz below), and "same loudness on every
 * station" as a checkbox. Virtualizer, volume boost and the scope stay below, as before.
 *
 * Every change goes straight to [AudioEq], which stores it and applies it to the playing sessions,
 * so what the driver hears follows the finger. A preset tap redraws the sheet (the chips and the
 * sliders both show it); a slider only updates its own number while it moves.
 */
class AudioEffectsSheet(private val context: Context) {
    private val dialog = Dialog(context)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(28))
        background = GradientDrawable().apply {
            setColor(SHEET)
            val r = context.dp(28).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(CappedScrollView(context).apply { addView(content) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        render()
        dialog.show()
    }

    private fun render() {
        content.removeAllViews()
        val settings = AudioEq.settings(context)

        // Grab handle, then the title with the on switch. Drag the handle down to dismiss.
        val grabHandle = View(context).apply {
            background = AutoBridgeDesign.surface(context, HANDLE, 3, Color.TRANSPARENT)
        }
        content.addView(grabHandle, LinearLayout.LayoutParams(context.dp(40), context.dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = context.dp(14)
        })
        val titleRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(context).apply {
            text = context.getString(R.string.eq_title)
            textSize = 22f
            setTextColor(AutoBridgeDesign.TEXT)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(Switch(context).apply {
            text = context.getString(R.string.eq_enabled)
            textSize = 14f
            setTextColor(SOFT)
            switchPadding = context.dp(8)
            thumbTintList = states(AMBER, KNOB_OFF)
            trackTintList = states(AMBER_TRACK, TRACK_OFF)
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, on ->
                AudioEq.setEnabled(context, on)
                render()
            }
        })
        content.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(18) })

        // Presets, three to a row.
        content.addView(label(context.getString(R.string.eq_presets)))
        val chips = EqPreset.chips.map { preset ->
            chip(context.getString(preset.labelRes), settings.enabled && settings.preset == preset) {
                AudioEq.setPreset(context, preset)
                render()
            }
        }
        AutoBridgeDesign.grid(context, chips, columns = 3).forEach { row ->
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
        }

        // The five bands by hand: vertical sliders side by side.
        val customHeader = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(10), 0, 0)
        }
        customHeader.addView(label(context.getString(R.string.eq_custom_section)).apply {
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        customHeader.addView(TextView(context).apply {
            text = context.getString(R.string.eq_reset)
            textSize = 14f
            setTextColor(AMBER)
            setPadding(context.dp(8), context.dp(8), 0, context.dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AudioEq.resetCustom(context)
                render()
            }
        })
        content.addView(customHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(6) })
        val bands = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(context.dp(8), context.dp(16), context.dp(8), context.dp(12))
            background = AutoBridgeDesign.surface(context, PANEL, 20, Color.TRANSPARENT)
        }
        val gains = settings.gains
        EqMath.REFERENCE_HZ.forEachIndexed { band, hz ->
            bands.addView(bandColumn(band, hz, gains.getOrElse(band) { 0 }), LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(bands, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(10) })

        // Bass boost, virtualizer and volume boost: one slider each, off at the left.
        content.addView(strengthRow(context.getString(R.string.eq_bass), settings.bass, AudioEq.BASS_MAX,
            { "${it / 10}%" }) { AudioEq.setBass(context, it) })
        // Same loudness on every station: the label on the left, the box on the right.
        val normalizeRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(4), 0, 0)
        }
        val normalize = CheckBox(context).apply {
            buttonTintList = states(AMBER, OUTLINE)
            isChecked = settings.normalize
            contentDescription = context.getString(R.string.eq_normalize)
            setOnCheckedChangeListener { _, on ->
                AudioEq.setNormalize(context, on)
                render()
            }
        }
        normalizeRow.addView(TextView(context).apply {
            text = context.getString(R.string.eq_normalize)
            textSize = 15f
            setTextColor(AutoBridgeDesign.TEXT)
            setOnClickListener { normalize.toggle() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        normalizeRow.addView(normalize)
        content.addView(normalizeRow, LinearLayout.LayoutParams(-1, context.dp(52)))
        content.addView(strengthRow(context.getString(R.string.eq_virtualizer), settings.virtualizer, AudioEq.VIRTUALIZER_MAX,
            { "${it / 10}%" }) { AudioEq.setVirtualizer(context, it) })
        content.addView(strengthRow(context.getString(R.string.eq_boost), settings.boostMb, AudioEq.BOOST_MAX_MB,
            { context.getString(R.string.eq_band_value, it / 100) }) { AudioEq.setBoost(context, it) })

        // Which sound the effects reach.
        content.addView(label(context.getString(R.string.eq_scope)).apply { setPadding(0, context.dp(18), 0, context.dp(8)) })
        val scopes = listOf(
            EffectScope.APP to R.string.eq_scope_app,
            EffectScope.MUSIC_APPS to R.string.eq_scope_music_apps,
            EffectScope.ALL to R.string.eq_scope_all,
        ).map { (scope, labelRes) ->
            chip(context.getString(labelRes), settings.scope == scope) {
                AudioEq.setScope(context, scope)
                AudioEffectsService.sync(context)
                render()
            }
        }
        AutoBridgeDesign.grid(context, scopes, columns = 3).forEach { row ->
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
        }
        content.addView(TextView(context).apply {
            text = context.getString(when (settings.scope) {
                EffectScope.APP -> R.string.eq_scope_app_caption
                EffectScope.MUSIC_APPS -> R.string.eq_scope_music_apps_caption
                EffectScope.ALL -> R.string.eq_scope_all_caption
            })
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
        })

        // The grab handle is rebuilt on every render(); re-wire the drag on the fresh handle, with
        // the stable content column as the panel. Reused from the browser package (same :app
        // module): its requestDisallowInterceptTouchEvent is what stops the outer ScrollView from
        // stealing the vertical drag. setOnTouchListener just replaces the previous binding.
        CarMenuList.dragToClose(grabHandle, content) { dialog.dismiss() }
    }

    /** The design's chip: amber with dark bold text when on, an outline when off. */
    private fun chip(text: String, on: Boolean, onClick: () -> Unit): View = TextView(context).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(if (on) ON_AMBER else CHIP_TEXT)
        typeface = Typeface.create("sans-serif", if (on) Typeface.BOLD else Typeface.NORMAL)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(14).toFloat()
            if (on) setColor(AMBER) else {
                setColor(Color.TRANSPARENT)
                setStroke((1.5f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1), OUTLINE)
            }
        }
        minHeight = context.dp(44)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A title on the left and a 0..[max] slider on the right, the value under the slider. */
    private fun strengthRow(title: String, current: Int, max: Int, format: (Int) -> String, onChange: (Int) -> Unit): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(8), 0, 0)
        }
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(AutoBridgeDesign.TEXT)
        })
        val value = TextView(context).apply {
            text = format(current)
            textSize = 12f
            setTextColor(WARM_TEXT)
        }
        left.addView(value)
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(SeekBar(context).apply {
            this.max = max
            progress = current
            contentDescription = title
            tintAmber()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, at: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    value.text = format(at)
                    onChange(at)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                // Moving one turns the effects on; redraw so the switch says so.
                override fun onStopTrackingTouch(bar: SeekBar) = render()
            })
        }, LinearLayout.LayoutParams(context.dp(180), -2))
        return row
    }

    /** One band, standing up: its dB on top, a -12..+12 dB slider, its frequency under it. */
    private fun bandColumn(band: Int, hz: Int, millibels: Int): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val hzText = if (hz >= 1_000) "${hz / 1_000.0}".removeSuffix(".0") + "k" else hz.toString()
        val value = TextView(context).apply {
            text = context.getString(R.string.eq_band_value, millibels / 100)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(WARM_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        column.addView(value, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = context.dp(8) })
        val height = context.dp(BAND_HEIGHT_DP)
        val slider = SeekBar(context).apply {
            // One step is one dB; the middle is flat. Turned upright, so up is louder.
            max = (EqMath.MAX_MB - EqMath.MIN_MB) / 100
            progress = (millibels - EqMath.MIN_MB) / 100
            contentDescription = context.getString(R.string.eq_band_hz, hzText)
            rotation = -90f
            tintAmber()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, step: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val level = EqMath.MIN_MB + step * 100
                    value.text = context.getString(R.string.eq_band_value, level / 100)
                    AudioEq.setBand(context, band, level)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                // The preset chips now say Custom; redraw them once the finger lifts.
                override fun onStopTrackingTouch(bar: SeekBar) = render()
            })
        }
        // The slider is laid out [height] wide, then rotated into a column [height] tall.
        val holder = FrameLayout(context).apply { clipChildren = false }
        holder.addView(slider, FrameLayout.LayoutParams(height, -2, Gravity.CENTER))
        column.addView(holder, LinearLayout.LayoutParams(context.dp(44), height))
        column.addView(TextView(context).apply {
            text = context.getString(R.string.eq_band_hz, hzText)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(8) })
        return column
    }

    private fun SeekBar.tintAmber() {
        progressTintList = ColorStateList.valueOf(AMBER)
        thumbTintList = ColorStateList.valueOf(AMBER)
        progressBackgroundTintList = ColorStateList.valueOf(TRACK_OFF)
    }

    private fun states(on: Int, off: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(on, off)
    )

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(MUTED)
        setPadding(0, 0, 0, context.dp(10))
    }

    private companion object {
        // The radio design's EQ sheet palette.
        const val SHEET = DesignTokens.Radio.SHEET
        const val HANDLE = DesignTokens.Radio.HANDLE
        const val PANEL = DesignTokens.Phone.INK
        const val AMBER = DesignTokens.Phone.ACCENT_RADIO
        const val AMBER_TRACK = DesignTokens.Radio.AMBER_TRACK
        const val ON_AMBER = DesignTokens.Radio.ON_AMBER
        const val WARM_TEXT = DesignTokens.Radio.WARM_TEXT
        const val OUTLINE = DesignTokens.Radio.CONTROL_PRESSED
        const val CHIP_TEXT = DesignTokens.Radio.TEXT_CONTROL
        const val SOFT = DesignTokens.Radio.SOFT_TEXT
        const val MUTED = DesignTokens.Radio.TEXT_HINT
        const val KNOB_OFF = DesignTokens.Radio.SOFT_TEXT
        const val TRACK_OFF = DesignTokens.Radio.HANDLE
        const val BAND_HEIGHT_DP = 150
    }
}
