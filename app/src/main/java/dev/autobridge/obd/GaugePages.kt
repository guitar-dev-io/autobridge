package dev.autobridge.obd

enum class CellKind { DIAL, TILE }

/**
 * The shapes a page of gauges can take. Each lists its slots in order (a dial or a tile), and a page
 * keeps one gauge per slot.
 */
enum class PageLayout(val slots: List<CellKind>) {
    DIAL1(listOf(CellKind.DIAL)),
    DIALS2(listOf(CellKind.DIAL, CellKind.DIAL)),
    DIALS3(listOf(CellKind.DIAL, CellKind.DIAL, CellKind.DIAL)),
    DIALS2_TILES3(listOf(CellKind.DIAL, CellKind.DIAL, CellKind.TILE, CellKind.TILE, CellKind.TILE)),
    DIAL1_TILES4(listOf(CellKind.DIAL, CellKind.TILE, CellKind.TILE, CellKind.TILE, CellKind.TILE)),
    TILES4(List(4) { CellKind.TILE }),
    TILES6(List(6) { CellKind.TILE }),
    TILES8(List(8) { CellKind.TILE });

    val size: Int get() = slots.size
}

/** One page: a [layout] and the gauge shown in each of its slots. */
data class GaugePage(val layout: PageLayout, val gauges: List<Gauge>)

/**
 * The pages the car shows, and how they are saved.
 *
 * Saved as text, `LAYOUT:GAUGE,GAUGE;LAYOUT:GAUGE`, so it needs no JSON library and a half-written
 * or hand-edited value can never crash the car screen: anything that does not parse falls back to
 * [DEFAULT], and a page with too few or too many gauges is repaired rather than dropped.
 */
object GaugePages {
    const val MAX_PAGES = 6

    /** The two pages the car used before pages could be arranged: drive, then engine. */
    val DEFAULT: List<GaugePage> = listOf(
        GaugePage(
            PageLayout.DIALS2_TILES3,
            listOf(Gauge.RPM, Gauge.SPEED, Gauge.COOLANT, Gauge.ATF, Gauge.BATTERY),
        ),
        GaugePage(
            PageLayout.TILES8,
            listOf(
                Gauge.BOOST, Gauge.LOAD, Gauge.INTAKE, Gauge.MAP,
                Gauge.THROTTLE, Gauge.FUEL_LEVEL, Gauge.AMBIENT, Gauge.BARO,
            ),
        ),
    )

    fun encode(pages: List<GaugePage>): String =
        pages.joinToString(";") { page -> page.layout.name + ":" + page.gauges.joinToString(",") { it.name } }

    fun decode(text: String?): List<GaugePage> {
        if (text.isNullOrBlank()) return DEFAULT
        val pages = text.split(';').mapNotNull(::decodePage).take(MAX_PAGES)
        return pages.ifEmpty { DEFAULT }
    }

    private fun decodePage(part: String): GaugePage? {
        val layout = runCatching { PageLayout.valueOf(part.substringBefore(':').trim()) }.getOrNull() ?: return null
        val named = part.substringAfter(':', "").split(',').mapNotNull { name ->
            runCatching { Gauge.valueOf(name.trim()) }.getOrNull()
        }
        return fit(layout, named)
    }

    /** [layout] with exactly one gauge per slot: extras are cut, gaps are filled with gauges not yet used. */
    fun fit(layout: PageLayout, gauges: List<Gauge>): GaugePage {
        val kept = gauges.take(layout.size).toMutableList()
        for (candidate in Gauge.entries) {
            if (kept.size >= layout.size) break
            if (candidate !in kept) kept += candidate
        }
        // More slots than gauges exist (never today, but cheap to survive): repeat from the start.
        var i = 0
        while (kept.size < layout.size) kept += Gauge.entries[i++ % Gauge.entries.size]
        return GaugePage(layout, kept)
    }

    /** The same page on another layout, keeping the gauges the new layout still has room for. */
    fun withLayout(page: GaugePage, layout: PageLayout): GaugePage = fit(layout, page.gauges)

    fun withGauge(page: GaugePage, slot: Int, gauge: Gauge): GaugePage =
        page.copy(gauges = page.gauges.toMutableList().also { if (slot in it.indices) it[slot] = gauge })
}
