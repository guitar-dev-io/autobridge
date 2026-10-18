package dev.autobridge.desktop.install

/**
 * apt's machine-readable progress (`-o APT::Status-Fd=1`), one line per update:
 *
 * ```
 * dlstatus:<package>:<percent>:<message>   downloading .debs
 * pmstatus:<package>:<percent>:<message>   dpkg unpacking / configuring
 * pmerror:<package>:<percent>:<message>    a package failed
 * ```
 *
 * Any other line is ordinary apt/dpkg output and is only logged.
 */
data class AptStatus(val phase: Phase, val packageName: String, val percent: Float, val message: String) {
    enum class Phase { DOWNLOAD, INSTALL, ERROR }

    companion object {
        fun parse(line: String): AptStatus? {
            val phase = when {
                line.startsWith("dlstatus:") -> Phase.DOWNLOAD
                line.startsWith("pmstatus:") -> Phase.INSTALL
                line.startsWith("pmerror:") -> Phase.ERROR
                else -> return null
            }
            val parts = line.split(':', limit = 4)
            if (parts.size < 4) return null
            val percent = parts[2].toFloatOrNull() ?: return null
            return AptStatus(phase, parts[1], percent.coerceIn(0f, 100f), parts[3])
        }

        /** `apt-cache policy` → (installed, candidate); "(none)" becomes null. */
        fun parsePolicy(output: String): Pair<String?, String?> {
            fun field(name: String): String? = output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("$name:") }
                ?.substringAfter(':')?.trim()
                ?.takeUnless { it.isEmpty() || it == "(none)" }
            return field("Installed") to field("Candidate")
        }
    }
}
