package dev.autobridge.duoscreen.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import dev.autobridge.duoscreen.R
import dev.autobridge.duoscreen.layout.DuoScreenControl
import dev.autobridge.duoscreen.layout.DuoScreenControlsLayout
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

    private companion object {
        const val PANEL_COLOR = 0xE60B0D10.toInt()
        const val BUTTON_COLOR = 0xFF1F242B.toInt()
        const val ICON_COLOR = 0xFFD5DAE0.toInt()
        const val HANDLE_COLOR = 0xFF4A5260.toInt()
        const val ACCENT_COLOR = 0xFF4DA3FF.toInt()
        const val ON_ACCENT_COLOR = 0xFF0B1A2E.toInt()
        const val FAB_COLOR = 0xDB1C2027.toInt()
        const val MENU_ITEM_COLOR = 0xF2232831.toInt()

        /** Corner radius of a square button, as a share of its side (16 of 56 in the design). */
        const val BUTTON_RADIUS_SHARE = 0.29f

        /** Icon size as a share of its button (26 of 56 in the design). */
        const val ICON_SHARE = 0.46f
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val box = RectF()

    /** Returns null when there is nothing to draw. [presetIcon] is the layout button's icon. */
    fun paint(layout: DuoScreenControlsLayout, @DrawableRes presetIcon: Int): Painted? {
        val area = layout.paintedArea ?: return null
        if (area.width <= 0 || area.height <= 0) return null
        val bitmap = Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(-area.left.toFloat(), -area.top.toFloat())

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
    }

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
