package dev.autobridge.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import dev.autobridge.obd.CellKind
import dev.autobridge.obd.ColorMode
import dev.autobridge.obd.DialStyle
import dev.autobridge.obd.Gauge
import dev.autobridge.obd.GaugeCarLayout
import dev.autobridge.obd.GaugeCell
import dev.autobridge.obd.GaugeFormat
import dev.autobridge.obd.GaugePage
import dev.autobridge.obd.GaugeSpec
import dev.autobridge.obd.GaugeStyle
import dev.autobridge.obd.Telemetry
import dev.autobridge.obd.TileStyle
import dev.autobridge.obd.Zone
import dev.autobridge.obd.ZoneColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the gauges: a card per gauge, its value, and a one-line header with the page and the
 * connection state. Used on the car surface and, with sample values, in the phone's preview.
 *
 * Surfaces, text, the accent, card shape and corner radius all come from [HomeDashboardLook], the
 * same look the car home is drawn with, so a gauge page follows the app's theme (dark, light, card
 * style, text size) instead of carrying colours of its own. Only the state colours are its own, and
 * those are darkened on a light theme so they still read. How a dial or tile is drawn, and which
 * colours carry the state, is the [GaugeStyle] the user chose.
 *
 * Plain canvas work with no state besides the paints; the caller decides what to show and when.
 */
internal class GaugeCarRenderer(private val labelOf: (Gauge) -> String) {

