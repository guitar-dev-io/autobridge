package dev.autobridge.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * Paints the car radio grid ([CarRadioHomeScreen]) after the radio design's car screen: dark
 * ground, round back and search buttons, the tabs as pills with the selected one amber, and the
 * stations as cards whose logo sits on top of the name. The station on the air gets the design's
 * warm card and amber ring. Paints and paths are built once; a frame only ellipsizes names.
 */
internal class CarRadioGridRenderer(private val context: Context) {

    /** One cell: a station (with its logo address) or an action such as "All stations". */
    data class Cell(
        val title: String,
        val logo: String? = null,
        @DrawableRes val icon: Int = 0,
        val onAir: Boolean = false,
        /** Shown under the name of the station on the air ("Playing" / "Paused"). */
        val status: String? = null,
    )

    data class Tab(val label: String, @DrawableRes val icon: Int)

    data class Content(
        val tabs: List<Tab>,
        val selectedTab: Int,
        val cells: List<Cell>,
        /** Shown instead of the grid (loading, empty, failed, pick a province). */
        val message: String? = null,
        val messageAction: String? = null,
    )

    fun interface Logos {
        fun bitmap(url: String?): Bitmap?
    }

    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val icons = mutableMapOf<Int, Drawable?>()

    /** The tab label paint, so the layout can be computed from the widths it really draws. */
    fun measureTabLabels(tabs: List<Tab>, density: Float): List<Float> {
        text.typeface = bold
        text.textSize = TAB_TEXT_DP * density
        return tabs.map { text.measureText(it.label) }
    }

