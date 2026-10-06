package dev.autobridge.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * The backup file: the fuel/charging log, the maintenance list and the trips as one JSON document,
 * so moving to a new phone is one file. Pure, so the round trip is tested on the JVM.
 */
data class Backup(val ev: Boolean, val fuel: String, val maintenance: String, val trips: String)

object BackupFormat {
    const val VERSION = 1

    fun write(backup: Backup): String = JSONObject()
        .put("app", "autobridge")
        .put("version", VERSION)
        .put("ev", backup.ev)
        .put("fuel", JSONArray(backup.fuel))
        .put("maintenance", JSONArray(backup.maintenance))
        .put("trips", JSONArray(backup.trips))
        .toString(2)

    /** The backup in [text], or null if it is not one of ours (or from a newer version). */
    fun read(text: String): Backup? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optString("app") != "autobridge" || root.optInt("version", 0) !in 1..VERSION) return null
        val fuel = root.optJSONArray("fuel") ?: return null
        val maintenance = root.optJSONArray("maintenance") ?: return null
        val trips = root.optJSONArray("trips") ?: return null
        return Backup(root.optBoolean("ev"), fuel.toString(), maintenance.toString(), trips.toString())
    }
}
