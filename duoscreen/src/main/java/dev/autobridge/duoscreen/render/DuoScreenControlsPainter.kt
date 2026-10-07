package dev.autobridge.duoscreen.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import dev.autobridge.duoscreen.R
import dev.autobridge.duoscreen.layout.DuoScreenControl
import dev.autobridge.duoscreen.layout.DuoScreenControlsLayout
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Bounds
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect

/**
 * Paints [DuoScreenControlsLayout] into a bitmap the compositor draws over the panes.
 *
 * Only the area the controls cover is painted ([DuoScreenControlsLayout.paintedArea]), so a change
 * costs one small upload rather than a full-surface one. Runs on the caller's thread; the bitmap
 * is handed to [DuoScreenCompositor.setOverlay], which owns and recycles it.
 *
 * Colours and sizes follow docs/design/13–17 (UI_REDESIGN_TASKS.md, Phase 7).
 */
class DuoScreenControlsPainter(private val context: Context) {
    data class Painted(val bitmap: Bitmap, val area: Rect)

    /**
     * A message card over the panes — "the phone is asleep" — centred in [area] (usually the
     * largest pane). While one is shown the whole surface is painted, with a scrim over the panes.
     */
    data class Notice(val title: String, val body: String, val area: Rect)

    private companion object {
        const val PANEL_COLOR = 0xE60B0D10.toInt()
        const val BUTTON_COLOR = 0xFF1F242B.toInt()
        const val ICON_COLOR = 0xFFD5DAE0.toInt()
        const val HANDLE_COLOR = 0xFF4A5260.toInt()
        const val ACCENT_COLOR = 0xFF4DA3FF.toInt()
        const val ON_ACCENT_COLOR = 0xFF0B1A2E.toInt()
        const val FAB_COLOR = 0xDB1C2027.toInt()
        const val MENU_ITEM_COLOR = 0xF2232831.toInt()
        const val SCRIM_COLOR = 0xD90B0D10.toInt()
        const val CARD_COLOR = 0xFA1C2027.toInt()
        const val TITLE_COLOR = 0xFFF3F5F7.toInt()
        const val BODY_COLOR = 0xFFAEB5BE.toInt()
        const val PHONE_OFF_ACTIVE_COLOR = 0xFF2A3A55.toInt()

        /** Corner radius of a square button, as a share of its side (16 of 56 in the design). */
        const val BUTTON_RADIUS_SHARE = 0.29f

        /** Icon size as a share of its button (26 of 56 in the design). */
        const val ICON_SHARE = 0.46f
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val box = RectF()

    /**
     * Returns null when there is nothing to draw. [presetIcon] is the layout button's icon;
     * [phoneScreenOff] picks the phone-screen button's icon; [scale] is DuoScreenChrome's.
     */
    fun paint(
        layout: DuoScreenControlsLayout,
        @DrawableRes presetIcon: Int,
        phoneScreenOff: Boolean = false,
        notice: Notice? = null,
        bounds: Bounds? = null,
        scale: Float = 1f
    ): Painted? {
        val area = if (notice != null && bounds != null) {
            Rect(0, 0, bounds.width, bounds.height)
        } else {
            layout.paintedArea ?: return null
        }
        if (area.width <= 0 || area.height <= 0) return null
        val bitmap = Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-area.left.toFloat(), -area.top.toFloat())

        if (notice != null && bounds != null) drawNotice(canvas, notice, bounds, scale)

        layout.panel?.let { panel ->
            roundRect(canvas, panel, minOf(panel.width, panel.height) * 0.36f, PANEL_COLOR)
        }
        layout.buttons.forEach { button ->
            val rect = button.rect
            when (button.control) {
                DuoScreenControl.HANDLE ->
                    roundRect(canvas, rect, minOf(rect.width, rect.height) / 2f, HANDLE_COLOR)

                DuoScreenControl.MENU -> {
                    val open = layout.menuOpen
                    circle(canvas, rect, if (open) ACCENT_COLOR else FAB_COLOR)
                    icon(
                        canvas, rect,
                        if (open) R.drawable.ic_duo_close else R.drawable.ic_duo_more,
                        if (open) ON_ACCENT_COLOR else ICON_COLOR
                    )
                }

                DuoScreenControl.DONE -> {
                    background(canvas, rect, layout.kind, ACCENT_COLOR)
                    icon(canvas, rect, R.drawable.ic_duo_done, ON_ACCENT_COLOR)
                }

                DuoScreenControl.PHONE_SCREEN -> {
                    background(
                        canvas, rect, layout.kind,
                        if (phoneScreenOff) PHONE_OFF_ACTIVE_COLOR
                        else if (layout.kind == DuoScreenControlsLayout.Kind.FLOATING) MENU_ITEM_COLOR
                        else BUTTON_COLOR
                    )
                    icon(
                        canvas, rect,
                        if (phoneScreenOff) R.drawable.ic_duo_phone_on else R.drawable.ic_duo_phone_off,
                        if (phoneScreenOff) ACCENT_COLOR else ICON_COLOR
                    )
                }

                else -> {
                    val color = if (layout.kind == DuoScreenControlsLayout.Kind.FLOATING) {
                        MENU_ITEM_COLOR
                    } else {
                        BUTTON_COLOR
                    }
                    background(canvas, rect, layout.kind, color)
                    icon(canvas, rect, iconFor(button.control, presetIcon), ICON_COLOR)
                }
            }
        }
        return Painted(bitmap, area)
    }

