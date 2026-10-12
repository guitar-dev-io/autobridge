package dev.autobridge.mirror

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import dev.autobridge.R
import dev.autobridge.car.CarSessionHistory
import dev.autobridge.car.ConnectionTimeline
import dev.autobridge.core.model.Feature
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.input.AutoBridgeAccessibilityService
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.install.InstallerSource
import dev.autobridge.mirror.MirrorReadiness.Step
import dev.autobridge.safety.ParkingStateStore

/**
 * Reads the live phone state [MirrorReadiness] decides on, so Car setup and the My Car page show
 * the same next step. Read only: nothing here grants, starts or changes anything.
 */
object MirrorReadinessProbe {
    const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
    /** Hidden AppOps name for Android 13+'s "restricted settings" on sideloaded apps. */
    private const val OP_RESTRICTED_SETTINGS = "android:access_restricted_settings"
    /** Built only into the sideload flavors (src/projection); same name PhoneMaintenance opens. */
    private const val PROJECTION_SETUP = "dev.autobridge.projection.ProjectionSetupActivity"

    /** [captureDenied]: the capture dialog was cancelled in this process (only Car setup knows). */
    fun read(context: Context, captureDenied: Boolean = false): MirrorReadiness.Status {
        val shizukuRunning = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuGranted = runCatching { ShizukuInputBackend.isPermissionGranted }.getOrDefault(false)
        val accessibilityEnabled = accessibilityEnabledInSettings(context)
        return MirrorReadiness.Status(
            notificationsRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            shizukuRunning = shizukuRunning,
            shizukuGranted = shizukuGranted,
            realTouchAvailable = runCatching { ShizukuInputBackend.isRealTouchAvailable }.getOrDefault(false),
            // Running, not merely allowed by the mode: a switched-on service Android has stopped
            // is exactly the case Car setup has to tell apart.
            accessibilityRunning = AutoBridgeAccessibilityService.instance != null,
            projecting = ProjectionService.sessionState == ProjectionService.SessionState.READY ||
                ProjectionService.sessionState == ProjectionService.SessionState.STARTING,
            batteryOptimizationExempt = runCatching {
                context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            }.getOrDefault(true),
            carConnected = RuntimeContextStore.context.value.connected,
            speedPermissionGranted = context.checkSelfPermission(CAR_SPEED_PERMISSION) == PackageManager.PERMISSION_GRANTED,
            // Read only: the reading is shown, never set or overridden.
            speedReported = ParkingStateStore.state != ParkingStateStore.State.UNKNOWN,
            androidAutoInstalled = isInstalled(context, ANDROID_AUTO_PACKAGE),
            installerTrusted = InstallerSource.isTrusted(installer(context)),
            carSessionSeen = CarSessionHistory.hasStarted(context),
            installerRepairAvailable = projectionSetupIntent(context) != null,
            shizukuInstalled = isInstalled(context, SHIZUKU_PACKAGE),
            accessibilityEnabledInSettings = accessibilityEnabled,
            accessibilityRestricted = !accessibilityEnabled && accessibilityRestricted(context),
            lastCaptureStop = lastCaptureStop(captureDenied),
            privilegedRepair = shizukuRunning && shizukuGranted && FeaturePolicy.app.isAvailable(Feature.SHIZUKU),
            oemAutostartManager = MirrorReadiness.hasOemAutostart(Build.MANUFACTURER)
        )
    }

    fun stepTitle(context: Context, step: Step): String = context.getString(
        when (step) {
            Step.ANDROID_AUTO -> R.string.setup_step_android_auto
            Step.NOTIFICATIONS -> R.string.setup_step_notifications
            Step.TOUCH -> R.string.setup_step_touch
            Step.CAPTURE -> R.string.setup_step_capture
            Step.BATTERY -> R.string.setup_step_battery
            Step.SPEED -> R.string.setup_step_speed
        }
    )

    /** The sideload-flavor screen that reinstalls with a trusted installer; null where it is not built in. */
    fun projectionSetupIntent(context: Context): Intent? =
        Intent().setClassName(context, PROJECTION_SETUP)
            .takeIf { context.packageManager.resolveActivity(it, 0) != null }

    private fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private fun installer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    private fun accessibilityEnabledInSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val component = ComponentName(context, AutoBridgeAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }

    /**
     * Android 13+ greys the accessibility switch out for an app installed outside a store until
     * "Allow restricted settings" is tapped in App info. Read through the AppOps name the platform
     * uses for it; any failure reads as not restricted, so the screen falls back to the plain
     * "turn it on" advice rather than claiming a lock that may not be there.
     */
    @Suppress("DEPRECATION") // The by-name check is the only form that takes this hidden op's string.
    private fun accessibilityRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val mode = context.getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(OP_RESTRICTED_SETTINGS, Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_IGNORED
        }.getOrDefault(false)
    }

    /** A cancelled dialog this process first; otherwise the reason the timeline recorded for the last stop. */
    private fun lastCaptureStop(captureDenied: Boolean): MirrorReadiness.CaptureStop {
        if (captureDenied) return MirrorReadiness.CaptureStop.DENIED
        val last = ConnectionTimeline.recent().lastOrNull {
            it.kind == ConnectionTimeline.Kind.PROJECTION_STARTED || it.kind == ConnectionTimeline.Kind.PROJECTION_STOPPED
        } ?: return MirrorReadiness.CaptureStop.NONE
        if (last.kind == ConnectionTimeline.Kind.PROJECTION_STARTED) return MirrorReadiness.CaptureStop.NONE
        return MirrorReadiness.captureStopFromReason(last.detail)
    }
}
