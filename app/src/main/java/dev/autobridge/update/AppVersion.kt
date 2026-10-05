package dev.autobridge.update

/**
 * Ordering for the `major.minor.patch[-rc1]` version names this project uses.
 *
 * The update check has to answer one question — is the published release newer than the build
 * running right now — from two strings that come from different places: [dev.autobridge.BuildConfig]
 * and a GitHub release tag. Tags are written by hand, so `v0.5.0`, `0.5.0` and ` 0.5.0 ` all have
 * to mean the same thing, and a version nobody can parse must not be reported as an update.
 *
 * The rules follow semantic versioning where it matters here:
 *  - numeric segments compare as numbers, so 0.4.12 is above 0.4.9
 *  - a missing segment is zero, so 0.5 equals 0.5.0
 *  - a pre-release suffix ranks *below* the same release without one: 0.5.0-rc1 < 0.5.0
 *  - build metadata after `+` is ignored, as the spec says it must be
 *
 * Pure string work with no Android dependency, so the comparisons are unit-tested directly.
 */
object AppVersion {

    /** Strips the `v` prefix, surrounding whitespace and `+build` metadata. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V").trim()
        return trimmed.substringBefore('+').trim()
    }

    /** True when [raw] is a version this comparison understands at all. */
    fun isParsable(raw: String): Boolean {
        val release = normalize(raw).substringBefore('-')
        if (release.isEmpty()) return false
        return release.split('.').all { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /** Negative when [left] is older, zero when the two name the same version, positive when newer. */
    fun compare(left: String, right: String): Int {
        val leftVersion = normalize(left)
        val rightVersion = normalize(right)

        val releaseOrder = compareNumericSegments(
            leftVersion.substringBefore('-'),
            rightVersion.substringBefore('-')
        )
        if (releaseOrder != 0) return releaseOrder

        val leftPreRelease = leftVersion.substringAfter('-', "")
        val rightPreRelease = rightVersion.substringAfter('-', "")
        // Having no suffix is the released version, which outranks every candidate for it.
        if (leftPreRelease.isEmpty() && rightPreRelease.isEmpty()) return 0
        if (leftPreRelease.isEmpty()) return 1
        if (rightPreRelease.isEmpty()) return -1
        return comparePreRelease(leftPreRelease, rightPreRelease)
    }

    /**
     * True when [candidate] is a newer version than [current].
     *
     * A candidate that cannot be parsed is never newer: an unreadable tag means "this check does
     * not know", and offering an update on a guess is worse than offering none.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        if (!isParsable(candidate) || !isParsable(current)) return false
        return compare(candidate, current) > 0
    }

    private fun compareNumericSegments(left: String, right: String): Int {
        val leftSegments = left.split('.')
        val rightSegments = right.split('.')
        for (index in 0 until maxOf(leftSegments.size, rightSegments.size)) {
            val leftValue = leftSegments.getOrNull(index).toSegment()
            val rightValue = rightSegments.getOrNull(index).toSegment()
            if (leftValue != rightValue) return leftValue.compareTo(rightValue)
        }
        return 0
    }

    /** An absent or non-numeric segment counts as zero rather than throwing on odd input. */
    private fun String?.toSegment(): Long = this?.trim()?.toLongOrNull() ?: 0L

    /**
     * Semver pre-release precedence: identifier by identifier, numbers below text, and a shorter
     * run of identifiers below a longer one with the same prefix (rc1 < rc1.2).
     */
    private fun comparePreRelease(left: String, right: String): Int {
        val leftIdentifiers = left.split('.')
        val rightIdentifiers = right.split('.')
        for (index in 0 until minOf(leftIdentifiers.size, rightIdentifiers.size)) {
            val leftIdentifier = leftIdentifiers[index]
            val rightIdentifier = rightIdentifiers[index]
            val leftNumber = leftIdentifier.toLongOrNull()
            val rightNumber = rightIdentifier.toLongOrNull()
            val order = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> leftIdentifier.compareTo(rightIdentifier)
            }
            if (order != 0) return order
        }
        return leftIdentifiers.size.compareTo(rightIdentifiers.size)
    }
}
