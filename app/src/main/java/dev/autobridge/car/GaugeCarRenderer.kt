package dev.autobridge.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.autobridge.obd.CellKind
import dev.autobridge.obd.Gauge
import dev.autobridge.obd.GaugeCarLayout
import dev.autobridge.obd.GaugeCell
import dev.autobridge.obd.GaugeFormat
import dev.autobridge.obd.GaugeSpec
import dev.autobridge.obd.Telemetry
import dev.autobridge.obd.Zone
import dev.autobridge.obd.ZoneColors
import kotlin.math.min

/**
 * Draws the gauges on the car surface: a dark card per gauge, its value in the zone's colour, and
 * a one-line header with the page name and the connection state.
 *
 * Plain canvas work with no state of its own besides the paints; the caller decides what to show
 * and when to redraw. Sizes derive from the cell, never from fixed pixels, so the same code serves
 * a wide head unit and a tall one.
 */
class GaugeCarRenderer(private val labelOf: (Gauge) -> String) {

    class Frame(
        val telemetry: Telemetry,
        val nowMs: Long,
        val imperial: Boolean,
        val page: dev.autobridge.obd.GaugePage,
        val pageIndex: Int,
        val pageCount: Int,
        val pageTitle: String,
        val status: String,
        /** Shown over the gauges when there is nothing to show yet, e.g. "choose an adapter". */
        val notice: String?,
        /** The worst reading right now, e.g. "Coolant 108 °C", or null while everything is fine. */
        val alert: String?,
        val density: Float,
    )

    private val background = 0xFF0E1216.toInt()
    private val card = 0xFF1A2027.toInt()
    private val text = 0xFFF2F5F7.toInt()
    private val muted = 0xFF8E99A4.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val box = RectF()

    fun draw(canvas: Canvas, width: Int, height: Int, area: RectF, frame: Frame) {
        canvas.drawColor(background)
        val layout = GaugeCarLayout.compute(area.left, area.top, area.right, area.bottom, frame.density, frame.page)
        drawHeader(canvas, area, layout.headerHeight, frame)
        for (cell in layout.cells) {
            val reading = frame.telemetry.value(cell.gauge, frame.nowMs)
            when (cell.kind) {
                CellKind.DIAL -> drawDial(canvas, cell, reading, frame)
                CellKind.TILE -> drawTile(canvas, cell, reading, frame)
            }
        }
        frame.notice?.let { drawNotice(canvas, area, it, frame.density) }
    }

    private fun drawHeader(canvas: Canvas, area: RectF, height: Float, frame: Frame) {
        label.textSize = height * 0.62f
        val baseline = area.top + height * 0.72f
        label.color = text
        label.typeface = Typeface.DEFAULT_BOLD
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(frame.pageTitle, area.left, baseline, label)
        label.typeface = Typeface.DEFAULT
        label.textAlign = Paint.Align.RIGHT
        if (frame.alert != null) {
            label.color = ZoneColors.DANGER
            label.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("! ${frame.alert}", area.right, baseline, label)
        } else {
            label.color = muted
            canvas.drawText(frame.status, area.right, baseline, label)
        }
        label.typeface = Typeface.DEFAULT
        label.textAlign = Paint.Align.LEFT
        label.color = muted

        // Page dots, centred.
        val dot = height * 0.12f
        val total = frame.pageCount
        var x = (area.left + area.right) / 2f - (total - 1) * dot * 2.2f
        for (i in 0 until total) {
            fill.color = if (i == frame.pageIndex) text else muted
            canvas.drawCircle(x, area.top + height * 0.5f, dot, fill)
            x += dot * 4.4f
        }
    }

    private fun drawCard(canvas: Canvas, cell: GaugeCell, radius: Float, outline: Int?, density: Float) {
        box.set(cell.left, cell.top, cell.right, cell.bottom)
        fill.color = card
        canvas.drawRoundRect(box, radius, radius, fill)
        if (outline != null) {
            stroke.color = outline
            stroke.strokeWidth = 2.5f * density
            canvas.drawRoundRect(box, radius, radius, stroke)
        }
    }

    private fun zoneOf(gauge: Gauge, reading: Float?): Zone? =
        if (reading == null) null else runCatching { GaugeSpec.of(gauge).zone(reading) }.getOrNull()

    private fun colorOf(zone: Zone?): Int = zone?.let { ZoneColors.of(it) } ?: muted

