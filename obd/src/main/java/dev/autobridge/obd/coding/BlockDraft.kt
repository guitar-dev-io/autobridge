package dev.autobridge.obd.coding

/**
 * An edit in progress of one configuration block, one hex digit (nibble) at a time, starting from
 * the value just read from the module. It only ever produces a whole-block change whose old value
 * is exactly what was read, so [UdsWriter.plan] refuses it if the module holds anything else.
 *
 * A 6-byte block is taken to end in a checksum byte (the As-Built line layout); its two digits
 * are locked, because a checksum is never guessed.
 */
class BlockDraft(
    val module: String,
    val did: Int,
    original: ByteArray,
    /** Digits a catalog entry points at; only for display. */
    val focus: Set<Int> = emptySet(),
    /** The catalog entry this draft came from, if any. */
    val sourceId: String? = null,
) {
    val original: ByteArray = original.copyOf()
    private val working: ByteArray = original.copyOf()

    val value: ByteArray get() = working.copyOf()
    val digits: Int get() = working.size * 2

    /** Digits that cannot be edited: the checksum byte of a 6-byte line. */
    val locked: Set<Int> = if (original.size == 6) setOf(10, 11) else emptySet()

    fun nibble(index: Int, of: ByteArray = working): Int {
        val b = of[index / 2].toInt() and 0xFF
        return if (index % 2 == 0) b shr 4 else b and 0x0F
    }

    /** False when [index] is locked or out of range, or [nibble] is not 0..15. */
    fun set(index: Int, nibble: Int): Boolean {
        if (index !in 0 until digits || index in locked || nibble !in 0..15) return false
        val i = index / 2
        val b = working[i].toInt() and 0xFF
        working[i] = (if (index % 2 == 0) (nibble shl 4) or (b and 0x0F) else (b and 0xF0) or nibble).toByte()
        return true
    }

    fun reset() = original.copyInto(working)

    val changed: Set<Int> get() = (0 until digits).filter { nibble(it) != nibble(it, original) }.toSet()

    /** Null while nothing differs from the module. */
    fun toChange(): CodingChange? =
        if (changed.isEmpty()) null else CodingChange(module.uppercase(), did, emptyList(), original.copyOf(), working.copyOf())

    /** The same change as a recipe line, for copying or the text editor. */
    fun recipeLine(): String = "%s %04X %s %s".format(module.uppercase(), did, CodingChange.hex(original), CodingChange.hex(working))

    companion object {
        fun binary(nibble: Int): String = Integer.toBinaryString(nibble).padStart(4, '0')
    }
}
