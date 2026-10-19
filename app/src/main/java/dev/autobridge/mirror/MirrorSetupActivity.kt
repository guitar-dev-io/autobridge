package dev.autobridge.mirror

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.core.consent.PermissionDisclosure
import dev.autobridge.i18n.AppLocale
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.logging.StructuredLog
import dev.autobridge.media.MediaAutoStart
import dev.autobridge.mirror.MirrorReadiness.Cause
import dev.autobridge.mirror.MirrorReadiness.Fix
import dev.autobridge.mirror.MirrorReadiness.State
import dev.autobridge.mirror.MirrorReadiness.Step
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * One screen that gets the phone ready for the car, in the order the steps actually happen, and
 * says for each one what is wrong and the single tap that fixes it.
 *
 * Everything the car needs used to live somewhere else: Android Auto's own listing rules, the
 * notification permission, touch in Android's accessibility list, capture behind a button on the
 * control centre, and the battery allowlist. Each is a different system screen and none of them
 * says what the others are, so the common outcome was trial and error. The "Fix" button at the top
 * runs the next repair; each row also runs its own.
 *
 * The step list itself is [MirrorReadiness] — pure, and unit tested. This activity only reads the
 * live state, draws it, and performs the one action a step offers.
 */
class MirrorSetupActivity : Activity() {

    companion object {
        private const val TAG = "CarSetup"
        private const val REQUEST_NOTIFICATIONS = 5101
        private const val REQUEST_CAPTURE = 5102
        private const val REQUEST_BLUETOOTH = 5103
        private const val SHIZUKU_BIND_WAIT_MS = 3_000L

        /** A cancelled capture dialog this process; cleared when capture starts. */
        @Volatile
        private var captureDenied = false

        fun intent(context: Context): Intent = Intent(context, MirrorSetupActivity::class.java)
    }

    private val accent = AutoBridgeDesign.ACCENT_SYSTEM

