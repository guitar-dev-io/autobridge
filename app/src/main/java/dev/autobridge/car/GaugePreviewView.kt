package dev.autobridge.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import dev.autobridge.R
import dev.autobridge.obd.Gauge
import dev.autobridge.obd.GaugeCatalog
import dev.autobridge.obd.GaugeLabels
import dev.autobridge.obd.GaugePage
import dev.autobridge.obd.GaugeStyle
import dev.autobridge.obd.Telemetry

/**
 * The phone's preview of a gauge page: the car's own renderer, drawn with made-up readings, so the
 * layout and style chosen on the phone are what the head unit will show. Not a mock-up that can
 * drift from the real thing, because there is only one drawing code.
 */
internal class GaugePreviewView(context: Context) : View(context) {
    var page: GaugePage? = null
    var pageIndex = 0
    var pageCount = 1
    var style: GaugeStyle = GaugeStyle.DEFAULT
    var imperial = false

    private val renderer = GaugeCarRenderer { gauge -> GaugeLabels.text(context, gauge) }
    private val area = RectF()

    override fun onDraw(canvas: Canvas) {
        val shown = page ?: return
        val density = resources.displayMetrics.density
        val inset = 6f * density
        area.set(inset, inset * 0.5f, width - inset, height - inset)
        val now = SystemClock.elapsedRealtime()
        val frame = GaugeCarRenderer.Frame(
            telemetry = sample(now),
            nowMs = now,
            imperial = imperial,
            page = shown,
            pageIndex = pageIndex,
            pageCount = pageCount,
            pageTitle = context.getString(R.string.car_gauges_page_n, (pageIndex + 1).toString(), pageCount.toString()),
            status = context.getString(R.string.obd_status_live),
            notice = null,
            alert = null,
            density = density * 0.62f,
            style = style,
            look = HomeDashboardLook.current(),
        )
        renderer.draw(canvas, area, frame)
    }

    /** Readings that exercise every colour: mostly normal, coolant a little warm, gearbox oil high. */
    private fun sample(now: Long): Telemetry {
        val values = listOf(
            Gauge.RPM to 2100f, Gauge.SPEED to 72f, Gauge.COOLANT to 99f, Gauge.ATF to 108f,
            Gauge.BATTERY to 14.1f, Gauge.LOAD to 38f, Gauge.INTAKE to 38f, Gauge.THROTTLE to 22f,
            Gauge.FUEL_LEVEL to 64f, Gauge.AMBIENT to 31f, Gauge.BARO to 101f, Gauge.MAP to 150f,
        )
        // Gauges worked out from others (boost from MAP and the barometer) follow from these.
        val base = values.fold(Telemetry()) { telemetry, (gauge, value) -> telemetry.with(gauge, value, now) }
        return values.fold(base) { telemetry, (gauge, _) -> telemetry.refreshDerived(GaugeCatalog.derived, gauge, now) }
    }
}
