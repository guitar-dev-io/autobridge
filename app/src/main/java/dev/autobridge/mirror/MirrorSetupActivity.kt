package dev.autobridge.mirror

import android.Manifest
import android.app.Activity
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
import dev.autobridge.input.AccessibilityInputBackend
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.media.MediaAutoStart
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * One screen that gets the phone ready for the car, in the order the steps actually happen.
 *
 * Everything mirroring needs used to live somewhere else: notifications in the system app info,
 * touch in Android's accessibility list, and capture behind a button on the control centre. Each
 * one is a different app and none of them says what the other two are, so the common outcome was
 * a mirror session with no working touch and no obvious reason why.
 *
 * The step list itself is [MirrorReadiness] — pure, and unit tested. This activity only reads the
 * live state, draws it, and performs the one action a step offers.
 */
class MirrorSetupActivity : Activity() {

    companion object {
        private const val REQUEST_NOTIFICATIONS = 5101
        private const val REQUEST_CAPTURE = 5102
        private const val REQUEST_BLUETOOTH = 5103

        fun intent(context: Context): Intent = Intent(context, MirrorSetupActivity::class.java)
    }

    private val accent = AutoBridgeDesign.ACCENT_SYSTEM

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
            if (!granted) toast("Bluetooth permission is needed for this")
        }
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ProjectionService.start(this, resultCode, data)
            } else {
                toast("Screen capture was not allowed")
            }
        }
        render()
    }

    private fun status(): MirrorReadiness.Status {
        val shizukuRunning = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
        return MirrorReadiness.Status(
            notificationsRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            shizukuRunning = shizukuRunning,
            shizukuGranted = runCatching { ShizukuInputBackend.isPermissionGranted }.getOrDefault(false),
            realTouchAvailable = runCatching { ShizukuInputBackend.isRealTouchAvailable }.getOrDefault(false),
            accessibilityEnabled = AccessibilityInputBackend.isAvailable,
            projecting = ProjectionService.sessionState == ProjectionService.SessionState.READY ||
                ProjectionService.sessionState == ProjectionService.SessionState.STARTING,
            batteryOptimizationExempt = runCatching {
                getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            }.getOrDefault(true)
        )
    }

    private fun render() {
        val status = status()
        val steps = MirrorReadiness.steps(status)
        val next = MirrorReadiness.nextAction(status)

        val body = AutoBridgeDesign.body(this)
        body.stack(AutoBridgeDesign.sectionLabel(this, "Before mirroring"), gap = 2)
        steps.forEachIndexed { index, item ->
            body.stack(stepRow(index + 1, item))
        }

        body.stack(AutoBridgeDesign.sectionLabel(this, "When a car connects"), gap = 12)
        val autoStart = MediaAutoStart.isEnabled(this)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = "Start the media service on Bluetooth",
                subtitle = if (autoStart) {
                    "The session is ready as soon as the car pairs"
                } else {
                    "The session starts when you first play something"
                },
                accent = if (autoStart) accent else AutoBridgeDesign.TEXT_MUTED,
                badgeText = if (autoStart) "ON" else "OFF",
                onClick = { toggleAutoStart(!autoStart) }
            )
        )

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = "Car setup",
                    subtitle = when {
                        status.projecting -> "Mirroring is running"
                        next == null -> "Ready"
                        else -> "Next: ${next.title}"
                    },
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun stepRow(number: Int, item: MirrorReadiness.Item): View {
        val badge = when (item.state) {
            MirrorReadiness.State.DONE -> "✓"
            MirrorReadiness.State.BLOCKING -> "$number"
            MirrorReadiness.State.OPTIONAL -> "!"
        }
        val rowAccent = when (item.state) {
            MirrorReadiness.State.DONE -> AutoBridgeDesign.ACCENT_FILES
            MirrorReadiness.State.BLOCKING -> accent
            MirrorReadiness.State.OPTIONAL -> AutoBridgeDesign.ACCENT_RADIO
        }
        return AutoBridgeDesign.contentRow(
            context = this,
            title = item.title,
            subtitle = item.caption,
            accent = rowAccent,
            badgeText = badge,
            trailing = item.actionLabel,
            onTrailing = item.actionLabel?.let { { perform(item.step) } },
            onClick = { perform(item.step) }
        )
    }

    private fun perform(step: MirrorReadiness.Step) {
        when (step) {
            MirrorReadiness.Step.NOTIFICATIONS -> requestNotifications()
            MirrorReadiness.Step.TOUCH -> openTouchSetup()
            MirrorReadiness.Step.CAPTURE -> toggleProjection()
            MirrorReadiness.Step.BATTERY -> requestBatteryExemption()
        }
    }

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
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            )
        }
    }

    private fun openTouchSetup() {
        val shizukuRunning = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
        val granted = runCatching { ShizukuInputBackend.isPermissionGranted }.getOrDefault(false)
        when {
            shizukuRunning && !granted -> {
                ShizukuInputBackend.requestPermission()
                toast("Approve AutoBridge in Shizuku")
            }
            // Shizuku gives real multi-touch, so it stays the offer even once accessibility works.
            shizukuRunning -> ShizukuInputBackend.bind(this)
            else -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null)))
            toast("Allow AutoBridge to run in the background from here")
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

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private var notificationsAsked: Boolean
        get() = getPreferences(Context.MODE_PRIVATE).getBoolean("notifications_asked", false)
        set(value) {
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean("notifications_asked", value).apply()
        }
}
