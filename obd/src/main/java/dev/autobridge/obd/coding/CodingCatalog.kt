package dev.autobridge.obd.coding

/**
 * One row of the community coding catalog (Ranger 6G, 2024, North America). Reference data only:
 * it says where a setting is reported to live, never what to write. [pattern] marks nibble
 * positions and is not a payload.
 */
data class CatalogEntry(
    val id: String,
    val category: String,
    val featureEn: String,
    val featureTh: String,
    val moduleName: String,
    val ecu: String,
    val block: Int,
    val line: Int,
    val pattern: String,
    val markedNibbles: Set<Int>,
    val guiParameter: String,
    val settingNotes: String,
    val communityTab: String,
    val thaiValidation: String,
    val risk: String,
    val equipment: String,
    val engineeringNotes: String
) {
    /** Assumption, not verified on a car: As-Built block NN is data identifier DE NN. */
    val did: Int get() = 0xDE00 or block
    val address: String get() = "%s-%02X-%02d".format(ecu, block, line)
}

object CodingCatalog {
    /** Parses the catalog CSV; rows that do not have a usable address are skipped. */
    fun parse(csv: String): List<CatalogEntry> {
        val rows = rows(csv)
        if (rows.isEmpty()) return emptyList()
        val col = rows[0].withIndex().associate { it.value.trim() to it.index }
        fun Map<String, Int>.get(r: List<String>, name: String) = this[name]?.let { r.getOrNull(it) }?.trim().orEmpty()
        return rows.drop(1).mapNotNull { r ->
            val addr = col.get(r, "asbuilt_address").split('-')
            if (addr.size != 3) return@mapNotNull null
            val block = addr[1].toIntOrNull(16) ?: return@mapNotNull null
            val line = addr[2].toIntOrNull() ?: return@mapNotNull null
            if (addr[0].isEmpty()) return@mapNotNull null
            val pattern = col.get(r, "source_pattern")
            val flat = pattern.filter { !it.isWhitespace() }
            CatalogEntry(
                id = col.get(r, "feature_id"), category = col.get(r, "category"),
                featureEn = col.get(r, "feature_en"), featureTh = col.get(r, "feature_th"),
                moduleName = col.get(r, "module"), ecu = addr[0].uppercase(), block = block, line = line,
                pattern = pattern, markedNibbles = flat.indices.filter { flat[it] == '*' }.toSet(),
                guiParameter = col.get(r, "forscan_gui_parameter"), settingNotes = col.get(r, "reported_setting_notes"),
                communityTab = col.get(r, "community_tab"), thaiValidation = col.get(r, "thai_market_validation"),
                risk = col.get(r, "risk"), equipment = col.get(r, "requires_equipment"),
                engineeringNotes = col.get(r, "engineering_notes")
            )
        }
    }

    /**
     * Hex digits of a value read from the module, plus which of them the pattern marks. Marks are
     * only reported when the value is one As-Built line (5 data bytes, or 6 with the checksum
     * byte); with any other length the line layout is unconfirmed and nothing is marked.
     */
    fun marked(entry: CatalogEntry, value: ByteArray): Pair<String, Set<Int>> {
        val hex = value.joinToString("") { "%02X".format(it) }
        val usable = value.size in 5..6 && entry.markedNibbles.all { it < hex.length }
        return hex to if (usable) entry.markedNibbles else emptySet()
    }

    /** Minimal RFC-4180-style reader: quoted fields, doubled quotes, commas and newlines inside quotes. */
    internal fun rows(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row.add(cell.toString()); cell.setLength(0) }
        fun endRow() { endCell(); if (row.any { it.isNotEmpty() }) out.add(row); row = ArrayList() }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> endCell()
                !quoted && c == '\n' -> endRow()
                !quoted && c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return out
    }
}
