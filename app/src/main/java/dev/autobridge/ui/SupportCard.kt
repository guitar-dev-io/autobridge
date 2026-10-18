package dev.autobridge.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.support.QrCode
import dev.autobridge.support.SupportChannels
import dev.autobridge.ui.AutoBridgeDesign.dp

/**
 * The phone's "buy me a snack" card: a heading, one tab per [SupportChannels.Channel], a line of
 * thanks, and the chosen channel's QR beside its link and an Open button.
 *
 * The QR is there for the case the card is most often seen in - someone showing the phone to a
 * passenger, or scanning from a second device - and the Open button for the person holding it.
 * PromptPay has no link, so its tab shows only the bundled QR and a "scan with your bank app" line.
 */
object SupportCard {

    private const val QR_DP = 168

    /**
     * [framed] draws the card's own surface; pass false when it goes inside a [SettingsUi.group]
     * card or a dialog that already provides one.
     */
    fun view(
        context: Context,
        channels: List<SupportChannels.Channel>,
        framed: Boolean = true,
        open: (String) -> Unit,
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (framed) background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 20)
            setPadding(context.dp(16), context.dp(18), context.dp(16), context.dp(18))
        }
        if (channels.isEmpty()) return card
        var selected = 0
        fun render() {
            card.removeAllViews()
            card.addView(TextView(context).apply {
                text = context.getString(R.string.support_card_title)
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(AutoBridgeDesign.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LinearLayout.LayoutParams(-1, -2))
            if (channels.size > 1) {
                card.addView(
                    HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        addView(SettingsUi.segments(context, channels.indices.map { it to channels[it].label }, selected) {
                            selected = it
                            render()
                        })
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(14) }
                )
            }
            card.addView(TextView(context).apply {
                text = context.getString(R.string.support_card_message)
                textSize = 13.5f
                gravity = Gravity.CENTER
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(14) })
            card.addView(body(context, channels[selected], open), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = context.dp(16)
            })
        }
        render()
        return card
    }

    private fun body(context: Context, channel: SupportChannels.Channel, open: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(qr(context, channel), LinearLayout.LayoutParams(context.dp(QR_DP), context.dp(QR_DP)))
            addView(TextView(context).apply {
                text = channel.displayUrl ?: context.getString(R.string.support_scan_bank_app)
                textSize = 14f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(AutoBridgeDesign.TEXT)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
            val url = channel.url
            if (url != null) {
                addView(TextView(context).apply {
                    text = context.getString(R.string.support_open, channel.label)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background = AutoBridgeDesign.tappable(
                        context, AutoBridgeDesign.ACCENT_FAVORITE, 24, Color.WHITE, Color.TRANSPARENT
                    )
                    minHeight = context.dp(48)
                    setPadding(context.dp(22), context.dp(12), context.dp(22), context.dp(12))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { open(url) }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(12) })
            }
        }

    /** The QR on a white tile: banking apps and phone cameras read dark-on-light far more reliably. */
    private fun qr(context: Context, channel: SupportChannels.Channel): View = ImageView(context).apply {
        background = AutoBridgeDesign.surface(context, Color.WHITE, 16, Color.TRANSPARENT)
        val pad = context.dp(8)
        setPadding(pad, pad, pad, pad)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(R.string.support_qr_description, channel.label)
        val bundled = QrCode.bundled(channel.kind)
        if (bundled != null) setImageResource(bundled)
        else setImageBitmap(QrCode.bitmap(channel.url.orEmpty(), context.dp(QR_DP)))
    }
}
