package dev.autobridge.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import dev.autobridge.R

/**
 * Paints the whole AutoBridge home content area onto the car surface: background, header
 * (logo + two-tone wordmark), the 3 × 2 card grid and, only when the grid cannot fit, the scroll
 * buttons. Android Auto's own chrome (rail / bottom bar) lives outside this surface and is never
 * drawn or imitated here.
 */
internal class HomeMenuRenderer(private val context: Context) {
    data class State(val focused: Int = -1, val pressed: Int = -1, val scroll: Float = 0f)

    private val logo: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_autobridge_launcher)
    private val card = HomeMenuCard()
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chevron = Path()

    /** Label width at a text size, measured with the exact paint the cards draw with. */
    fun measureLabel(text: String, size: Float): Float {
        card.label.textSize = size
        return card.label.measureText(text)
    }

    fun draw(canvas: Canvas, layout: HomeMenuLayout, items: List<HomeMenuItem>, state: State) {
        canvas.drawColor(HomeMenuTheme.BACKGROUND)
        drawHeader(canvas, layout)

        canvas.save()
        val viewport = layout.viewport
        // Room for the focus halo, which straddles the card edge.
        val halo = HomeMenuTheme.Dp.FOCUS_GLOW * layout.density
        canvas.clipRect(viewport.left - halo, viewport.top - halo, viewport.right + halo, viewport.bottom + halo)
        items.forEachIndexed { index, item ->
            val bounds = layout.cards.getOrNull(index)?.offset(-state.scroll) ?: return@forEachIndexed
            val cardState = when (index) {
                state.pressed -> HomeMenuCard.State.PRESSED
                state.focused -> HomeMenuCard.State.FOCUSED
                else -> HomeMenuCard.State.NORMAL
            }
            card.draw(canvas, bounds, item, item.title(context), layout, cardState)
        }
        canvas.restore()

        layout.scrollUp?.let { drawScrollButton(canvas, it, layout, up = true, enabled = state.scroll > 0f) }
        layout.scrollDown?.let {
            drawScrollButton(canvas, it, layout, up = false, enabled = state.scroll < layout.maxScroll)
        }
    }

    private fun drawHeader(canvas: Canvas, layout: HomeMenuLayout) {
        val box = layout.logo
        logo?.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        logo?.draw(canvas)

        title.textSize = layout.titleSize
        val metrics = title.fontMetrics
        // Centre the cap-height block on the logo, not the full ascent/descent box.
        val baseline = box.centerY - (metrics.ascent + metrics.descent) / 2f
        title.color = HomeMenuTheme.TEXT_PRIMARY
        canvas.drawText(WORD_AUTO, layout.titleX, baseline, title)
        title.color = HomeMenuTheme.ACCENT
        canvas.drawText(WORD_BRIDGE, layout.titleX + title.measureText(WORD_AUTO), baseline, title)
    }

    private fun drawScrollButton(canvas: Canvas, box: MenuBox, layout: HomeMenuLayout, up: Boolean, enabled: Boolean) {
        val d = layout.density
        val radius = box.width / 2f
        fill.color = HomeMenuTheme.CARD
        canvas.drawCircle(box.centerX, box.centerY, radius, fill)
        stroke.color = HomeMenuTheme.BORDER
        stroke.strokeWidth = HomeMenuTheme.Dp.CARD_BORDER * d
        canvas.drawCircle(box.centerX, box.centerY, radius - stroke.strokeWidth / 2f, stroke)

        val half = radius * 0.30f
        val rise = half * 0.55f * if (up) 1f else -1f
        chevron.reset()
        chevron.moveTo(box.centerX - half, box.centerY + rise)
        chevron.lineTo(box.centerX, box.centerY - rise)
        chevron.lineTo(box.centerX + half, box.centerY + rise)
        stroke.color = if (enabled) HomeMenuTheme.TEXT_PRIMARY
        else HomeMenuTheme.withAlpha(HomeMenuTheme.TEXT_SECONDARY, 0.35f)
        stroke.strokeWidth = 3f * d
        canvas.drawPath(chevron, stroke)
    }

    private companion object {
        const val WORD_AUTO = "Auto"
        const val WORD_BRIDGE = "Bridge"
    }
}
