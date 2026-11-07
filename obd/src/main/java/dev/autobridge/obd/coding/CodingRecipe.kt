package dev.autobridge.obd.coding

/** Change one byte of a configuration block: it must currently be [old], and becomes [new]. */
data class ByteEdit(val offset: Int, val old: Int, val new: Int)

/**
 * The change to one configuration block (one DID of one module). Either byte edits, each checked
 * against the value now in the module, or a whole-block replacement whose old value must match
 * exactly. Both refuse to apply to a module whose block is not what the recipe expects, which is
 * what stops a recipe written for another model or year from landing on this car.
 */
class CodingChange(val module: String, val did: Int, val edits: List<ByteEdit>, val fullOld: ByteArray?, val fullNew: ByteArray?) {
    /** The new block for [current], or an error text if the module's block is not what the recipe expects. */
    fun applyTo(current: ByteArray): Result<ByteArray> {
        if (fullOld != null && fullNew != null) {
            if (!fullOld.contentEquals(current)) return Result.failure(IllegalStateException("block is ${hex(current)}, recipe expects ${hex(fullOld)}"))
            return Result.success(fullNew.copyOf())
        }
        val out = current.copyOf()
        for (e in edits) {
            if (e.offset !in out.indices) return Result.failure(IllegalStateException("offset ${e.offset} is outside the ${current.size}-byte block"))
            val have = out[e.offset].toInt() and 0xFF
            if (have != e.old) return Result.failure(IllegalStateException("byte ${e.offset} is %02X, recipe expects %02X".format(have, e.old)))
            out[e.offset] = e.new.toByte()
        }
        return Result.success(out)
    }

    companion object {
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    }
}

class CodingRecipe(val name: String, val changes: List<CodingChange>) {
    sealed class Parsed {
        class Ok(val recipe: CodingRecipe) : Parsed()
        class Error(val line: Int, val message: String) : Parsed()
    }

    companion object {
        /**
         * One change per line, `#` starts a comment, an optional `name: ...` line titles it:
         *
         *     726 DE01 2 4F 5F          module, block, byte offset (decimal), old byte, new byte
         *     726 DE01 0804B0 0804F0    module, block, whole old block, whole new block
         *
         * Lines for the same module and block are combined into one write.
         */
        fun parse(text: String): Parsed {
            var name = ""
            val order = ArrayList<Pair<String, Int>>()
            val edits = HashMap<Pair<String, Int>, MutableList<ByteEdit>>()
            val full = HashMap<Pair<String, Int>, Pair<ByteArray, ByteArray>>()
            text.lines().forEachIndexed { index, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val n = index + 1
                if (line.startsWith("name:", ignoreCase = true)) { name = line.substringAfter(':').trim(); return@forEachIndexed }
                val t = line.split(Regex("\\s+"))
                if (t.size != 4 && t.size != 5) return Parsed.Error(n, "expected 4 or 5 fields")
                val module = t[0].uppercase()
                if (!Regex("[0-9A-F]{3}").matches(module)) return Parsed.Error(n, "module must be 3 hex digits, like 726")
                val did = t[1].takeIf { Regex("[0-9A-Fa-f]{4}").matches(it) }?.toInt(16) ?: return Parsed.Error(n, "block must be 4 hex digits, like DE01")
                val key = module to did
                if (key !in order) order.add(key)
                if (t.size == 5) {
                    val offset = t[2].toIntOrNull()?.takeIf { it >= 0 } ?: return Parsed.Error(n, "offset must be a decimal number")
                    val old = byteOf(t[3]) ?: return Parsed.Error(n, "old byte must be 2 hex digits")
                    val new = byteOf(t[4]) ?: return Parsed.Error(n, "new byte must be 2 hex digits")
                    if (full.containsKey(key)) return Parsed.Error(n, "cannot mix whole-block and byte edits for $module $did")
                    edits.getOrPut(key) { ArrayList() }.add(ByteEdit(offset, old, new))
                } else {
                    val old = bytesOf(t[2]) ?: return Parsed.Error(n, "old block must be hex")
                    val new = bytesOf(t[3]) ?: return Parsed.Error(n, "new block must be hex")
                    if (old.size != new.size) return Parsed.Error(n, "old and new block must be the same length")
                    if (edits.containsKey(key) || full.containsKey(key)) return Parsed.Error(n, "block $module $did appears twice")
                    full[key] = old to new
                }
            }
            if (order.isEmpty()) return Parsed.Error(0, "no changes")
            val changes = order.map { key ->
                val f = full[key]
                CodingChange(key.first, key.second, edits[key].orEmpty(), f?.first, f?.second)
            }
            return Parsed.Ok(CodingRecipe(name, changes))
        }

        private fun byteOf(s: String) = s.takeIf { Regex("[0-9A-Fa-f]{2}").matches(it) }?.toInt(16)
        private fun bytesOf(s: String): ByteArray? =
            s.takeIf { it.isNotEmpty() && it.length % 2 == 0 && it.all { c -> c.isDigit() || c.uppercaseChar() in 'A'..'F' } }
                ?.let { h -> ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
    }
}

/**
 * What a coding write may touch at all. Independent of any recipe, so a hand-typed or copied one
 * cannot go past it: configuration blocks only (0xDE00-0xDEFF), and never the restraints, brake
 * or steering modules.
 */
object CodingGuard {
    /**
     * Airbags/restraints, ABS/ESP, power steering, and the driver-assist camera and adaptive
     * headlamp modules (the catalog rates those "critical": multi-module calibration).
     */
    val blockedModules = setOf("737", "760", "730", "706", "734", "7E0", "7E1")

    /**
     * Engine and transmission. Blocked above until a write to them has been checked on a car; kept
     * so the confirmation still warns if they are ever let through.
     */
    val highImpactModules = setOf("7E0", "7E1")

    /**
     * Modules not even asked for an extended session or a security seed by the access check: the
     * safety systems. Reading their identification is still allowed.
     */
    val noProbeModules = setOf("737", "760", "730", "706", "734")

    val allowedDids = 0xDE00..0xDEFF

    fun check(module: String, did: Int): String? = when {
        module.uppercase() in blockedModules -> "module $module (restraints, brakes, steering, driver-assist camera, adaptive headlamps, engine or transmission) is never written"
        did !in allowedDids -> "block %04X is outside the configuration range DE00-DEFF".format(did)
        else -> null
    }

    /**
     * Checks a whole new value against the old one. A 6-byte block is taken to end in a checksum
     * byte (the As-Built line layout); a checksum is never guessed, so that byte must stay as it
     * is, whether the change came from the digit editor or a typed recipe. The length never changes.
     */
    fun checkChange(before: ByteArray, after: ByteArray): String? = when {
        before.size != after.size -> "new value is ${after.size} bytes, the block is ${before.size}"
        before.size == 6 && before[5] != after[5] -> "the last byte of a 6-byte block is a checksum and is never changed"
        else -> null
    }
}
