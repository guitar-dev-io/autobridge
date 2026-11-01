package dev.autobridge.obd

import android.content.Context

/**
 * What each trouble code means, from the list shipped in assets (`dtc/ford_dtc_codes.csv`, about
 * 700 codes, English). The list comes from the Ford OBD2 Smart Gauge project, used with its
 * maintainer's permission and credited in the README; a code that is not in it simply has no
 * description, and the screen says so.
 */
object DtcDatabase {
    private const val ASSET = "dtc/ford_dtc_codes.csv"

    @Volatile
    private var cache: Map<String, String>? = null

    /** The description of [code] (e.g. "P0133"), or null when the list does not know it. */
    fun describe(context: Context, code: String): String? = load(context)[code.uppercase()]

    private fun load(context: Context): Map<String, String> {
        cache?.let { return it }
        val loaded = runCatching {
            context.applicationContext.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
        }.getOrDefault(emptyMap())
        cache = loaded
        return loaded
    }

    /**
     * `CODE,DESCRIPTION` lines; the header, blank lines and anything that is not a code are skipped,
     * and "No Fault" (P0000) is dropped because it describes the absence of a code.
     */
    fun parse(csv: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in csv.lineSequence()) {
            val comma = line.indexOf(',')
            if (comma < 5) continue
            val code = line.substring(0, comma).trim().uppercase()
            val description = line.substring(comma + 1).trim()
            if (!CODE.matches(code) || description.isEmpty() || code == "P0000") continue
            out[code] = description
        }
        return out
    }

    private val CODE = Regex("[PCBU][0-9A-F]{4}")
}