    class Frame(
        val telemetry: Telemetry,
        val nowMs: Long,
        val imperial: Boolean,
        val page: GaugePage,
        val pageIndex: Int,
        val pageCount: Int,
        val pageTitle: String,
        val status: String,
        /** Shown over the gauges when there is nothing to show yet, e.g. "choose an adapter". */
        val notice: String?,
        /** The worst reading right now, e.g. "Coolant 108 °C", or null while everything is fine. */
        val alert: String?,
        val density: Float,
        val style: GaugeStyle,
        val look: HomeDashboardLook,
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val box = RectF()

    fun draw(canvas: Canvas, area: RectF, frame: Frame) {
        canvas.drawColor(frame.look.background)
        val layout = GaugeCarLayout.compute(area.left, area.top, area.right, area.bottom, frame.density, frame.page)
        drawHeader(canvas, area, layout.headerHeight, frame)
        for (cell in layout.cells) {
            val reading = frame.telemetry.value(cell.gauge, frame.nowMs)
            when (cell.kind) {
                CellKind.DIAL -> drawDial(canvas, cell, reading, frame)
                CellKind.TILE -> drawTile(canvas, cell, reading, frame)
            }
        }
        frame.notice?.let { drawNotice(canvas, area, it, frame) }
    }

    // --- Colours -------------------------------------------------------------------------------

    private fun danger(look: HomeDashboardLook) = look.hue(ZoneColors.DANGER)

    /** The colour of a reading in [zone] (null: no reading) under the chosen colour mode. */
    private fun stateColor(frame: Frame, zone: Zone?): Int {
        val look = frame.look
        if (zone == null) return look.textTertiary
        if (zone == Zone.DANGER) return danger(look)
        return when (frame.style.color) {
            ColorMode.ZONES -> look.hue(ZoneColors.of(zone))
            ColorMode.ACCENT -> look.accent
            ColorMode.MONO -> look.textPrimary
        }
    }

    private fun zoneOf(gauge: Gauge, reading: Float?): Zone? =
        if (reading == null) null else runCatching { GaugeSpec.of(gauge).zone(reading) }.getOrNull()

    /** Text size as the user and the theme want it: the style's multiplier and the car text scale, capped. */
    private fun textMul(frame: Frame): Float = frame.style.size.scale * frame.look.textScale.coerceIn(0.9f, 1.3f)

    // --- Header --------------------------------------------------------------------------------

    private fun drawHeader(canvas: Canvas, area: RectF, height: Float, frame: Frame) {
        val look = frame.look
        label.textSize = height * 0.62f
        val baseline = area.top + height * 0.72f
        label.color = look.textPrimary
        label.typeface = Typeface.DEFAULT_BOLD
        label.textAlign = Paint.Align.LEFT
        canvas.drawText(frame.pageTitle, area.left, baseline, label)
        label.typeface = Typeface.DEFAULT
        label.textAlign = Paint.Align.RIGHT
        if (frame.alert != null) {
            label.color = danger(look)
            label.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("! ${frame.alert}", area.right, baseline, label)
        } else {
            label.color = look.textSecondary
            canvas.drawText(frame.status, area.right, baseline, label)
        }
        label.typeface = Typeface.DEFAULT
        label.textAlign = Paint.Align.LEFT

        // Page dots, centred.
        val dot = height * 0.12f
        val total = frame.pageCount
        var x = (area.left + area.right) / 2f - (total - 1) * dot * 2.2f
        for (i in 0 until total) {
            fill.color = if (i == frame.pageIndex) look.textPrimary else look.textTertiary
            canvas.drawCircle(x, area.top + height * 0.5f, dot, fill)
            x += dot * 4.4f
        }
    }

    private fun drawCard(canvas: Canvas, cell: GaugeCell, radius: Float, outline: Int?, frame: Frame) {
        val look = frame.look
        box.set(cell.left, cell.top, cell.right, cell.bottom)
        fill.color = look.card
        canvas.drawRoundRect(box, radius, radius, fill)
        if (look.hasCardEdge) {
            stroke.color = look.cardEdge
            stroke.strokeWidth = frame.density
            canvas.drawRoundRect(box, radius, radius, stroke)
        }
        if (outline != null) {
            stroke.color = outline
            stroke.strokeWidth = 2.5f * frame.density
            canvas.drawRoundRect(box, radius, radius, stroke)
        }
    }

    // --- Dials ---------------------------------------------------------------------------------

    private fun drawDial(canvas: Canvas, cell: GaugeCell, reading: Float?, frame: Frame) {
        val look = frame.look
        val spec = GaugeSpec.of(cell.gauge)
        val zone = zoneOf(cell.gauge, reading)
        val color = stateColor(frame, zone)
        drawCard(canvas, cell, look.cardRadius * frame.density, if (zone == Zone.DANGER) color else null, frame)

        val size = min(cell.width, cell.height)
        val mul = textMul(frame)
        val shown = if (reading == null) "—" else GaugeFormat.text(cell.gauge, reading, frame.imperial)
        val unit = GaugeFormat.unit(cell.gauge, frame.imperial)
        val fraction = if (reading == null) null else spec.fraction(reading)
        val cx = (cell.left + cell.right) / 2f
        val cy = (cell.top + cell.bottom) / 2f
        val thickness = size * 0.075f
        val radius = size / 2f - thickness * 1.6f
        val name = labelOf(cell.gauge)

        value.color = if (reading == null) look.textTertiary else look.textPrimary
        value.textAlign = Paint.Align.CENTER
        label.textAlign = Paint.Align.CENTER
        label.color = look.textSecondary

        when (frame.style.dial) {
            DialStyle.ARC, DialStyle.RING -> {
                val ring = frame.style.dial == DialStyle.RING
                box.set(cx - radius, cy - radius, cx + radius, cy + radius)
                stroke.strokeWidth = thickness
                stroke.color = look.progressTrack
                val start = if (ring) -90f else 135f
                val sweep = if (ring) 360f else 270f
                canvas.drawArc(box, start, sweep, false, stroke)
                if (fraction != null) {
                    stroke.color = color
                    canvas.drawArc(box, start, sweep * fraction, false, stroke)
                }
                fit(value, shown, radius * 1.5f, size * 0.30f * mul)
                canvas.drawText(shown, cx, cy + value.textSize * 0.2f, value)
                label.textSize = size * 0.085f * mul
                canvas.drawText(unit, cx, cy + value.textSize * 0.2f + label.textSize * 1.4f, label)
                canvas.drawText(name, cx, cell.bottom - thickness * 0.8f, label)
            }
            DialStyle.NEEDLE -> {
                box.set(cx - radius, cy - radius, cx + radius, cy + radius)
                stroke.strokeWidth = thickness * 0.7f
                stroke.color = look.progressTrack
                canvas.drawArc(box, 135f, 270f, false, stroke)
                if (fraction != null) {
                    val angle = Math.toRadians((135f + 270f * fraction).toDouble())
                    stroke.color = color
                    stroke.strokeWidth = thickness * 0.55f
                    canvas.drawLine(
                        cx, cy,
                        cx + (radius * 0.86f * cos(angle)).toFloat(),
                        cy + (radius * 0.86f * sin(angle)).toFloat(),
                        stroke
                    )
                }
                fill.color = color
                canvas.drawCircle(cx, cy, thickness * 0.9f, fill)
                fit(value, shown, radius * 1.3f, size * 0.20f * mul)
                canvas.drawText(shown, cx, cy + radius * 0.6f, value)
                label.textSize = size * 0.075f * mul
                canvas.drawText("$name  $unit", cx, cell.bottom - thickness * 0.8f, label)
            }
            DialStyle.DIGITAL -> {
                val pad = size * 0.1f
                label.textSize = size * 0.09f * mul
                fit(label, name, cell.width - pad * 2f, label.textSize)
                canvas.drawText(name, cx, cell.top + pad + label.textSize * 0.85f, label)
                fit(value, shown, cell.width - pad * 2f, size * 0.46f * mul)
                canvas.drawText(shown, cx, cy + value.textSize * 0.3f, value)
                label.textSize = size * 0.10f * mul
                canvas.drawText(unit, cx, cy + value.textSize * 0.3f + label.textSize * 1.5f, label)
                if (fraction != null) {
                    val barHeight = size * 0.045f
                    val barTop = cell.bottom - pad - barHeight
                    val full = cell.width - pad * 2f
                    box.set(cell.left + pad, barTop, cell.left + pad + full, barTop + barHeight)
                    fill.color = look.progressTrack
                    canvas.drawRoundRect(box, barHeight, barHeight, fill)
                    box.right = cell.left + pad + full * fraction
                    fill.color = color
                    canvas.drawRoundRect(box, barHeight, barHeight, fill)
                }
            }
        }
        label.textAlign = Paint.Align.LEFT
    }

    // --- Tiles ---------------------------------------------------------------------------------

    private fun drawTile(canvas: Canvas, cell: GaugeCell, reading: Float?, frame: Frame) {
        val look = frame.look
        val zone = zoneOf(cell.gauge, reading)
        val color = stateColor(frame, zone)
        drawCard(canvas, cell, look.tileRadius * frame.density, if (zone == Zone.DANGER) color else null, frame)
        val pad = min(cell.width, cell.height) * 0.1f
        // Text is sized from this, not from the height alone: a tile taller than it is wide would
        // otherwise get a name too big for its width and a number with no width left at all.
        val base = min(cell.height, cell.width * 0.6f) * textMul(frame)
        val tileStyle = frame.style.tile
        val stripe = if (tileStyle == TileStyle.STRIPE) pad * 0.45f else 0f
        val left = cell.left + pad + if (stripe > 0f) stripe + pad * 0.6f else 0f
        val inner = cell.right - pad - left

        if (stripe > 0f) {
            box.set(cell.left + pad, cell.top + pad, cell.left + pad + stripe, cell.bottom - pad)
            fill.color = color
            canvas.drawRoundRect(box, stripe, stripe, fill)
        }

        val name = labelOf(cell.gauge)
        label.color = look.textSecondary
        label.textAlign = Paint.Align.LEFT
        fit(label, name, inner, base * 0.2f)
        canvas.drawText(name, left, cell.top + pad + label.textSize * 0.85f, label)

        val shown = if (reading == null) "—" else GaugeFormat.text(cell.gauge, reading, frame.imperial)
        val unit = GaugeFormat.unit(cell.gauge, frame.imperial)
        label.textSize = base * 0.14f
        val unitWidth = label.measureText(unit)
        fit(value, shown, (inner - unitWidth - pad * 0.4f).coerceAtLeast(base * 0.3f), base * 0.46f)
        value.color = if (reading == null) look.textTertiary else color
        value.textAlign = Paint.Align.LEFT
        val baseline = cell.bottom - pad - base * (if (tileStyle == TileStyle.BAR) 0.12f else 0.05f)
        canvas.drawText(shown, left, baseline, value)
        canvas.drawText(unit, left + value.measureText(shown) + pad * 0.4f, baseline, label)

        // A thin bar along the bottom: how far along the gauge's own range the value is.
        if (tileStyle == TileStyle.BAR && reading != null) {
            val barHeight = cell.height * 0.05f
            val barTop = cell.bottom - pad * 0.55f - barHeight
            box.set(left, barTop, left + inner, barTop + barHeight)
            fill.color = look.progressTrack
            canvas.drawRoundRect(box, barHeight, barHeight, fill)
            box.right = left + inner * GaugeSpec.of(cell.gauge).fraction(reading)
            fill.color = color
            canvas.drawRoundRect(box, barHeight, barHeight, fill)
        }
    }

    private fun drawNotice(canvas: Canvas, area: RectF, notice: String, frame: Frame) {
        val look = frame.look
        val bandHeight = 74f * frame.density
        val top = (area.top + area.bottom) / 2f - bandHeight / 2f
        box.set(area.left, top, area.right, top + bandHeight)
        fill.color = (look.background and 0x00FFFFFF) or 0xE6000000.toInt()
        canvas.drawRect(box, fill)
        label.color = look.textPrimary
        label.textAlign = Paint.Align.CENTER
        fit(label, notice, area.width() - 32f * frame.density, 22f * frame.density)
        canvas.drawText(notice, (area.left + area.right) / 2f, top + bandHeight * 0.62f, label)
        label.textAlign = Paint.Align.LEFT
    }

    /** Sets [paint]'s size to [preferred], shrunk until [text] fits in [maxWidth]. */
    private fun fit(paint: Paint, text: String, maxWidth: Float, preferred: Float) {
        paint.textSize = preferred
        val measured = paint.measureText(text)
        if (measured > maxWidth && measured > 0f) paint.textSize = preferred * maxWidth / measured
    }
}
