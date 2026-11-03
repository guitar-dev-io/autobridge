package dev.autobridge.obd.profile

/** What is wrong with a profile, in words a person can act on. */
data class ProfileIssue(val where: String, val problem: String) {
    override fun toString(): String = "$where: $problem"
}

/**
 * Decides whether a profile may be used. Profiles are shared as text, so this is the gate that
 * keeps a file from anywhere harmless: every request must be a read, the formula language is the
 * arithmetic-only one in [Formula], and every field is bounded.
 *
 * The rule that matters most is the first one: only service 01 (standard live data) and 22 (read
 * data by identifier) are allowed. Anything that could write, reset, clear or reprogram (2E, 2F,
 * 31, 04, 10, 11, 27, 3B, 3D and the rest) is refused, so importing a profile can never make the
 * app change anything in a control unit.
 */
object ProfileValidator {
    const val MAX_PIDS = 80
    const val MAX_TEXT = 24_000

    private val ID = Regex("[a-z][a-z0-9_]{0,23}")
    private val PROFILE_ID = Regex("[a-z0-9_]{3,32}")
    private val STANDARD = Regex("01[0-9A-F]{2}")
    private val ENHANCED = Regex("22[0-9A-F]{4}")
    private val HEADER = Regex("[0-9A-F]{3}")

    fun check(profile: VehicleProfile): List<ProfileIssue> {
        val issues = ArrayList<ProfileIssue>()
        fun bad(where: String, problem: String) { issues += ProfileIssue(where, problem) }

        if (!PROFILE_ID.matches(profile.id)) bad("id", "use 3 to 32 lowercase letters, digits or _")
        if (profile.name.isBlank() || profile.name.length > 60) bad("profile", "the name must be 1 to 60 characters")
        if (profile.make.length > 40 || profile.model.length > 40 || profile.years.length > 20) bad("profile", "make, model or years is too long")
        if (profile.notes.length > 400) bad("notes", "longer than 400 characters")
        if (profile.pids.isEmpty()) bad("profile", "no pid lines")
        if (profile.pids.size > MAX_PIDS) bad("profile", "more than $MAX_PIDS pid lines")

        for ((index, pid) in profile.pids.withIndex()) {
            val at = "pid ${index + 1} (${pid.gauge})"
            if (!ID.matches(pid.gauge)) bad(at, "the gauge id must be a lowercase word of up to 24 characters")
            if (pid.label.isBlank() || pid.label.length > 40) bad(at, "the label must be 1 to 40 characters")
            if (!STANDARD.matches(pid.request) && !ENHANCED.matches(pid.request)) {
                bad(at, "the request ${pid.request} is not allowed: only 01xx and 22xxxx reads are accepted")
            }
            if (pid.bytes !in 1..8) bad(at, "bytes must be 1 to 8")
            if (pid.header != null && !HEADER.matches(pid.header)) bad(at, "the header must be 3 hex digits such as 7E0")
            val formula = Formula.parse(pid.formula)
            when {
                formula == null -> bad(at, "the formula '${pid.formula}' is not arithmetic over A to H")
                formula.bytesNeeded > pid.bytes -> bad(at, "the formula uses byte ${('A' + formula.bytesNeeded - 1)} but only ${pid.bytes} byte(s) are read")
            }
            if (pid.unit.length > 8) bad(at, "the unit is longer than 8 characters")
            if (!(pid.min < pid.max)) bad(at, "min must be below max")
            val lo = pid.plausibleMin
            val hi = pid.plausibleMax
            if (lo != null && hi != null && !(lo < hi)) bad(at, "the plausible range is empty")
            pid.zone?.let { z ->
                if (!(z.safeMin <= z.safeMax)) bad(at, "the safe range is reversed")
                if (z.cautionMin > z.safeMin || z.cautionMax < z.safeMax) bad(at, "the caution range must contain the safe range")
            }
        }
        return issues
    }

    /** True when [request] is one of the two read-only forms. */
    fun isReadRequest(request: String): Boolean = STANDARD.matches(request) || ENHANCED.matches(request)
}
