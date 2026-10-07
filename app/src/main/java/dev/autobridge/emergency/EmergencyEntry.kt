package dev.autobridge.emergency

/**
 * One line of the emergency card: a label and what to reach or quote — a phone number to dial, or
 * plain text such as an insurance policy number.
 */
data class EmergencyEntry(val id: Long, val label: String, val value: String) {
    /** The number to dial when [value] is a phone number, else null (it is text to read). */
    val dialable: String? get() = EmergencyNumber.dialable(value)
}

/** What counts as a phone number, kept pure so it is tested on the JVM. */
object EmergencyNumber {
    private val PHONE = Regex("""^\+?\(?[0-9][0-9 \-().]{1,18}$""")

    /**
     * [value] as a dialable string (digits and a leading +) when it looks like a phone number:
     * 191, 1669, 02-123-4567, (081) 234-5678, +66 81 234 5678. Text with letters, like a policy number "AB-1234567",
     * is not one.
     */
    fun dialable(value: String): String? {
        val trimmed = value.trim()
        if (!PHONE.matches(trimmed)) return null
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 3) return null
        return (if (trimmed.startsWith("+")) "+" else "") + digits
    }
}
