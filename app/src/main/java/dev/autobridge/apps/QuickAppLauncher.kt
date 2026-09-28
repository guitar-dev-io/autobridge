package dev.autobridge.apps

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import dev.autobridge.core.datastore.SessionRestoreStore
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.display.StructuredLog
import dev.autobridge.mirror.MirrorCoordinator
import dev.autobridge.settings.MirrorSettings

/**
 * Launches a Quick App through policy, applies its profile, and records a restorable session.
 * Android does not permit AutoBridge to force another app's immersive fullscreen window, so that
 * preference is persisted and surfaced to the user but is not falsely reported as applied.
 */
object QuickAppLauncher {
    private const val TAG = "AutoBridgeQuickApp"

    @Volatile
    private var autoLaunchAttempted = false

    /** Pure safety predicate retained for callers that already have an evaluated state. */
    fun canLaunch(isParked: Boolean): Boolean = isParked

    fun canLaunch(context: Context): Boolean =
        FeaturePolicy.app.isAvailable(Feature.QUICK_APPS)

    fun hasLandscapePermission(context: Context): Boolean = Settings.System.canWrite(context)

    fun requestLandscapePermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            "package:${context.packageName}".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Resets the one-shot guard when a new projection session starts. */
    fun resetAutoLaunchSession() {
        autoLaunchAttempted = false
    }

    /**
     * Reopens the last explicitly launched app once per projection session. This never replays
     * MediaProjection consent; the caller must already have a ready projection and a car surface.
     */
    fun autoLaunchLastSession(context: Context): Boolean {
        if (!MirrorSettings.autoLaunchLastApp || autoLaunchAttempted) return false
        autoLaunchAttempted = true
        val snapshot = SessionRestoreStore.restore(context) ?: return false
        return launch(context, snapshot.packageName, snapshot.profileId)
    }

    /**
     * Launches [packageName] only when Quick Apps and the resolved SmartMode are allowed. The
     * existing projection consent remains authoritative: autoMirror never starts capture silently.
     */
    fun launch(context: Context, packageName: String, profileId: String = "default"): Boolean {
        if (!canLaunch(context)) return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        val profile = PerAppProfileStore.profile(context, packageName, profileId)
        val decision = SmartModeResolver.resolve(packageName, profile)
        val requiredFeature = when (decision.mode) {
            SmartMode.MEDIA -> Feature.MEDIA
            SmartMode.MIRROR -> Feature.MIRROR
            SmartMode.BROWSER -> Feature.BROWSER
            SmartMode.NATIVE_CAR -> Feature.QUICK_APPS
        }
        if (!FeaturePolicy.app.isAvailable(requiredFeature)) {
            Log.i(TAG, "Launch blocked for $packageName: ${FeaturePolicy.app.denialMessage(requiredFeature)}")
            return false
        }

        val rotationApplied = if (profile.rotationMode == dev.autobridge.core.model.RotationMode.LANDSCAPE) {
            val applied = AppRotationController.apply(context, profile.rotationMode)
            if (!applied) {
                // Do not leave a partial or stale settings snapshot behind after a failed write.
                AppRotationController.restore(context)
                Log.w(TAG, "Force-landscape set for $packageName but rotation permission is unavailable")
            }
            applied
        } else {
            AppRotationController.restore(context)
            false
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            MirrorSettings.preferredFps = profile.preferredFps
            // Keep the renderer/input geometry on the same profile boundary. AUTO_MIRROR still
            // reports unsupported requested scales while using FIT for the actual output.
            MirrorCoordinator.setScaleMode(profile.scaleMode)
            MirrorCoordinator.setRotationMode(profile.rotationMode)
            RuntimeContextStore.setCurrentFeature(requiredFeature, packageName)
            RuntimeContextStore.setDisplayPreferences(
                scaleMode = profile.scaleMode,
                rotationMode = profile.rotationMode,
                audioMode = profile.audioMode,
                fullscreen = profile.autoFullscreen
            )
            SessionRestoreStore.save(context, packageName, profile, decision.mode)
            MirrorContentPolicy.warningFor(packageName)?.let { warning ->
                StructuredLog.w(TAG, warning)
                MirrorDiagnostics.record("protected_content_warning")
            }
            if (profile.autoMirror && !MirrorCoordinator.isProjectionReady) {
                Log.i(TAG, "Profile requests autoMirror; waiting for explicit MediaProjection consent")
            }
            MirrorDiagnostics.record("quick_app_launched")
            Log.i(TAG, "Launched $packageName as ${decision.mode}: ${decision.reason}")
            true
        }.onFailure { error ->
            if (rotationApplied) AppRotationController.restore(context)
            Log.w(TAG, "Could not launch $packageName", error)
        }.getOrDefault(false)
    }

    /** Compatibility cleanup; AppRotationController now owns exact snapshot restoration. */
    fun restoreAutoRotate(context: Context) = AppRotationController.restore(context)
}
