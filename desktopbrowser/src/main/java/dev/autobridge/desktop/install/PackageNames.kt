package dev.autobridge.desktop.install

/**
 * Package names typed into Settings, checked before they reach `apt-get`. The command runs without
 * a shell, but a name starting with "-" would still be read as an option, so only Debian's own
 * package-name alphabet is let through (lower-case letters, digits, "+", "-", ".").
 */
object PackageNames {
    private val VALID = Regex("^[a-z0-9][a-z0-9+.\\-]{1,99}$")
    const val MAX = 20

    /** The names in [input] (separated by spaces, commas or lines), or null when any is not a valid name. */
    fun parse(input: String): List<String>? {
        val names = input.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty() || names.size > MAX) return null
        return names.takeIf { list -> list.all { VALID.matches(it) } }
    }
}