    /** Applies the Settings &gt; Language choice; see [AppLocale.rebase]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    /** Coming back from Shizuku, the accessibility list or the capture dialog changes the answers. */
    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH) {
            // The option is only switched on once the broadcast can actually be delivered;
            // otherwise the screen would show ON for something that can never fire.
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            MediaAutoStart.setEnabled(this, granted)
            if (!granted) toast(R.string.setup_toast_bluetooth_needed)
        }
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                captureDenied = false
                ProjectionService.start(this, resultCode, data)
            } else {
                captureDenied = true
                toast(R.string.setup_toast_capture_denied)
            }
        }
        render()
    }

    private fun status(): MirrorReadiness.Status = MirrorReadinessProbe.read(this, captureDenied)

    private fun render() {
        val status = status()
        val steps = MirrorReadiness.steps(status)
        val next = MirrorReadiness.nextAction(status)
        val fix = MirrorReadiness.nextFix(status)

        val body = AutoBridgeDesign.body(this)
        if (fix?.fix != null) {
            body.stack(
                AutoBridgeDesign.pill(
                    context = this,
                    label = getString(R.string.setup_fix_next, stepTitle(fix.step)),
                    primary = true,
                    accent = accent,
                    onClick = { perform(fix.fix) }
                ),
                gap = 2
            )
        }
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.setup_section_steps)), gap = 8)
        steps.forEachIndexed { index, item ->
            body.stack(stepRow(index + 1, item))
        }

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.setup_section_car_connects)), gap = 12)
        val autoStart = MediaAutoStart.isEnabled(this)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.setup_autostart_title),
                subtitle = getString(if (autoStart) R.string.setup_autostart_on else R.string.setup_autostart_off),
                accent = if (autoStart) accent else AutoBridgeDesign.TEXT_MUTED,
                badgeText = if (autoStart) "ON" else "OFF",
                onClick = { toggleAutoStart(!autoStart) }
            )
        )

        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.setup_title),
                    subtitle = when {
                        status.projecting -> getString(R.string.setup_subtitle_running)
                        next == null -> getString(R.string.setup_subtitle_ready)
                        else -> getString(R.string.setup_subtitle_next, stepTitle(next.step))
                    },
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun stepRow(number: Int, item: MirrorReadiness.Item): View {
        val badge = when (item.state) {
            State.DONE -> "✓"
            State.BLOCKING -> "$number"
            State.OPTIONAL -> "!"
            State.WAITING -> "…"
        }
        val rowAccent = when (item.state) {
            State.DONE -> AutoBridgeDesign.ACCENT_FILES
            State.BLOCKING -> accent
            State.OPTIONAL -> AutoBridgeDesign.ACCENT_RADIO
            State.WAITING -> AutoBridgeDesign.TEXT_MUTED
        }
        val fix = item.fix
        return AutoBridgeDesign.contentRow(
            context = this,
            title = stepTitle(item.step),
            subtitle = getString(causeText(item.cause)),
            accent = rowAccent,
            badgeText = badge,
            subtitleMaxLines = 4,
            actionLabel = fix?.let { getString(fixLabel(it)) },
            onTrailing = fix?.let { { perform(it) } },
            onClick = { fix?.let(::perform) }
        )
    }

    private fun perform(fix: Fix) {
        StructuredLog.i(TAG, "fix $fix")
        when (fix) {
            Fix.INSTALL_ANDROID_AUTO -> openStore(MirrorReadinessProbe.ANDROID_AUTO_PACKAGE)
            Fix.MAKE_VISIBLE_ON_CAR -> MirrorReadinessProbe.projectionSetupIntent(this)?.let(::startActivity)
            Fix.ALLOW_NOTIFICATIONS -> requestNotifications()
            Fix.GRANT_SHIZUKU -> grantShizuku()
            Fix.OPEN_ACCESSIBILITY -> openAccessibility()
            Fix.RESTART_ACCESSIBILITY -> {
                // Already switched on, so the disclosure was read when it was; this is a restart.
                toast(R.string.setup_toast_restart_accessibility)
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            Fix.ALLOW_RESTRICTED_SETTINGS -> openAppInfo()
            Fix.START_CAPTURE, Fix.STOP_CAPTURE -> toggleProjection()
            Fix.EXEMPT_BATTERY_WITH_SHIZUKU -> exemptBatteryWithShizuku()
            Fix.ALLOW_BATTERY -> requestBatteryExemption()
            Fix.OPEN_AUTOSTART -> openAutostart()
        }
    }

    // ---------------------------------------------------------------- fixes

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Once the system stops asking, the request returns instantly and the only way through is
        // the app's own settings page. Sending the user there directly beats a dialog that never
        // appears. (The same trap cost the library screen a StackOverflowError.)
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
            !notificationsAsked
        ) {
            notificationsAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            openAppInfo()
        }
    }

    private fun grantShizuku() {
        val granted = runCatching { ShizukuInputBackend.isPermissionGranted }.getOrDefault(false)
        if (granted) {
            ShizukuInputBackend.bind(this)
        } else {
            ShizukuInputBackend.requestPermission()
            toast(R.string.setup_toast_approve_shizuku)
        }
    }

    /**
     * Accessibility is a Play-declared permission: the user reads what the service will do with
     * this phone before the settings screen that turns it on (see PLAY_DECLARATIONS).
     */
    private fun openAccessibility() {
        PermissionDisclosure.show(this, R.string.a11y_disclosure_title, R.string.a11y_disclosure_body) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun openAppInfo() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        )
    }

    private fun openStore(pkg: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
    }

    /**
     * This is the one system dialog Android lets an app trigger directly for itself — no chain of
     * settings screens — because Google requires an explicit user-facing prompt naming the app
     * before it can be exempted. `onResume()` re-reads the real state either way, so a denial here
     * just leaves the step showing "Allow" again rather than needing its own result handling.
     */
    private fun requestBatteryExemption() {
        val alreadyExempt = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        if (alreadyExempt) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }.onFailure {
            // Some OEM builds (custom battery managers) don't implement this action at all.
            openAppInfo()
            toast(R.string.setup_toast_battery_fallback)
        }
    }

    /**
     * Adds this app to the device-idle allowlist from the Shizuku shell, which is what the system
     * dialog does, without the dialog. Off the main thread: binding the user service and running
     * the command can take a moment. Verified by reading the exemption back, and on any failure the
     * normal dialog opens instead, so the tap always leads somewhere.
     */
    private fun exemptBatteryWithShizuku() {
        ShizukuInputBackend.bind(this)
        val pkg = packageName
        Thread {
            val deadline = System.currentTimeMillis() + SHIZUKU_BIND_WAIT_MS
            var output: String? = null
            while (output == null && System.currentTimeMillis() < deadline) {
                output = ShizukuInputBackend.runShellCommand(listOf("dumpsys", "deviceidle", "whitelist", "+$pkg"))
                if (output == null) Thread.sleep(200)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val exempt = runCatching {
                    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)
                }.getOrDefault(false)
                StructuredLog.i(TAG, "battery allowlist via Shizuku exempt=$exempt output=${output?.trim()}")
                if (exempt) {
                    toast(R.string.setup_toast_battery_shizuku_ok)
                } else {
                    toast(R.string.setup_toast_battery_shizuku_failed)
                    requestBatteryExemption()
                }
                render()
            }
        }.start()
    }

    /** MIUI/HyperOS's own autostart list; App info when this build of the OS hides it elsewhere. */
    private fun openAutostart() {
        val miui = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        runCatching { startActivity(miui) }.onFailure {
            openAppInfo()
            toast(R.string.setup_toast_autostart_manual)
        }
    }

    private fun toggleProjection() {
        if (ProjectionService.sessionState == ProjectionService.SessionState.IDLE) {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } else {
            ProjectionService.stop(this)
            render()
        }
    }

    private fun toggleAutoStart(enable: Boolean) {
        if (enable && !MediaAutoStart.hasBluetoothPermission(this)) {
            // Android 12+ only delivers the Bluetooth connect broadcast to an app holding this,
            // so turning the setting on without it would produce a switch that does nothing.
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
            return
        }
        MediaAutoStart.setEnabled(this, enable)
        render()
    }

    // ---------------------------------------------------------------- text

    private fun stepTitle(step: Step): String = MirrorReadinessProbe.stepTitle(this, step)

    private fun causeText(cause: Cause): Int = when (cause) {
        Cause.AA_MISSING -> R.string.setup_cause_aa_missing
        Cause.AA_HIDDEN_FIXABLE -> R.string.setup_cause_aa_hidden_fixable
        Cause.AA_HIDDEN -> R.string.setup_cause_aa_hidden
        Cause.AA_CONNECTED -> R.string.setup_cause_aa_connected
        Cause.AA_READY -> R.string.setup_cause_aa_ready
        Cause.NOTIF_NOT_NEEDED -> R.string.setup_cause_notif_not_needed
        Cause.NOTIF_OK -> R.string.setup_cause_notif_ok
        Cause.NOTIF_MISSING -> R.string.setup_cause_notif_missing
        Cause.TOUCH_SHIZUKU_FULL -> R.string.setup_cause_touch_shizuku_full
        Cause.TOUCH_SHIZUKU -> R.string.setup_cause_touch_shizuku
        Cause.TOUCH_ACCESSIBILITY -> R.string.setup_cause_touch_accessibility
        Cause.TOUCH_SHIZUKU_NOT_GRANTED -> R.string.setup_cause_touch_shizuku_not_granted
        Cause.TOUCH_ACCESSIBILITY_STOPPED -> R.string.setup_cause_touch_accessibility_stopped
        Cause.TOUCH_ACCESSIBILITY_RESTRICTED -> R.string.setup_cause_touch_accessibility_restricted
        Cause.TOUCH_NONE_SHIZUKU_STOPPED -> R.string.setup_cause_touch_none_shizuku_stopped
        Cause.TOUCH_NONE -> R.string.setup_cause_touch_none
        Cause.CAPTURE_RUNNING -> R.string.setup_cause_capture_running
        Cause.CAPTURE_DENIED -> R.string.setup_cause_capture_denied
        Cause.CAPTURE_STOPPED_BY_SYSTEM -> R.string.setup_cause_capture_stopped_by_system
        Cause.CAPTURE_FAILED -> R.string.setup_cause_capture_failed
        Cause.CAPTURE_NEEDED -> R.string.setup_cause_capture_needed
        Cause.BATTERY_OK -> R.string.setup_cause_battery_ok
        Cause.BATTERY_OK_CHECK_AUTOSTART -> R.string.setup_cause_battery_ok_check_autostart
        Cause.BATTERY_RESTRICTED -> R.string.setup_cause_battery_restricted
        Cause.SPEED_WAITING -> R.string.setup_cause_speed_waiting
        Cause.SPEED_OK -> R.string.setup_cause_speed_ok
        Cause.SPEED_NO_PERMISSION -> R.string.setup_cause_speed_no_permission
        Cause.SPEED_SILENT -> R.string.setup_cause_speed_silent
    }

    private fun fixLabel(fix: Fix): Int = when (fix) {
        Fix.INSTALL_ANDROID_AUTO -> R.string.setup_fix_install_android_auto
        Fix.MAKE_VISIBLE_ON_CAR -> R.string.setup_fix_make_visible_on_car
        Fix.ALLOW_NOTIFICATIONS -> R.string.setup_fix_allow_notifications
        Fix.GRANT_SHIZUKU -> R.string.setup_fix_grant_shizuku
        Fix.OPEN_ACCESSIBILITY -> R.string.setup_fix_open_accessibility
        Fix.RESTART_ACCESSIBILITY -> R.string.setup_fix_restart_accessibility
        Fix.ALLOW_RESTRICTED_SETTINGS -> R.string.setup_fix_allow_restricted_settings
        Fix.START_CAPTURE -> R.string.setup_fix_start_capture
        Fix.STOP_CAPTURE -> R.string.setup_fix_stop_capture
        Fix.EXEMPT_BATTERY_WITH_SHIZUKU -> R.string.setup_fix_exempt_battery_with_shizuku
        Fix.ALLOW_BATTERY -> R.string.setup_fix_allow_battery
        Fix.OPEN_AUTOSTART -> R.string.setup_fix_open_autostart
    }

    private fun toast(message: Int) =
        Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()

    private var notificationsAsked: Boolean
        get() = getPreferences(Context.MODE_PRIVATE).getBoolean("notifications_asked", false)
        set(value) {
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean("notifications_asked", value).apply()
        }
}
