package dev.autobridge.media

/**
 * Pure allowlist for the exported MediaSession service. The package list is intentionally narrow:
 * AutoBridge itself, the Android system UID, and known Android Auto/system media-controller
 * identities are allowed; unknown external callers are rejected and logged by the service.
 */
object MediaControllerAuthorization {
    val defaultTrustedPackages: Set<String> = setOf(
        "com.google.android.projection.gearhead",
        "com.google.android.projection.gearhead.debug",
        "com.google.android.apps.automotive",
        "com.android.systemui",
        "com.android.bluetooth"
    )

    fun isAllowed(
        packageName: String,
        uid: Int,
        ownPackageName: String,
        ownUid: Int,
        systemUid: Int,
        trustedPackages: Set<String> = defaultTrustedPackages
    ): Boolean = packageName == ownPackageName ||
        uid == ownUid ||
        uid == systemUid ||
        packageName in trustedPackages
}
