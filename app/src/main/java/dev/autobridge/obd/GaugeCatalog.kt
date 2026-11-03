package dev.autobridge.obd

import dev.autobridge.obd.profile.BuiltInProfiles
import dev.autobridge.obd.profile.DerivedDef
import dev.autobridge.obd.profile.PidDef
import dev.autobridge.obd.profile.VehicleProfile

/** What the screens need to know about one gauge: its name, unit, dial range and zones. */
class GaugeInfo(
    val gauge: Gauge,
    val label: String,
    val unit: String,
    val plausible: ClosedFloatingPointRange<Float>?,
    val spec: GaugeSpec,
)

/**
 * The gauges of the vehicle profile in use. One profile is active at a time (set from the saved
 * choice by [ObdSettings.applyProfile]); the dials, the zone colours, the units and the editor all
 * ask here instead of knowing any gauge by name, which is what lets another make or model supply
 * its own.
 */
object GaugeCatalog {

    /** The active profile, already merged with its base. The generic OBD-II one until a choice is applied. */
    @Volatile
    var profile: VehicleProfile =
        checkNotNull(BuiltInProfiles.findResolved(BuiltInProfiles.GENERIC_ID)) { "built-in generic profile" }

    fun info(gauge: Gauge): GaugeInfo? {
        val p = profile
        p.candidates(gauge.id).firstOrNull()?.let { return fromPid(it) }
        p.derived.firstOrNull { it.gauge == gauge.id }?.let { return fromDerived(it) }
        return null
    }

    /** Every gauge the active profile can show: asked of the car, then derived. */
    fun gauges(): List<Gauge> = profile.allGauges.map { Gauge(it) }

    val derived: List<DerivedDef> get() = profile.derived

    private fun fromPid(pid: PidDef): GaugeInfo {
        val gauge = Gauge(pid.gauge)
        val lo = pid.plausibleMin
        val hi = pid.plausibleMax
        return GaugeInfo(
            gauge, pid.label, pid.unit,
            if (lo != null && hi != null) lo..hi else null,
            GaugeSpec(gauge, pid.min, pid.max, pid.zone),
        )
    }

    private fun fromDerived(d: DerivedDef): GaugeInfo {
        val gauge = Gauge(d.gauge)
        return GaugeInfo(gauge, d.label, d.unit, null, GaugeSpec(gauge, d.min, d.max, d.zone))
    }
}
