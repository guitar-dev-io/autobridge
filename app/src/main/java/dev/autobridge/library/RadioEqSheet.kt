package dev.autobridge.library

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.media.AudioEq
import dev.autobridge.media.EqMath
import dev.autobridge.media.EqPreset
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp

/**
 * The equalizer, as a sheet from the bottom of the radio player: an on switch, the one-tap
 * presets, five bands to set by hand, bass boost, and levelling between loud and quiet stations.
 *
 * Every change goes straight to [AudioEq], which stores it and applies it to the playing session,
 * so what the driver hears follows the finger. A preset tap redraws the sheet (the chips and the
 * sliders both show it); a slider only updates its own number while it moves.
 */
class RadioEqSheet(private val context: Context) {
    private val dialog = Dialog(context)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(28))
        background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE_RAISED, 28, Color.TRANSPARENT)
    }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(ScrollView(context).apply { addView(content) })
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

        // Grab handle, then the title with the on switch.
        content.addView(View(context).apply {
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.HAIRLINE, 3, Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(context.dp(40), context.dp(5)).apply {
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
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, on ->
                AudioEq.setEnabled(context, on)
                render()
            }
        })
        content.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(16) })

        // Presets, three to a row.
        content.addView(label(context.getString(R.string.eq_presets)))
        val chips = EqPreset.chips.map { preset ->
            AutoBridgeDesign.pill(
                context,
                context.getString(preset.labelRes),
                primary = settings.enabled && settings.preset == preset,
                accent = AutoBridgeDesign.ACCENT_RADIO
            ) {
                AudioEq.setPreset(context, preset)
                render()
            }
        }
        AutoBridgeDesign.grid(context, chips, columns = 3).forEach { row ->
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
        }

        // The five bands by hand.
        val customHeader = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(14), 0, 0)
        }
        customHeader.addView(label(context.getString(R.string.eq_custom_section)), LinearLayout.LayoutParams(0, -2, 1f))
        customHeader.addView(AutoBridgeDesign.pill(context, context.getString(R.string.eq_reset), accent = AutoBridgeDesign.ACCENT_RADIO) {
            AudioEq.resetCustom(context)
            render()
        })
        content.addView(customHeader)
        val gains = settings.gains
        EqMath.REFERENCE_HZ.forEachIndexed { band, hz ->
            content.addView(bandRow(band, hz, gains.getOrElse(band) { 0 }))
        }

        // Bass boost and loudness levelling.
        content.addView(label(context.getString(R.string.eq_bass)).apply { setPadding(0, context.dp(14), 0, context.dp(4)) })
        content.addView(SeekBar(context).apply {
            max = AudioEq.BASS_MAX
            progress = settings.bass
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) AudioEq.setBass(context, value)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
        content.addView(Switch(context).apply {
            text = context.getString(R.string.eq_level)
            textSize = 15f
            setTextColor(AutoBridgeDesign.TEXT)
            isChecked = settings.levelVolume
            setPadding(0, context.dp(12), 0, 0)
            setOnCheckedChangeListener { _, on -> AudioEq.setLevelVolume(context, on) }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** One band: its frequency, a slider of -12..+12 dB, and the value it is at. */
    private fun bandRow(band: Int, hz: Int, millibels: Int): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(4), 0, context.dp(4))
        }
        val hzText = if (hz >= 1_000) "${hz / 1_000.0}".removeSuffix(".0") + "k" else hz.toString()
        row.addView(TextView(context).apply {
            text = context.getString(R.string.eq_band_hz, hzText)
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
        }, LinearLayout.LayoutParams(context.dp(68), -2))
        val value = TextView(context).apply {
            text = context.getString(R.string.eq_band_value, millibels / 100)
            textSize = 13f
            gravity = Gravity.END
            setTextColor(AutoBridgeDesign.ACCENT_RADIO)
        }
        row.addView(SeekBar(context).apply {
            // One step is one dB; the middle is flat.
            max = (EqMath.MAX_MB - EqMath.MIN_MB) / 100
            progress = (millibels - EqMath.MIN_MB) / 100
            contentDescription = context.getString(R.string.eq_band_hz, hzText)
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
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value, LinearLayout.LayoutParams(context.dp(56), -2))
        return row
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(AutoBridgeDesign.TEXT_MUTED)
        setPadding(0, 0, 0, context.dp(8))
    }
}
