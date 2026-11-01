package dev.autobridge.obd

/** The colour each [Zone] is drawn in, on the phone and on the car. */
object ZoneColors {
    const val COLD = 0xFF00AAFF.toInt()
    const val NORMAL = 0xFF00D977.toInt()
    const val CAUTION = 0xFFFFAA00.toInt()
    const val DANGER = 0xFFFF3B30.toInt()

    fun of(zone: Zone): Int = when (zone) {
        Zone.COLD -> COLD
        Zone.NORMAL -> NORMAL
        Zone.CAUTION -> CAUTION
        Zone.DANGER -> DANGER
    }
}
