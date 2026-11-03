package dev.autobridge.obd

import dev.autobridge.obd.profile.BuiltInProfiles

/** Carries a setting saved by an older version over to the vehicle profiles. */
object ProfileMigration {

    /**
     * The profile id for the Ranger gearbox choice older versions saved ("T5", "T6", "NEXT_GEN").
     * Nothing saved, or something unknown, gives the generic OBD-II profile.
     */
    fun fromLegacy(transmission: String?): String = when (transmission) {
        "T5" -> "ford_ranger_pjpk"
        "T6" -> "ford_ranger_px"
        "NEXT_GEN" -> "ford_ranger_next_gen"
        else -> BuiltInProfiles.GENERIC_ID
    }
}
