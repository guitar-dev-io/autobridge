package dev.autobridge.obd

/** How a dial draws its value. */
enum class DialStyle { ARC, RING, NEEDLE, DIGITAL }

/** How a tile draws its value. */
enum class TileStyle { BAR, STRIPE, PLAIN }

/**
 * Which colours carry a reading's state: the zone colours (green, amber, red), the theme's own
 * accent, or plain text. Danger is red in every mode, because that one must never depend on taste.
 */
enum class ColorMode { ZONES, ACCENT, MONO }

/** How large the gauge text is drawn, as a multiplier on the size the cell would give it. */
enum class TextSize(val scale: Float) { SMALL(0.85f), NORMAL(1f), LARGE(1.15f) }

/**
 * The look of the gauges, chosen on the phone and read by the car (and the phone's preview). The
 * colours of surfaces and text are not here: they come from the app's own theme, so a gauge page
 * follows whatever look the rest of the car screens have.
 */
data class GaugeStyle(
    val dial: DialStyle = DialStyle.ARC,
    val tile: TileStyle = TileStyle.BAR,
    val color: ColorMode = ColorMode.ZONES,
    val size: TextSize = TextSize.NORMAL,
) {
    /** `ARC,BAR,ZONES,NORMAL`: four names, so a half-written value can only fall back, never crash. */
    fun encode(): String = "${dial.name},${tile.name},${color.name},${size.name}"

    companion object {
        val DEFAULT = GaugeStyle()

        fun decode(text: String?): GaugeStyle {
            val parts = text?.split(',')?.map { it.trim() } ?: return DEFAULT
            fun <T : Enum<T>> pick(index: Int, values: Array<T>, fallback: T): T =
                values.firstOrNull { it.name == parts.getOrNull(index) } ?: fallback
            return GaugeStyle(
                dial = pick(0, DialStyle.entries.toTypedArray(), DEFAULT.dial),
                tile = pick(1, TileStyle.entries.toTypedArray(), DEFAULT.tile),
                color = pick(2, ColorMode.entries.toTypedArray(), DEFAULT.color),
                size = pick(3, TextSize.entries.toTypedArray(), DEFAULT.size),
            )
        }
    }
}