    @DrawableRes
    private fun iconFor(control: DuoScreenControl, @DrawableRes presetIcon: Int): Int = when (control) {
        DuoScreenControl.LAYOUT -> presetIcon
        DuoScreenControl.SWAP -> R.drawable.ic_duo_swap
        DuoScreenControl.RELOAD -> R.drawable.ic_duo_reload
        DuoScreenControl.ARRANGE -> R.drawable.ic_duo_arrange
        DuoScreenControl.DONE -> R.drawable.ic_duo_done
        DuoScreenControl.MOVE -> R.drawable.ic_duo_move
        DuoScreenControl.MENU -> R.drawable.ic_duo_more
        DuoScreenControl.HANDLE -> R.drawable.ic_duo_arrange
        DuoScreenControl.PHONE_SCREEN -> R.drawable.ic_duo_phone_off
    }

    /** Scrim over the whole surface, then a card with an icon, a title and wrapped body text. */
    private fun drawNotice(canvas: Canvas, notice: Notice, bounds: Bounds, scale: Float) {
        fill.color = SCRIM_COLOR
        canvas.drawRect(0f, 0f, bounds.width.toFloat(), bounds.height.toFloat(), fill)

        val s = if (scale.isFinite() && scale > 0f) scale else 1f
        val pad = 32f * s
        val iconSize = (72f * s).toInt()
        val gap = 18f * s
        val cardWidth = minOf(620f * s, notice.area.width - 48f * s).toInt().coerceAtLeast(1)
        val textWidth = (cardWidth - 2 * pad).toInt().coerceAtLeast(1)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TITLE_COLOR
            textSize = 32f * s
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BODY_COLOR
            textSize = 23f * s
        }
        val title = centredText(notice.title, titlePaint, textWidth)
        val body = centredText(notice.body, bodyPaint, textWidth)
        val cardHeight = (pad + iconSize + gap + title.height + gap * 0.6f + body.height + pad).toInt()

        val left = notice.area.left + (notice.area.width - cardWidth) / 2
        val top = notice.area.top + ((notice.area.height - cardHeight) / 2).coerceAtLeast(0)
        roundRect(canvas, Rect(left, top, cardWidth, cardHeight), 28f * s, CARD_COLOR)

        val iconRect = Rect(left + (cardWidth - iconSize) / 2, (top + pad).toInt(), iconSize, iconSize)
        circle(canvas, iconRect, PHONE_OFF_ACTIVE_COLOR)
        icon(canvas, iconRect, R.drawable.ic_duo_phone_off, ACCENT_COLOR)

        canvas.save()
        canvas.translate(left + pad, iconRect.bottom + gap)
        title.draw(canvas)
        canvas.translate(0f, title.height + gap * 0.6f)
        body.draw(canvas)
        canvas.restore()
    }

    private fun centredText(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .build()

    /** Rounded squares on the seam bar, circles in the floating menu (as in the designs). */
    private fun background(canvas: Canvas, rect: Rect, kind: DuoScreenControlsLayout.Kind, color: Int) {
        if (kind == DuoScreenControlsLayout.Kind.FLOATING) {
            circle(canvas, rect, color)
        } else {
            roundRect(canvas, rect, minOf(rect.width, rect.height) * BUTTON_RADIUS_SHARE, color)
        }
    }

    private fun roundRect(canvas: Canvas, rect: Rect, radius: Float, color: Int) {
        fill.color = color
        box.set(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
        canvas.drawRoundRect(box, radius, radius, fill)
    }

    private fun circle(canvas: Canvas, rect: Rect, color: Int) {
        fill.color = color
        canvas.drawCircle(
            rect.left + rect.width / 2f,
            rect.top + rect.height / 2f,
            minOf(rect.width, rect.height) / 2f,
            fill
        )
    }

    private fun icon(canvas: Canvas, rect: Rect, @DrawableRes res: Int, color: Int) {
        val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return
        drawable.setTint(color)
        val size = (minOf(rect.width, rect.height) * ICON_SHARE).toInt().coerceAtLeast(1)
        val left = rect.left + (rect.width - size) / 2
        val top = rect.top + (rect.height - size) / 2
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
    }
}