    fun draw(
        canvas: Canvas,
        layout: CarRadioGridLayout,
        content: Content,
        scroll: Float,
        pressed: CarRadioGridRegion?,
        logos: Logos,
    ) {
        canvas.drawColor(BACKGROUND)
        drawRound(canvas, layout.back, pressed == CarRadioGridRegion.Back, R_BACK)
        drawRound(canvas, layout.search, pressed == CarRadioGridRegion.Search, R_SEARCH)
        content.tabs.forEachIndexed { index, tab ->
            val box = layout.tabs.getOrNull(index) ?: return@forEachIndexed
            drawTab(canvas, layout, box, tab, index == content.selectedTab, pressed == CarRadioGridRegion.Tab(index))
        }

        val message = content.message
        if (message != null) {
            drawMessage(canvas, layout, message, content.messageAction, pressed == CarRadioGridRegion.MessageAction)
            return
        }
        val viewport = layout.gridViewport
        canvas.save()
        canvas.clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom)
        content.cells.forEachIndexed { index, cell ->
            val box = layout.cellBox(index, scroll)
            if (box.bottom < viewport.top || box.top > viewport.bottom) return@forEachIndexed
            drawCell(canvas, layout, box, cell, pressed == CarRadioGridRegion.Cell(index), logos)
        }
        canvas.restore()
        drawScrollHint(canvas, layout, scroll)
    }

    private fun drawRound(canvas: Canvas, box: CarRadioBox, pressed: Boolean, @DrawableRes icon: Int) {
        fill.color = if (pressed) CHIP_PRESSED else CHIP
        canvas.drawCircle(box.centerX, box.centerY, box.width / 2f, fill)
        drawIcon(canvas, icon, box.centerX, box.centerY, box.width * 0.5f, INK)
    }

    private fun drawTab(canvas: Canvas, layout: CarRadioGridLayout, box: CarRadioBox, tab: Tab, selected: Boolean, pressed: Boolean) {
        fill.color = when {
            selected -> ACCENT
            pressed -> CHIP_PRESSED
            else -> CHIP
        }
        rect.set(box.left, box.top, box.right, box.bottom)
        canvas.drawRoundRect(rect, box.height / 2f, box.height / 2f, fill)
        val ink = if (selected) ON_ACCENT else TAB_INK
        val iconSize = layout.dp(CarRadioGridLayout.TAB_ICON_DP)
        text.typeface = bold
        text.textSize = layout.dp(TAB_TEXT_DP)
        text.color = ink
        val room = box.width - iconSize - layout.dp(CarRadioGridLayout.TAB_ICON_GAP_DP) - layout.dp(16f)
        val label = TextUtils.ellipsize(tab.label, text, room.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        val labelWidth = if (label.isEmpty()) 0f else text.measureText(label)
        val total = iconSize + (if (label.isEmpty()) 0f else layout.dp(CarRadioGridLayout.TAB_ICON_GAP_DP) + labelWidth)
        val start = box.centerX - total / 2f
        drawIcon(canvas, tab.icon, start + iconSize / 2f, box.centerY, iconSize, ink)
        if (label.isNotEmpty()) {
            canvas.drawText(label, start + iconSize + layout.dp(CarRadioGridLayout.TAB_ICON_GAP_DP), baseline(box.centerY), text)
        }
    }

    private fun drawCell(canvas: Canvas, layout: CarRadioGridLayout, box: CarRadioBox, cell: Cell, pressed: Boolean, logos: Logos) {
        val radius = layout.dp(20f)
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = when {
            cell.onAir -> ON_AIR_CARD
            pressed -> CHIP_PRESSED
            else -> CHIP
        }
        canvas.drawRoundRect(rect, radius, radius, fill)

        val pad = layout.dp(CarRadioGridLayout.CELL_PADDING_DP)
        val image = CarRadioBox(box.left + pad, box.top + pad, box.right - pad, box.top + pad + layout.imageHeight)
        val imageRadius = layout.dp(16f)
        val logo = if (cell.icon == 0) logos.bitmap(cell.logo) else null
        when {
            cell.icon != 0 -> {
                rect.set(image.left, image.top, image.right, image.bottom)
                fill.color = ACTION_CARD
                canvas.drawRoundRect(rect, imageRadius, imageRadius, fill)
                drawIcon(canvas, cell.icon, image.centerX, image.centerY, image.height * 0.42f, INK)
            }
            logo != null -> drawLogo(canvas, image, imageRadius, logo)
            else -> drawPlaceholder(canvas, layout, image, imageRadius, cell.title)
        }

        // Name, and under the station on the air whether it is playing.
        val nameTop = image.bottom + layout.dp(4f)
        text.typeface = bold
        text.textSize = layout.dp(NAME_TEXT_DP)
        text.color = INK
        text.textAlign = Paint.Align.CENTER
        val width = box.width - pad * 2 - layout.dp(8f)
        val status = cell.status
        val nameCenter = if (status == null) nameTop + layout.dp(CarRadioGridLayout.NAME_BLOCK_DP) / 2f else nameTop + layout.dp(12f)
        canvas.drawText(
            TextUtils.ellipsize(cell.title, text, width, TextUtils.TruncateAt.END).toString(),
            box.centerX, baseline(nameCenter), text
        )
        if (status != null) {
            text.typeface = medium
            text.textSize = layout.dp(STATUS_TEXT_DP)
            text.color = ACCENT
            canvas.drawText(status, box.centerX, baseline(nameTop + layout.dp(30f)), text)
        }
        text.textAlign = Paint.Align.LEFT

        if (cell.onAir) {
            val ring = layout.dp(3f)
            stroke.strokeWidth = ring
            stroke.color = ACCENT
            rect.set(box.left + ring / 2f, box.top + ring / 2f, box.right - ring / 2f, box.bottom - ring / 2f)
            canvas.drawRoundRect(rect, radius - ring / 2f, radius - ring / 2f, stroke)
        }
    }

    /**
     * A logo about square fills the card like the design's; a wide or tall one is fitted whole on
     * a light card so a dark or transparent logo stays readable.
     */
    private fun drawLogo(canvas: Canvas, box: CarRadioBox, radius: Float, logo: Bitmap) {
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = LOGO_CARD
        canvas.drawRoundRect(rect, radius, radius, fill)
        val inner = box.height - 0f
        val scale = minOf(box.width / logo.width, inner / logo.height)
        val w = logo.width * scale
        val h = logo.height * scale
        val left = box.centerX - w / 2f
        val top = box.centerY - h / 2f
        canvas.save()
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        rect.set(left, top, left + w, top + h)
        canvas.drawBitmap(logo, null, rect, bitmapPaint)
        canvas.restore()
    }

    /**
     * A station without a logo (or before it loads): its own colour as a soft gradient card, the
     * frequency or initials large, and a small broadcast mark in the corner, so it reads as a
     * station rather than a missing image.
     */
    private fun drawPlaceholder(canvas: Canvas, layout: CarRadioGridLayout, box: CarRadioBox, radius: Float, title: String) {
        val color = CarStationTile.colorFor(title)
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.shader = android.graphics.LinearGradient(
            box.left, box.top, box.right, box.bottom, lighten(color), darken(color), android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.shader = null

        // Broadcast waves in the top-right corner.
        val waveCenterX = box.right - layout.dp(22f)
        val waveCenterY = box.top + layout.dp(22f)
        stroke.color = 0x88FFFFFF.toInt()
        stroke.strokeWidth = layout.dp(2.5f)
        stroke.strokeCap = Paint.Cap.ROUND
        fill.color = 0xCCFFFFFF.toInt()
        canvas.drawCircle(waveCenterX, waveCenterY, layout.dp(3.5f), fill)
        for (step in 1..2) {
            val r = layout.dp(6f + step * 5f)
            rect.set(waveCenterX - r, waveCenterY - r, waveCenterX + r, waveCenterY + r)
            canvas.drawArc(rect, -60f, 120f, false, stroke)
            canvas.drawArc(rect, 120f, 120f, false, stroke)
        }

        val label = CarStationTile.label(title)
        text.typeface = bold
        text.color = 0xFFFFFFFF.toInt()
        text.textAlign = Paint.Align.CENTER
        text.textSize = box.height * when {
            label.length <= 2 -> 0.42f
            label.length <= 4 -> 0.34f
            else -> 0.28f
        }
        canvas.drawText(label, box.centerX, baseline(box.centerY), text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawMessage(canvas: Canvas, layout: CarRadioGridLayout, message: String, action: String?, pressed: Boolean) {
        val box = layout.message
        text.typeface = medium
        text.textSize = layout.dp(22f)
        text.color = MUTED
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(
            TextUtils.ellipsize(message, text, box.width, TextUtils.TruncateAt.END).toString(),
            box.centerX, baseline(box.centerY), text
        )
        if (action != null) {
            val button = layout.messageAction
            rect.set(button.left, button.top, button.right, button.bottom)
            fill.color = if (pressed) ACCENT_PRESSED else ACCENT
            canvas.drawRoundRect(rect, button.height / 2f, button.height / 2f, fill)
            text.typeface = bold
            text.color = ON_ACCENT
            canvas.drawText(action, button.centerX, baseline(button.centerY), text)
        }
        text.textAlign = Paint.Align.LEFT
    }

    /** A thin bar on the right edge while the grid has more above or below. */
    private fun drawScrollHint(canvas: Canvas, layout: CarRadioGridLayout, scroll: Float) {
        if (layout.maxScroll <= 0f) return
        val viewport = layout.gridViewport
        val track = viewport.height
        val thumb = (track * viewport.height / layout.gridHeight).coerceAtLeast(layout.dp(32f))
        val top = viewport.top + (track - thumb) * (scroll / layout.maxScroll)
        val x = viewport.right + layout.dp(10f)
        fill.color = SCROLL
        rect.set(x - layout.dp(2f), top, x + layout.dp(2f), top + thumb)
        canvas.drawRoundRect(rect, layout.dp(2f), layout.dp(2f), fill)
    }

    private fun drawIcon(canvas: Canvas, @DrawableRes res: Int, cx: Float, cy: Float, size: Float, tint: Int) {
        if (res == 0) return
        val drawable = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)?.mutate() } ?: return
        drawable.setTint(tint)
        val half = size / 2f
        drawable.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        drawable.draw(canvas)
    }

    private fun baseline(centerY: Float) = centerY - (text.descent() + text.ascent()) / 2f

    private fun lighten(color: Int) = blend(color, 0xFFFFFFFF.toInt(), 0.18f)
    private fun darken(color: Int) = blend(color, 0xFF000000.toInt(), 0.28f)

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int) = (((from shr shift) and 0xFF) * (1 - t) + ((to shr shift) and 0xFF) * t).toInt() and 0xFF
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** The radio design's car palette. */
        const val BACKGROUND = 0xFF0B0D10.toInt()
        const val CHIP = 0xFF1C2027.toInt()
        const val CHIP_PRESSED = 0xFF2A3038.toInt()
        const val ACTION_CARD = 0xFF262A31.toInt()
        const val LOGO_CARD = 0xFFF4F5F7.toInt()
        const val ON_AIR_CARD = 0xFF2A2116.toInt()
        const val ACCENT = 0xFFFFB35C.toInt()
        const val ACCENT_PRESSED = 0xFFE89E48.toInt()
        const val ON_ACCENT = 0xFF2A1A05.toInt()
        const val INK = 0xFFF3F5F7.toInt()
        const val TAB_INK = 0xFFE3E6EA.toInt()
        const val MUTED = 0xFFA9AFB8.toInt()
        const val SCROLL = 0x66F3F5F7

        const val TAB_TEXT_DP = 19f
        const val NAME_TEXT_DP = 18f
        const val STATUS_TEXT_DP = 14f

        @DrawableRes val R_BACK = dev.autobridge.R.drawable.ic_car_back
        @DrawableRes val R_SEARCH = dev.autobridge.R.drawable.ic_browser_search
    }
}
