package dev.autobridge.obd.profile

/**
 * The profiles that ship with the app, written in the same text a shared profile uses, so they are
 * read by the same code and checked by the same rules. [GENERIC] is the standard OBD-II set that
 * nearly every petrol and diesel car since 2008 answers the same way; the Ranger profiles add what
 * Ford answers on its own identifiers.
 *
 * The Ford ranges and PIDs come from the Ford OBD2 Smart Gauge project (see the README credits).
 */
object BuiltInProfiles {

    const val GENERIC_ID = "generic_obd2"

    private val GENERIC_TEXT = """
        id: generic_obd2
        name: Generic OBD-II
        make: Any
        model: Any
        years: 2008-
        verified: yes
        notes: Standard live data that every compliant car answers the same way.
        derived: boost | Boost | psi | map,baro | (A-B)*0.145038 | -15..25 | -5,12,-10,18,h
        pid: rpm | RPM | 010C | 2 | - | (A*256+B)/4 | rpm | 0..5000 | 0..9000 | 600,4500,300,5500,lh
        pid: speed | Speed | 010D | 1 | - | A | km/h | 0..200 | 0..255 | 0,120,0,140,h
        pid: coolant | Coolant | 0105 | 1 | - | A-40 | °C | 40..120 | -40..215 | 80,95,70,105,h,70
        pid: load | Engine load | 0104 | 1 | - | A*100/255 | % | 0..100 | - | 15,85,10,95,h
        pid: intake | Intake air | 010F | 1 | - | A-40 | °C | -10..100 | -40..215 | 15,50,-10,70,h
        pid: map | Manifold pressure | 010B | 1 | - | A | kPa | 0..255 | - | 20,105,10,120,lh
        pid: baro | Barometer | 0133 | 1 | - | A | kPa | 70..120 | - | 85,110,70,120,lh
        pid: throttle | Throttle | 0111 | 1 | - | A*100/255 | % | 0..100 | - | -
        pid: fuel | Fuel level | 012F | 1 | - | A*100/255 | % | 0..100 | - | 25,100,15,100,l
        pid: ambient | Outside | 0146 | 1 | - | A-40 | °C | -10..60 | -40..215 | -10,45,-20,55,lh
        pid: battery | Battery | 0142 | 2 | - | (A*256+B)/1000 | V | 10..16 | 6..20 | 13.8,14.4,12.5,15,lh
    """.trimIndent()

    // Fords answer battery voltage on their own identifier when the standard one is silent, and
    // the gearbox oil temperature only on their own. Each family's PID is the other's fallback.
    private const val ATF_T5_7E1 = "pid: atf | Gearbox oil | 221674 | 2 | 7E1 | (A*256+B)*5/72-18 | °C | 40..120 | -40..200 | "
    private const val ATF_T5_7E0 = "pid: atf | Gearbox oil | 221674 | 2 | 7E0 | (A*256+B)*5/72-18 | °C | 40..120 | -40..200 | "
    private const val ATF_T6_7E1 = "pid: atf | Gearbox oil | 221E1C | 2 | 7E1 | (A*256+B)/16 | °C | 40..120 | -40..200 | "
    private const val ATF_T6_7E0 = "pid: atf | Gearbox oil | 221E1C | 2 | 7E0 | (A*256+B)/16 | °C | 40..120 | -40..200 | "
    private const val BATTERY_STANDARD = "pid: battery | Battery | 0142 | 2 | - | (A*256+B)/1000 | V | 10..16 | 6..20 | 13.8,14.4,12.5,15,lh"
    private const val BATTERY_FORD = "pid: battery | Battery | 221172 | 1 | 7E0 | A/16 | V | 10..16 | 6..20 | 13.8,14.4,12.5,15,lh"

    private val RANGER_PJPK_TEXT = listOf(
        "id: ford_ranger_pjpk",
        "name: Ford Ranger PJ/PK (2009-2011)",
        "make: Ford", "model: Ranger", "years: 2009-2011",
        "verified: no",
        "base: $GENERIC_ID",
        "notes: Gearbox oil on the T5 identifier; Ford battery voltage as a fallback.",
        BATTERY_STANDARD, BATTERY_FORD,
        ATF_T5_7E1 + "70,90,65,100,h,70", ATF_T5_7E0 + "70,90,65,100,h,70",
        ATF_T6_7E1 + "70,90,65,100,h,70", ATF_T6_7E0 + "70,90,65,100,h,70",
    ).joinToString("\n")

    private val RANGER_PX_TEXT = listOf(
        "id: ford_ranger_px",
        "name: Ford Ranger PX (2011-2018)",
        "make: Ford", "model: Ranger", "years: 2011-2018",
        "verified: no",
        "base: $GENERIC_ID",
        "notes: Gearbox oil on the T6 identifier first.",
        BATTERY_STANDARD, BATTERY_FORD,
        ATF_T6_7E1 + "70,90,65,100,h,70", ATF_T6_7E0 + "70,90,65,100,h,70",
        ATF_T5_7E1 + "70,90,65,100,h,70", ATF_T5_7E0 + "70,90,65,100,h,70",
    ).joinToString("\n")

    private val RANGER_NEXT_GEN_TEXT = listOf(
        "id: ford_ranger_next_gen",
        "name: Ford Ranger Next-Gen (2022+)",
        "make: Ford", "model: Ranger", "years: 2022-",
        "verified: no",
        "base: $GENERIC_ID",
        "notes: Runs hotter than the PJ/PK, so the normal ranges are wider; gearbox oil is a guess until checked on a truck.",
        "pid: coolant | Coolant | 0105 | 1 | - | A-40 | °C | 40..130 | -40..215 | 80,106,70,114,h,70",
        "pid: rpm | RPM | 010C | 2 | - | (A*256+B)/4 | rpm | 0..6000 | 0..9000 | 600,5000,300,6000,lh",
        "derived: boost | Boost | psi | map,baro | (A-B)*0.145038 | -15..35 | -5,24,-10,30,h",
        BATTERY_STANDARD, BATTERY_FORD,
        ATF_T6_7E1 + "70,105,65,118,h,70", ATF_T6_7E0 + "70,105,65,118,h,70",
        ATF_T5_7E1 + "70,105,65,118,h,70", ATF_T5_7E0 + "70,105,65,118,h,70",
    ).joinToString("\n")

    private val parsed: Map<String, VehicleProfile> by lazy {
        listOf(GENERIC_TEXT, RANGER_PJPK_TEXT, RANGER_PX_TEXT, RANGER_NEXT_GEN_TEXT)
            .mapNotNull { ProfileCodec.decode(it).profile }
            .associateBy { it.id }
    }

    /** Each built-in as written, with [VehicleProfile.base] still naming its base. */
    val raw: List<VehicleProfile> get() = parsed.values.toList()

    fun find(id: String): VehicleProfile? = parsed[id]

    /** A built-in with its own base folded in, the form another profile can be based on. */
    fun findResolved(id: String): VehicleProfile? = all.firstOrNull { it.id == id }

    /** Each built-in with its base's definitions merged in: what the poller and the gauges use. */
    val all: List<VehicleProfile> by lazy { raw.map { ProfileResolver.resolve(it, ::find) } }
}
