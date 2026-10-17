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
 * Every change goes straight to [AudioEq], which stores it and applies it to the playing sessions,
 * so what the driver hears follows the finger. A preset tap redraws the sheet (the chips and the
 * sliders both show it); a slider only updates its own number while it moves.
 */
class AudioEffectsSheet(private val context: Context) {
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

        // Bass boost, virtualizer and volume boost: one slider each, off at the left.
        content.addView(strengthRow(context.getString(R.string.eq_bass), settings.bass, AudioEq.BASS_MAX,
            { "${it / 10}%" }) { AudioEq.setBass(context, it) })
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
            AutoBridgeDesign.pill(
                context,
                context.getString(labelRes),
                primary = settings.scope == scope,
                accent = AutoBridgeDesign.ACCENT_RADIO
            ) {
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
    }

    /** A titled 0..[max] slider with its value on the right, for the strength-style effects. */
    private fun strengthRow(title: String, current: Int, max: Int, format: (Int) -> String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(14), 0, 0)
        }
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(title).apply { setPadding(0, 0, 0, context.dp(4)) }, LinearLayout.LayoutParams(0, -2, 1f))
        val value = TextView(context).apply {
            text = format(current)
            textSize = 13f
            setTextColor(AutoBridgeDesign.ACCENT_RADIO)
        }
        head.addView(value)
        box.addView(head)
        box.addView(SeekBar(context).apply {
            this.max = max
            progress = current
            contentDescription = title
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
        })
        return box
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
