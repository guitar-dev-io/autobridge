package dev.autobridge.apps

/**
 * Describes content that may be intentionally blank in a public MediaProjection mirror.
 *
 * This is a diagnostic classification, not a DRM detector and not a bypass. Android and the
 * content provider remain responsible for deciding whether protected surfaces are capturable.
 */
enum class MirrorContentStatus {
    /** No known protected-content warning for the selected package. */
    NORMAL,

    /** The package commonly uses protected video that may be blank in AUTO_MIRROR output. */
    PROTECTED_CONTENT_MAY_BE_BLANK
}

object MirrorContentPolicy {
    private const val NETFLIX_PACKAGE = "com.netflix.mediaclient"

    fun status(packageName: String?): MirrorContentStatus = when (packageName?.lowercase()) {
        NETFLIX_PACKAGE -> MirrorContentStatus.PROTECTED_CONTENT_MAY_BE_BLANK
        else -> MirrorContentStatus.NORMAL
    }

    fun warningFor(packageName: String?): String? = when (status(packageName)) {
        MirrorContentStatus.NORMAL -> null
        MirrorContentStatus.PROTECTED_CONTENT_MAY_BE_BLANK ->
            "Netflix protected video may appear black through MediaProjection/AUTO_MIRROR; this is an Android DRM/content-protection limitation"
    }
}
