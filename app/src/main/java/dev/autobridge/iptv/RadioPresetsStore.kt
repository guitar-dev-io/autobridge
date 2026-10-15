package dev.autobridge.iptv

import android.content.Context
import dev.autobridge.core.crypto.SecretPrefs
import dev.autobridge.radio.RadioSourceType
import dev.autobridge.radio.RadioStation
import org.json.JSONObject

/**
 * The six radio presets (slots 1–6), each an internet [RadioStation] or empty.
 *
 * A preset holds a playable stream URL, which can embed credentials just like a history URL, so
 * this file is written through [SecretPrefs] and excluded from backup/transfer exactly like
 * [IptvHistoryStore] (see res/xml/backup_rules.xml and data_extraction_rules.xml).
 *
 * Only internet (and, in principle, offline) stations can be saved: a [RadioSourceType.HARDWARE_FM]
 * station has no stream URL to replay, so [save] rejects it and leaves the slot untouched. A slot
 * that cannot be parsed, or whose URL is blank, reads back as empty rather than as a broken preset.
 */
object RadioPresetsStore {
    /** The outcome of a [save]. */
    sealed interface SaveResult {
        object Saved : SaveResult
        data class Rejected(val reason: String) : SaveResult
    }

    const val SLOT_COUNT = 6
    private const val PREFS_NAME = "autobridge_radio_presets"

    private fun slotKey(slot: Int) = "slot_$slot"

    /** The six slots in order, null for an empty slot. Always [SLOT_COUNT] long. */
    fun presets(context: Context): List<RadioStation?> =
        (1..SLOT_COUNT).map { slot -> read(context, slot) }

    /**
     * Saves [station] into [slot] (clamped to 1..6). Rejects a [RadioSourceType.HARDWARE_FM] station
     * or one with a blank URL, leaving the slot unchanged.
     */
    fun save(context: Context, slot: Int, station: RadioStation): SaveResult {
        if (station.sourceType == RadioSourceType.HARDWARE_FM || station.streamUrl.isBlank()) {
            return SaveResult.Rejected("Can't save FM to a preset")
        }
        val safeSlot = slot.coerceIn(1, SLOT_COUNT)
        val json = JSONObject()
            .put("title", station.title)
            .put("url", station.streamUrl)
            .put("logo", station.logo)
            .put("sourceType", station.sourceType.name)
        SecretPrefs.write(prefs(context), PREFS_NAME, slotKey(safeSlot), json.toString())
        return SaveResult.Saved
    }

    /** Empties [slot] (clamped to 1..6). */
    fun clear(context: Context, slot: Int) {
        val safeSlot = slot.coerceIn(1, SLOT_COUNT)
        SecretPrefs.write(prefs(context), PREFS_NAME, slotKey(safeSlot), "")
    }

    private fun read(context: Context, slot: Int): RadioStation? {
        val raw = SecretPrefs.read(prefs(context), PREFS_NAME, slotKey(slot))
        if (raw.isBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val url = json.optString("url").takeIf { it.isNotBlank() } ?: return@runCatching null
            val sourceType = runCatching { RadioSourceType.valueOf(json.optString("sourceType")) }
                .getOrDefault(RadioSourceType.INTERNET_RADIO)
            RadioStation(
                sourceType = sourceType,
                id = url,
                title = json.optString("title").ifBlank { url },
                streamUrl = url,
                logo = json.optString("logo"),
            )
        }.getOrNull()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
