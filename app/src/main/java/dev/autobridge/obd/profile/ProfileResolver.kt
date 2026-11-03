package dev.autobridge.obd.profile

/** Merges a profile with the built-in it is based on. */
object ProfileResolver {

    /**
     * [profile] with its base's definitions folded in. A gauge the profile defines replaces the
     * base's candidates for that gauge, in the base's position; a gauge only the profile has comes
     * after. The base must itself be flat (its own base already folded in), so one level is ever
     * followed; a missing base leaves the profile as it is. The result has no base left.
     */
    fun resolve(profile: VehicleProfile, lookup: (String) -> VehicleProfile?): VehicleProfile {
        val base = profile.base?.let(lookup)?.takeIf { it.base == null } ?: return profile
        val merged = ArrayList<PidDef>()
        val placed = HashSet<String>()
        for (gauge in base.gauges) {
            val own = profile.candidates(gauge)
            merged += own.ifEmpty { base.candidates(gauge) }
            placed += gauge
        }
        for (gauge in profile.gauges) if (gauge !in placed) merged += profile.candidates(gauge)
        return profile.copy(pids = merged, base = null)
    }
}
