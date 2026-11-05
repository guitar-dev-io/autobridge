package dev.autobridge.obd.dtc

/** A diagnostic trouble code such as P0133, decoded from its two-byte wire form. */
data class DtcCode(val code: String, val raw: Int) {
    val system: Char get() = code[0]

    /** Second character 0 (and P2xxx) are standard SAE codes; 1 and 3 are manufacturer-specific. */
    val isGeneric: Boolean get() = code[1] == '0' || (system == 'P' && code[1] == '2')

    companion object {
        fun decode(a: Int, b: Int): DtcCode {
            val letter = "PCBU"[(a shr 6) and 0x03]
            val digit = (a shr 4) and 0x03
            val code = "%c%d%X%02X".format(letter, digit, a and 0x0F, b)
            return DtcCode(code, (a shl 8) or b)
        }
    }
}
