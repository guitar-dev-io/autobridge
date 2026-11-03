package dev.autobridge.obd.profile

/**
 * When a reading is worth a second look. Between [safeMin] and [safeMax] is normal; outside it the
 * value is a caution until it passes [cautionMin] / [cautionMax]. A side that cannot be a problem
 * ([lowIsBad] / [highIsBad] false) is always normal, and [coldBelow] marks a warm-up band.
 */
data class ZoneRange(
    val safeMin: Float,
    val safeMax: Float,
    val cautionMin: Float,
    val cautionMax: Float,
    val lowIsBad: Boolean,
    val highIsBad: Boolean,
    val coldBelow: Float? = null,
)

/**
 * One way of reading one gauge from the car. Several definitions with the same [gauge] are
 * candidates in order: the first that the car answers is kept, the others are the fallbacks (for
 * example the same temperature asked of the transmission module and then the engine module).
 *
 * [request] is what is sent, as hex: `01xx` for a standard parameter or `22xxxx` for a maker's own.
 * Only those two read-only services are accepted; see [ProfileValidator].
 */
data class PidDef(
    val gauge: String,
    val label: String,
    val request: String,
    val bytes: Int,
    /** The CAN id to talk to (`7E0` engine, `7E1` transmission), or null to leave the adapter as it is. */
    val header: String?,
    val formula: String,
    val unit: String,
    /** The dial's own range, for the arc and the bar. */
    val min: Float,
    val max: Float,
    /** A value outside this is a bad read and is dropped; null accepts anything. */
    val plausibleMin: Float? = null,
    val plausibleMax: Float? = null,
    val zone: ZoneRange? = null,
) {
    /** The start of a good answer: the service plus 0x40, then the same parameter id. */
    val reply: String
        get() = (request.substring(0, 2).toInt(16) + 0x40).toString(16).uppercase().padStart(2, '0') + request.substring(2)
}

/**
 * A gauge worked out from other gauges rather than asked of the car, such as boost from the manifold
 * and barometric pressures. [sources] are gauge ids bound to the formula's `A`, `B`...; the value
 * exists only while every source is fresh.
 */
data class DerivedDef(
    val gauge: String,
    val label: String,
    val unit: String,
    val sources: List<String>,
    val formula: String,
    val min: Float,
    val max: Float,
    val zone: ZoneRange? = null,
)

/**
 * What a vehicle can be asked, as data. [base] names a built-in profile whose definitions this one
 * starts from (a gauge defined here replaces the base's for that gauge), so a profile for another
 * model of a family only lists what differs.
 */
data class VehicleProfile(
    val id: String,
    val name: String,
    val make: String,
    val model: String,
    val years: String,
    /** False until someone has checked every definition against a real vehicle. */
    val verified: Boolean,
    val notes: String,
    val base: String?,
    val pids: List<PidDef>,
    val derived: List<DerivedDef> = emptyList(),
) {
    /** The gauges this profile can ask the car for, in the order they are first listed. */
    val gauges: List<String> get() = pids.map { it.gauge }.distinct()

    /** Every gauge it can show: asked of the car, then derived. */
    val allGauges: List<String> get() = gauges + derived.map { it.gauge }

    fun candidates(gauge: String): List<PidDef> = pids.filter { it.gauge == gauge }
}
