package dev.autobridge.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.i18n.AppLocale
import dev.autobridge.settings.AspectRatio
import dev.autobridge.settings.ChannelGesture
import dev.autobridge.settings.PlayerDpi
import dev.autobridge.settings.PreferredPlayer
import dev.autobridge.settings.SplitLayout
import dev.autobridge.settings.VideoSettings
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * The Video section of settings: how the phone player decodes, lays out and hands over a picture.
 *
 * Everything here is read by [PlayerActivity] and by
 * [dev.autobridge.media.MediaPlaybackService], and nothing on this screen needs a running player —
 * a value changed while a channel is on air is picked up by the next render or, for the decoder
 * and the enhancement effects, by the service's own listener.
 *
 * Choices open a list rather than cycling on tap: five aspect ratios behind a single row would
 * mean tapping four times to see the fifth, and this is a screen someone reads in a parked car.
 */
class VideoSettingsActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, VideoSettingsActivity::class.java)
    }

    private val accent = AutoBridgeDesign.ACCENT_VIDEO

    /** A device without PiP support would show a switch that cannot do anything. */
    private val supportsPip: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Applies the Settings &gt; Language choice; see [AppLocale.rebase]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The page is built in onResume, which also runs on the way back from the enhancement
        // sub-screen; building here too would just throw the first tree away.
    }

    private fun render() {
        val body = AutoBridgeDesign.body(this)

        body.stack(AutoBridgeDesign.sectionLabel(this, "Video"), gap = 2)

        val player = VideoSettings.preferredPlayer(this)
        body.stack(
            choiceRow("Preferred player", player.label, player.caption) {
                choose("Preferred player", PreferredPlayer.entries, player, { it.label }) {
                    VideoSettings.setPreferredPlayer(this, it)
                }
            }
        )

        body.stack(
            toggleRow(
                title = "Play in background",
                on = VideoSettings.playInBackground(this),
                onCaption = "Keep playing after leaving the player screen",
                offCaption = "Pause the picture when the screen is left"
            ) {
                VideoSettings.setPlayInBackground(this, !VideoSettings.playInBackground(this))
            }
        )

        body.stack(
            toggleRow(
                title = "Automatic picture in picture",
                on = supportsPip && VideoSettings.autoPictureInPicture(this),
                onCaption = "Shrink into a floating window on leaving",
                offCaption = if (supportsPip) {
                    "Leaving the player does not open a window"
                } else {
                    "This device has no picture-in-picture mode"
                },
                dimmed = !supportsPip
            ) {
                if (!supportsPip) return@toggleRow
                VideoSettings.setAutoPictureInPicture(this, !VideoSettings.autoPictureInPicture(this))
            }
        )

        body.stack(
            toggleRow(
                title = "Auto next channel",
                on = VideoSettings.autoNextChannel(this),
                onCaption = "Move on by itself when a channel fails or ends",
                offCaption = "A dead channel waits for you"
            ) {
                VideoSettings.setAutoNextChannel(this, !VideoSettings.autoNextChannel(this))
            }
        )

        val gesture = VideoSettings.channelGesture(this)
        body.stack(
            choiceRow("Change channel", gesture.label, gesture.caption) {
                choose("Change channel", ChannelGesture.entries, gesture, { it.label }) {
                    VideoSettings.setChannelGesture(this, it)
                }
            }
        )

        val split = VideoSettings.splitLayout(this)
        body.stack(
            choiceRow("Split screen layout", split.label, split.caption) {
                choose("Split screen layout", SplitLayout.entries, split, { it.label }) {
                    VideoSettings.setSplitLayout(this, it)
                }
            }
        )

        val ratio = VideoSettings.aspectRatio(this)
        body.stack(
            choiceRow("Default aspect ratio", ratio.label, ratio.caption) {
                choose("Default aspect ratio", AspectRatio.entries, ratio, { it.label }) {
                    VideoSettings.setAspectRatio(this, it)
                }
            }
        )

        val dpi = VideoSettings.playerDpi(this)
        body.stack(
            choiceRow("Preferred DPI", dpi.label, dpi.caption) {
                choose("Preferred DPI", PlayerDpi.entries, dpi, { it.label }) {
                    VideoSettings.setPlayerDpi(this, it)
                }
            }
        )

        val enhancement = VideoSettings.enhancement(this)
        body.stack(
            choiceRow(
                title = "Video enhancement",
                value = if (enhancement.isNeutral) "Open" else "On",
                caption = if (enhancement.isNeutral) {
                    "Brightness, contrast and saturation"
                } else {
                    "Brightness ${signed(enhancement.brightness)} • " +
                        "Contrast ${signed(enhancement.contrast)} • " +
                        "Saturation ${signed(enhancement.saturation)}"
                }
            ) {
                startActivity(VideoEnhancementActivity.intent(this))
            }
        )

        body.stack(AutoBridgeDesign.sectionLabel(this, "Channel"), gap = 2)
        body.stack(
            toggleRow(
                title = "Show delay",
                on = VideoSettings.showDelay(this),
                onCaption = "Show how far behind the live edge a channel is",
                offCaption = "Only the LIVE badge"
            ) {
                VideoSettings.setShowDelay(this, !VideoSettings.showDelay(this))
            }
        )

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = "Video",
                    subtitle = "Player, layout and picture",
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

    /**
     * A row that reads as a switch, in the same shape as the YouTube add-on settings: the badge
     * carries the state and takes the section accent only while it is on.
     */
    private fun toggleRow(
        title: String,
        on: Boolean,
        onCaption: String,
        offCaption: String,
        dimmed: Boolean = false,
        onToggle: () -> Unit
    ): View = AutoBridgeDesign.contentRow(
        context = this,
        title = title,
        subtitle = if (on) onCaption else offCaption,
        accent = if (on && !dimmed) accent else AutoBridgeDesign.TEXT_MUTED,
        badgeText = if (on) "ON" else "OFF",
        onClick = {
            onToggle()
            render()
        }
    )

    /**
     * A row that names a setting and shows its current value on the right, the way a settings list
     * reads: the title and the quiet caption on the left, the answer where the eye looks for it.
     * [AutoBridgeDesign.contentRow] has no right-hand value slot and its badge only fits a glyph,
     * so this row is built here rather than bent out of that one.
     */
    private fun choiceRow(
        title: String,
        value: String,
        caption: String,
        onClick: () -> Unit
    ): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = AutoBridgeDesign.tappable(this@VideoSettingsActivity, AutoBridgeDesign.SURFACE, 16, accent)
        isClickable = true
        isFocusable = true
        contentDescription = "$title, $value"
        setPadding(dp(14), dp(12), dp(10), dp(12))

        val labels = LinearLayout(this@VideoSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        labels.addView(TextView(this@VideoSettingsActivity).apply {
            this.text = title
            textSize = 15f
            setTextColor(AutoBridgeDesign.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        labels.addView(TextView(this@VideoSettingsActivity).apply {
            this.text = caption
            textSize = 12f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))

        addView(TextView(this@VideoSettingsActivity).apply {
            this.text = value
            textSize = 14f
            setTextColor(accent)
            gravity = Gravity.END
            maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(-2, -2))
        addView(TextView(this@VideoSettingsActivity).apply {
            this.text = "›"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), -2))

        setOnClickListener { onClick() }
    }

    private fun <T> choose(
        title: String,
        options: List<T>,
        current: T,
        label: (T) -> String,
        onPick: (T) -> Unit
    ) {
        val labels = options.map { option ->
            // The current value is marked rather than only highlighted, so the list says what it
            // is doing now even before anything is picked.
            if (option == current) "● " + label(option) else "   " + label(option)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, index ->
                onPick(options[index])
                render()
            }
            .show()
    }
}
