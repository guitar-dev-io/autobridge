package dev.autobridge.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.design.ThemeHolder
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.mirror.ProjectionService
import dev.autobridge.ui.AutoBridgeDesign.dp

/** What the status card says; chosen from the projection service's state, pure so it is tested on the JVM. */
enum class MirrorStatus {
    IDLE, CONNECTING, LIVE, ERROR;

    companion object {
        fun of(state: ProjectionService.SessionState, mirroring: Boolean): MirrorStatus = when {
            mirroring -> LIVE
            state == ProjectionService.SessionState.STARTING -> CONNECTING
            state == ProjectionService.SessionState.ERROR -> ERROR
            else -> IDLE
        }
    }
}

/**
 * Mirror settings' top card: phone and car joined by three dots, one line saying where mirroring
 * stands, and the one button that fits that state: Start, a disabled Connecting, or Stop.
 *
 * The service keeps its state in a plain field rather than a flow, so the card looks again once a
 * second while it is on screen; it asks nothing of the service and stops when it is detached.
 */
class MirrorStatusCard(
    context: Context,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit
) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val dots = List(3) { View(context) }
    private val carChip = ImageView(context)
    private lateinit var carBox: View
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val button = TextView(context)
    private var shown: MirrorStatus? = null

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val p = ThemeHolder.palette
        background = AutoBridgeDesign.surface(context, p.cardFill, p.corners.card, p.cardEdge)
        setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))

        val link = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        link.addView(chip(ImageView(context).apply { setImageResource(R.drawable.ic_tile_mirror) }, AutoBridgeDesign.TEXT_MUTED, p.raised), LayoutParams(context.dp(56), context.dp(56)))
        val dotRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        dots.forEach { dot -> dotRow.addView(dot, LayoutParams(context.dp(6), context.dp(6)).apply { marginStart = context.dp(3); marginEnd = context.dp(3) }) }
        link.addView(dotRow, LayoutParams(-2, -2).apply { marginStart = context.dp(10); marginEnd = context.dp(10) })
        carChip.setImageResource(R.drawable.ic_tile_car)
        carBox = chip(carChip, AutoBridgeDesign.TEXT_MUTED, p.raised)
        link.addView(carBox, LayoutParams(context.dp(56), context.dp(56)))
        addView(link, LayoutParams(-2, -2))

        title.apply { textSize = 17f; gravity = Gravity.CENTER; typeface = AppFonts.bold(); setTextColor(AutoBridgeDesign.TEXT) }
        subtitle.apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(AutoBridgeDesign.TEXT_MUTED) }
        addView(title, LayoutParams(-1, -2).apply { topMargin = context.dp(16) })
        addView(subtitle, LayoutParams(-1, -2).apply { topMargin = context.dp(4) })

        button.apply {
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = AppFonts.bold()
            isClickable = true
            isFocusable = true
        }
        addView(button, LayoutParams(-1, context.dp(52)).apply { topMargin = context.dp(16) })
        render()
    }

    private fun chip(icon: ImageView, tint: Int, fill: Int): View = android.widget.FrameLayout(context).apply {
        background = GradientDrawable().apply { setColor(fill); cornerRadius = context.dp(16).toFloat() }
        icon.setColorFilter(tint)
        addView(icon, android.widget.FrameLayout.LayoutParams(context.dp(26), context.dp(26), Gravity.CENTER))
    }

    private fun render() {
        val status = MirrorStatus.of(ProjectionService.sessionState, MirrorCoordinator.isMirroring)
        if (status == shown) return
        shown = status
        val p = ThemeHolder.palette
        val live = AutoBridgeDesign.ACCENT_ONLINE
        val dotColor = when (status) {
            MirrorStatus.LIVE -> live
            MirrorStatus.CONNECTING -> AutoBridgeDesign.ACCENT
            else -> AutoBridgeDesign.HAIRLINE
        }
        dots.forEach { it.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) } }
        val carFill = if (status == MirrorStatus.LIVE) AutoBridgeDesign.tint(live, 0.18f) else p.raised
        carBox.background = GradientDrawable().apply { setColor(carFill); cornerRadius = context.dp(16).toFloat() }
        carChip.setColorFilter(if (status == MirrorStatus.LIVE) live else AutoBridgeDesign.TEXT_MUTED)
        title.text = context.getString(
            when (status) {
                MirrorStatus.IDLE -> R.string.mirror_card_idle_title
                MirrorStatus.CONNECTING -> R.string.mirror_card_connecting_title
                MirrorStatus.LIVE -> R.string.mirror_card_live_title
                MirrorStatus.ERROR -> R.string.mirror_card_error_title
            }
        )
        subtitle.text = when (status) {
            MirrorStatus.ERROR -> ProjectionService.lastErrorMessage ?: context.getString(R.string.mirror_card_error_sub)
            MirrorStatus.IDLE -> context.getString(R.string.mirror_card_idle_sub)
            MirrorStatus.CONNECTING -> context.getString(R.string.mirror_card_connecting_sub)
            MirrorStatus.LIVE -> context.getString(R.string.mirror_card_live_sub)
        }
        when (status) {
            MirrorStatus.IDLE, MirrorStatus.ERROR -> style(R.string.mirror_card_start, fill = AutoBridgeDesign.ACCENT, text = AutoBridgeDesign.ON_ACCENT, stroke = AutoBridgeDesign.ACCENT) { onStart() }
            MirrorStatus.CONNECTING -> style(R.string.mirror_card_connecting, fill = p.raised, text = AutoBridgeDesign.TEXT_MUTED, stroke = AutoBridgeDesign.HAIRLINE, action = null)
            MirrorStatus.LIVE -> style(R.string.mirror_card_stop, fill = android.graphics.Color.TRANSPARENT, text = live, stroke = live) { onStop() }
        }
    }

    private fun style(label: Int, fill: Int, text: Int, stroke: Int, action: (() -> Unit)?) {
        button.text = context.getString(label)
        button.setTextColor(text)
        button.background = AutoBridgeDesign.surface(context, fill, ThemeHolder.palette.corners.button, stroke)
        button.isEnabled = action != null
        button.setOnClickListener(action?.let { run -> View.OnClickListener { run() } })
        button.contentDescription = context.getString(label)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shown = null
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
