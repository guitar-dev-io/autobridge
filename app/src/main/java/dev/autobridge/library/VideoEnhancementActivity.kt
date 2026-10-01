package dev.autobridge.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.autobridge.settings.VideoSettings
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * Brightness, contrast and saturation for the decoded picture.
 *
 * The adjustments run in the shared player's GL effect pipeline
 * ([dev.autobridge.media.MediaPlaybackService]), so what is changed here applies to the car screen
 * as well as the phone, and it applies while a channel is playing — the service listens for the
 * change rather than waiting for the next stream.
 *
 * The master switch is separate from the sliders so a set of values can be parked and brought back
 * without re-dialling them, which is the point of having the screen at all: a dim IPTV channel
 * wants different numbers from a washed-out one.
 */
class VideoEnhancementActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, VideoEnhancementActivity::class.java)
    }

    private val accent = AutoBridgeDesign.ACCENT_VIDEO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val enabled = VideoSettings.enhancementEnabled(this)
        val body = AutoBridgeDesign.body(this)

        body.stack(AutoBridgeDesign.sectionLabel(this, "Enhancement"), gap = 2)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = "Enhance the picture",
                subtitle = if (enabled) {
                    "Applied to the phone and the car screen"
                } else {
                    "The frame is shown exactly as decoded"
                },
                accent = if (enabled) accent else AutoBridgeDesign.TEXT_MUTED,
                badgeText = if (enabled) "ON" else "OFF",
                onClick = {
                    VideoSettings.setEnhancementEnabled(this, !enabled)
                    render()
                }
            )
        )

        body.stack(
            AutoBridgeDesign.sectionLabel(
                this,
                if (enabled) "Adjustments" else "Adjustments (enhancement is off)"
            ),
            gap = 12
        )
        body.stack(
            slider(
                title = "Brightness",
                value = VideoSettings.brightness(this),
                dimmed = !enabled
            ) { VideoSettings.setBrightness(this, it) }
        )
        body.stack(
            slider(
                title = "Contrast",
                value = VideoSettings.contrast(this),
                dimmed = !enabled
            ) { VideoSettings.setContrast(this, it) }
        )
        body.stack(
            slider(
                title = "Saturation",
                value = VideoSettings.saturation(this),
                dimmed = !enabled
            ) { VideoSettings.setSaturation(this, it) }
        )

        body.stack(
            AutoBridgeDesign.pill(this, "Reset to decoded picture", accent = accent) {
                VideoSettings.resetEnhancement(this)
                render()
            },
            gap = 16
        )

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = "Video enhancement",
                    subtitle = if (enabled) "On" else "Off",
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /**
     * One labelled slider over -100..+100. [SeekBar] starts at 0, so the stored value is carried
     * with the midpoint added and taken off again; the readout updates while dragging and the
     * setting is only written when the finger lifts, so a drag does not rebuild the GL effect
     * pipeline on every pixel.
     */
    private fun slider(
        title: String,
        value: Int,
        dimmed: Boolean,
        onPicked: (Int) -> Unit
    ): View {
        val range = VideoSettings.ADJUSTMENT_RANGE
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoBridgeDesign.surface(this@VideoEnhancementActivity, AutoBridgeDesign.SURFACE, 16)
            setPadding(dp(14), dp(12), dp(14), dp(6))
        }
        val readout = TextView(this).apply {
            text = label(value)
            textSize = 13f
            setTextColor(if (dimmed) AutoBridgeDesign.TEXT_MUTED else accent)
            gravity = Gravity.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val heading = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@VideoEnhancementActivity).apply {
                text = title
                textSize = 15f
                setTextColor(if (dimmed) AutoBridgeDesign.TEXT_MUTED else AutoBridgeDesign.TEXT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(readout, LinearLayout.LayoutParams(-2, -2))
        }
        val bar = SeekBar(this).apply {
            max = range * 2
            progress = value + range
            isEnabled = !dimmed
            val tint = if (dimmed) AutoBridgeDesign.TEXT_MUTED else accent
            progressTintList = ColorStateList.valueOf(tint)
            thumbTintList = ColorStateList.valueOf(tint)
            progressBackgroundTintList = ColorStateList.valueOf(AutoBridgeDesign.HAIRLINE)
            contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    readout.text = label(progress - range)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    onPicked(seekBar.progress - range)
                }
            })
        }
        column.addView(heading, LinearLayout.LayoutParams(-1, -2))
        column.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        return column
    }

    private fun label(value: Int): String = when {
        value > 0 -> "+$value"
        value == 0 -> "Neutral"
        else -> value.toString()
    }
}