    private fun drawDial(canvas: Canvas, cell: GaugeCell, reading: Float?, frame: Frame) {
        val zone = zoneOf(cell.gauge, reading)
        val color = if (zone == null) muted else ZoneColors.of(zone)
        drawCard(canvas, cell, 18f * frame.density, if (zone == Zone.DANGER) color else null, frame.density)

        val size = min(cell.width, cell.height)
        val thickness = size * 0.075f
        val cx = (cell.left + cell.right) / 2f
        val cy = (cell.top + cell.bottom) / 2f
        val radius = size / 2f - thickness * 1.6f
        box.set(cx - radius, cy - radius, cx + radius, cy + radius)
        stroke.strokeWidth = thickness
        stroke.color = 0xFF2A333C.toInt()
        canvas.drawArc(box, 135f, 270f, false, stroke)
        if (reading != null) {
            stroke.color = color
            canvas.drawArc(box, 135f, 270f * GaugeSpec.of(cell.gauge).fraction(reading), false, stroke)
        }

        val shown = if (reading == null) "—" else GaugeFormat.text(cell.gauge, reading, frame.imperial)
        fit(value, shown, radius * 1.5f, size * 0.30f)
        value.color = if (reading == null) muted else text
        value.textAlign = Paint.Align.CENTER
        canvas.drawText(shown, cx, cy + value.textSize * 0.2f, value)

        label.textAlign = Paint.Align.CENTER
        label.color = muted
        label.textSize = size * 0.085f
        canvas.drawText(GaugeFormat.unit(cell.gauge, frame.imperial), cx, cy + value.textSize * 0.2f + label.textSize * 1.4f, label)
        canvas.drawText(labelOf(cell.gauge), cx, cell.bottom - thickness * 0.8f, label)
        label.textAlign = Paint.Align.LEFT
    }

    private fun drawTile(canvas: Canvas, cell: GaugeCell, reading: Float?, frame: Frame) {
        val zone = zoneOf(cell.gauge, reading)
        val color = if (zone == null) muted else ZoneColors.of(zone)
        drawCard(canvas, cell, 14f * frame.density, if (zone == Zone.DANGER) color else null, frame.density)
        val pad = min(cell.width, cell.height) * 0.1f
        // Text is sized from this, not from the height alone: a tile taller than it is wide would
        // otherwise get a name too big for its width and a number with no width left at all.
        val base = min(cell.height, cell.width * 0.6f)
        val inner = cell.width - pad * 2f

        val name = labelOf(cell.gauge)
        label.color = muted
        label.textAlign = Paint.Align.LEFT
        fit(label, name, inner, base * 0.2f)
        canvas.drawText(name, cell.left + pad, cell.top + pad + label.textSize * 0.85f, label)

        val shown = if (reading == null) "—" else GaugeFormat.text(cell.gauge, reading, frame.imperial)
        val unit = GaugeFormat.unit(cell.gauge, frame.imperial)
        label.textSize = base * 0.14f
        val unitWidth = label.measureText(unit)
        fit(value, shown, (inner - unitWidth - pad * 0.4f).coerceAtLeast(base * 0.3f), base * 0.46f)
        value.color = if (reading == null) muted else color
        value.textAlign = Paint.Align.LEFT
        val baseline = cell.bottom - pad - base * 0.12f
        canvas.drawText(shown, cell.left + pad, baseline, value)
        canvas.drawText(unit, cell.left + pad + value.measureText(shown) + pad * 0.4f, baseline, label)

        // A thin bar along the bottom: how far along the gauge's own range the value is.
        if (reading != null) {
            val barHeight = cell.height * 0.05f
            val barTop = cell.bottom - pad * 0.55f - barHeight
            box.set(cell.left + pad, barTop, cell.left + pad + inner, barTop + barHeight)
            fill.color = 0xFF2A333C.toInt()
            canvas.drawRoundRect(box, barHeight, barHeight, fill)
            box.right = cell.left + pad + inner * GaugeSpec.of(cell.gauge).fraction(reading)
            fill.color = color
            canvas.drawRoundRect(box, barHeight, barHeight, fill)
        }
    }

    private fun drawNotice(canvas: Canvas, area: RectF, notice: String, density: Float) {
        val bandHeight = 74f * density
        val top = (area.top + area.bottom) / 2f - bandHeight / 2f
        box.set(area.left, top, area.right, top + bandHeight)
        fill.color = 0xE60E1216.toInt()
        canvas.drawRect(box, fill)
        label.color = text
        label.textAlign = Paint.Align.CENTER
        label.textSize = 22f * density
        fit(label, notice, area.width() - 32f * density, label.textSize)
        canvas.drawText(notice, (area.left + area.right) / 2f, top + bandHeight * 0.62f, label)
        label.textAlign = Paint.Align.LEFT
        label.color = muted
    }

    /** Sets [paint]'s size to [preferred], shrunk until [text] fits in [maxWidth]. */
    private fun fit(paint: Paint, text: String, maxWidth: Float, preferred: Float) {
        paint.textSize = preferred
        val measured = paint.measureText(text)
        if (measured > maxWidth && measured > 0f) paint.textSize = preferred * maxWidth / measured
    }
}
