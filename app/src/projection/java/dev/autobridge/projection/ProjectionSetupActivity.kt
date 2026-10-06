package dev.autobridge.projection

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.input.ShizukuInputBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Walks the user through making the projection browser visible on Android Auto. Projection-flavor
 * only (personal/lab); never compiled into the store build.
 *
 * The hard requirement is the installer-source rewrite ([InstallerSpoofController]); the "Unknown
 * sources" toggle in Android Auto's own developer settings is a separate, manual step the app
 * cannot flip, so it is explained rather than automated.
 */
class ProjectionSetupActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, ProjectionSetupActivity::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var mirrorSwitch: Switch

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
        setContentView(buildLayout())
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        super.onDestroy()
    }

    private fun buildLayout(): View {
        fun pad(v: Int) = (v * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(24), pad(24), pad(24), pad(24))
        }
        column.addView(TextView(this).apply {
            text = "AutoBridge on Android Auto"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, pad(16), 0, pad(16))
        }
        column.addView(status)
        action = Button(this).apply {
            text = "Enable on Android Auto"
            setOnClickListener { onAction() }
        }
        column.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        mirrorSwitch = Switch(this).apply {
            text = "Show Bridge Mirror on Android Auto"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, pad(24), 0, 0)
            setOnCheckedChangeListener { _, checked -> setMirrorShown(checked) }
        }
        column.addView(mirrorSwitch)
        column.addView(TextView(this).apply {
            text = MIRROR_NOTE
            textSize = 13f
            setTextColor(Color.GRAY)
        })

        column.addView(TextView(this).apply {
            text = MANUAL_STEPS
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, pad(24), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 18))
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun render() {
        val visible = InstallerSpoofController.isAlreadyVisible(this)
        val installer = InstallerSpoofController.currentInstaller(this) ?: "none"
        val shizuku = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "not running"
            ShizukuInputBackend.isPermissionGranted -> "ready"
            else -> "needs permission"
        }
        status.text = buildString {
            appendLine(if (visible) "Status: visible on Android Auto" else "Status: hidden (sideloaded)")
            appendLine("Recorded installer: $installer")
            append("Shizuku: $shizuku")
        }
        action.visibility = if (visible) View.GONE else View.VISIBLE
        val shown = isMirrorShown()
        if (mirrorSwitch.isChecked != shown) mirrorSwitch.isChecked = shown
        action.text = when {
            InstallerSpoofController.canAttempt() -> "Enable on Android Auto"
            runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Grant Shizuku permission"
            else -> "Enable on Android Auto"
        }
    }

    private fun onAction() {
        // No privilege yet: ask Shizuku for permission if it is running, else point at the manual
        // steps below. Root, when present, needs no prompt and is used directly by the controller.
        if (!InstallerSpoofController.canAttempt()) {
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                ShizukuInputBackend.requestPermission()
            } else {
                toast("Start Shizuku first, or install through a store-spoofing installer")
            }
            return
        }
        action.isEnabled = false
        scope.launch {
            val result = withContext(Dispatchers.IO) { InstallerSpoofController.makeVisible(this@ProjectionSetupActivity) }
            action.isEnabled = true
            when (result) {
                is InstallerSpoofController.Result.AlreadyTrusted -> toast("Already visible on Android Auto")
                is InstallerSpoofController.Result.Success ->
                    toast("Done via ${result.method}. Reconnect Android Auto to see AutoBridge Browser.")
                is InstallerSpoofController.Result.NoPrivilege -> toast("No root or Shizuku permission available")
                is InstallerSpoofController.Result.Failed -> toast("Could not enable: ${result.detail}")
            }
            render()
        }
    }

    private val mirrorService get() = ComponentName(this, ProjectionMirrorCarService::class.java)

    /** Off unless switched on: the manifest declares the service disabled; see the comment there. */
    private fun isMirrorShown(): Boolean =
        packageManager.getComponentEnabledSetting(mirrorService) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /**
     * Lists or unlists Bridge Mirror on Android Auto by enabling or disabling its service. It is
     * the second projection service this app declares, added after the last version on which the
     * host offered its split screen beside Maps for Bridge Web, so this lets that be tested on the
     * car without a separate build.
     */
    private fun setMirrorShown(shown: Boolean) {
        if (shown == isMirrorShown()) return
        packageManager.setComponentEnabledSetting(
            mirrorService,
            if (shown) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP
        )
        toast("Reconnect Android Auto for the change to show on the car")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

private val MIRROR_NOTE = """
    Off by default. While Bridge Mirror is listed, Android Auto may stop offering its split screen
    (Bridge Web beside Maps, as with Fermata). Turn it on when you need the mirror, and off again
    for the split. Reconnect Android Auto after changing it.
""".trimIndent()

private val MANUAL_STEPS = """
    Android Auto only shows apps it believes came from the Play Store, so a sideloaded build is
    hidden until its recorded install source is rewritten. The button above does that through root
    or Shizuku (no root needed - start Shizuku via wireless debugging first).

    One manual step remains in Android Auto's own settings:
    1. Open Android Auto settings and tap the Version line 10 times to unlock Developer settings.
    2. In the three-dot menu, open Developer settings and turn on "Unknown sources".
    3. Reconnect to the car. "AutoBridge Browser" then appears in the Android Auto app list.
""".trimIndent()
