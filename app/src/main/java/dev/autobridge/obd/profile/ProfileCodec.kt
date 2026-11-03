package dev.autobridge.obd.profile

/** The result of reading profile text: a profile when it is sound, otherwise every problem found. */
class ProfileParse(val profile: VehicleProfile?, val issues: List<ProfileIssue>)

/**
 * Profiles as text, one line each, so they can be copied, pasted and shared:
 *
 * ```
 * # a comment
 * id: ford_ranger_next_gen
 * name: Ford Ranger Next-Gen (2022+)
 * make: Ford
 * model: Ranger
 * years: 2022-
 * verified: no
 * base: generic_obd2
 * notes: Gearbox oil is a guess until someone checks it on a truck.
 * pid: atf | Gearbox oil | 221E1C | 2 | 7E1 | (A*256+B)/16 | °C | 40..140 | -40..200 | 70,105,65,118,h,70
 * ```
 *
 * A `pid` line is `gauge | label | request | bytes | header | formula | unit | min..max | plausible |
 * zones`. `header`, `plausible` and `zones` are `-` when absent. Zones are `safeMin,safeMax,
 * cautionMin,cautionMax,flags[,coldBelow]` where flags is `l` (low is bad), `h` (high is bad), both,
 * or `-`. Reading never throws: a bad line becomes an issue with its line number.
 */
object ProfileCodec {

    fun decode(text: String): ProfileParse {
        if (text.length > ProfileValidator.MAX_TEXT) {
            return ProfileParse(null, listOf(ProfileIssue("text", "longer than ${ProfileValidator.MAX_TEXT} characters")))
        }
        val issues = ArrayList<ProfileIssue>()
        val keys = HashMap<String, String>()
        val pids = ArrayList<PidDef>()

        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val at = "line ${index + 1}"
            val colon = line.indexOf(':')
            if (colon <= 0) {
                issues += ProfileIssue(at, "expected 'key: value'")
                return@forEachIndexed
            }
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "pid" -> parsePid(value, at, issues)?.let { pids += it }
                "id", "name", "make", "model", "years", "verified", "base", "notes" -> {
                    if (key in keys) issues += ProfileIssue(at, "'$key' is given twice") else keys[key] = value
                }
                else -> issues += ProfileIssue(at, "unknown key '$key'")
            }
        }

        val verified = when (keys["verified"]?.lowercase()) {
            null, "no", "false" -> false
            "yes", "true" -> true
            else -> { issues += ProfileIssue("verified", "use yes or no"); false }
        }
        val profile = VehicleProfile(
            id = keys["id"].orEmpty(),
            name = keys["name"].orEmpty(),
            make = keys["make"].orEmpty(),
            model = keys["model"].orEmpty(),
            years = keys["years"].orEmpty(),
            verified = verified,
            notes = keys["notes"].orEmpty(),
            base = keys["base"]?.takeIf { it.isNotEmpty() },
            pids = pids,
        )
        // A profile that only names a base and changes nothing is allowed; one with no base needs pids.
        val checked = if (issues.isEmpty()) {
            ProfileValidator.check(if (profile.base != null && pids.isEmpty()) profile.copy(pids = listOf(PLACEHOLDER)) else profile)
        } else {
            emptyList()
        }
        val all = issues + checked
        return ProfileParse(if (all.isEmpty()) profile else null, all)
    }

    fun encode(profile: VehicleProfile): String = buildString {
        appendLine("id: ${profile.id}")
        appendLine("name: ${profile.name}")
        if (profile.make.isNotEmpty()) appendLine("make: ${profile.make}")
        if (profile.model.isNotEmpty()) appendLine("model: ${profile.model}")
        if (profile.years.isNotEmpty()) appendLine("years: ${profile.years}")
        appendLine("verified: ${if (profile.verified) "yes" else "no"}")
        profile.base?.let { appendLine("base: $it") }
        if (profile.notes.isNotEmpty()) appendLine("notes: ${profile.notes}")
        for (pid in profile.pids) {
            appendLine(
                "pid: ${pid.gauge} | ${pid.label} | ${pid.request} | ${pid.bytes} | ${pid.header ?: "-"} | ${pid.formula} | " +
                    "${pid.unit} | ${num(pid.min)}..${num(pid.max)} | ${plausible(pid)} | ${zones(pid.zone)}"
            )
        }
    }

    private fun plausible(pid: PidDef): String {
        val lo = pid.plausibleMin
        val hi = pid.plausibleMax
        return if (lo == null || hi == null) "-" else "${num(lo)}..${num(hi)}"
    }

    private fun zones(zone: ZoneRange?): String {
        zone ?: return "-"
        val flags = (if (zone.lowIsBad) "l" else "") + (if (zone.highIsBad) "h" else "")
        val base = "${num(zone.safeMin)},${num(zone.safeMax)},${num(zone.cautionMin)},${num(zone.cautionMax)},${flags.ifEmpty { "-" }}"
        return zone.coldBelow?.let { "$base,${num(it)}" } ?: base
    }

    private fun num(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()

    private fun parsePid(value: String, at: String, issues: MutableList<ProfileIssue>): PidDef? {
        val f = value.split('|').map { it.trim() }
        if (f.size != 10) {
            issues += ProfileIssue(at, "a pid line has 10 fields separated by |, found ${f.size}")
            return null
        }
        val dial = range(f[7]) ?: run { issues += ProfileIssue(at, "min..max is not a range: '${f[7]}'"); return null }
        val plausible = if (f[8] == "-") null else range(f[8]) ?: run {
            issues += ProfileIssue(at, "the plausible range is not min..max: '${f[8]}'"); return null
        }
        val zone = if (f[9] == "-") null else zone(f[9]) ?: run {
            issues += ProfileIssue(at, "zones are 'safeMin,safeMax,cautionMin,cautionMax,flags[,coldBelow]': '${f[9]}'"); return null
        }
        val bytes = f[3].toIntOrNull() ?: run { issues += ProfileIssue(at, "bytes is not a number: '${f[3]}'"); return null }
        return PidDef(
            gauge = f[0], label = f[1], request = f[2].uppercase(), bytes = bytes,
            header = f[4].takeIf { it != "-" }?.uppercase(), formula = f[5], unit = f[6],
            min = dial.first, max = dial.second, plausibleMin = plausible?.first, plausibleMax = plausible?.second,
            zone = zone,
        )
    }

    private fun range(text: String): Pair<Float, Float>? {
        val at = text.indexOf("..")
        if (at <= 0) return null
        val lo = text.substring(0, at).trim().toFloatOrNull() ?: return null
        val hi = text.substring(at + 2).trim().toFloatOrNull() ?: return null
        return lo to hi
    }

    private fun zone(text: String): ZoneRange? {
        val p = text.split(',').map { it.trim() }
        if (p.size !in 5..6) return null
        val n = p.take(4).map { it.toFloatOrNull() ?: return null }
        val flags = p[4]
        if (flags != "-" && flags.any { it != 'l' && it != 'h' }) return null
        val cold = if (p.size == 6) p[5].toFloatOrNull() ?: return null else null
        return ZoneRange(n[0], n[1], n[2], n[3], 'l' in flags, 'h' in flags, cold)
    }

    private val PLACEHOLDER = PidDef("placeholder", "x", "0100", 1, null, "A", "", 0f, 1f)
}
