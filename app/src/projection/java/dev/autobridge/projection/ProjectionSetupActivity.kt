package dev.autobridge.projection

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.SettingsUi
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

    /** True while the installer rewrite runs, so the button reads "Working…" and ignores taps. */
    private var working = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> render() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
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

    /**
     * The page in the app's settings style: a status card, the one action as a full-width button,
     * the Bridge Mirror switch, and the manual Android Auto steps as a numbered card. Rebuilt on
     * every change, like the other settings pages, so it never shows a stale state.
     */
    private fun render() {
        val visible = InstallerSpoofController.isAlreadyVisible(this)
        val installer = InstallerSpoofController.currentInstaller(this) ?: getString(R.string.projection_setup_installer_none)
        val shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizuku = getString(
            when {
                !shizukuRunning -> R.string.projection_setup_shizuku_not_running
                ShizukuInputBackend.isPermissionGranted -> R.string.projection_setup_shizuku_ready
                else -> R.string.projection_setup_shizuku_needs_permission
            }
        )
        val body = AutoBridgeDesign.body(this)

        body.stack(SettingsUi.group(this, getString(R.string.projection_setup_status_section), listOf(
            SettingsUi.valueRow(
                this, getString(R.string.projection_setup_status_label),
                getString(if (visible) R.string.projection_setup_status_visible else R.string.projection_setup_status_hidden)
            ) {},
            SettingsUi.valueRow(this, getString(R.string.projection_setup_installer_label), installer) {},
            SettingsUi.valueRow(this, "Shizuku", shizuku) {},
        )), gap = 14)

        if (!visible) {
            val label = when {
                working -> getString(R.string.projection_setup_working)
                InstallerSpoofController.canAttempt() -> getString(R.string.projection_setup_enable)
                shizukuRunning -> getString(R.string.projection_setup_grant_shizuku)
                else -> getString(R.string.projection_setup_enable)
            }
            body.stack(primaryButton(label, enabled = !working) { onAction() }, gap = 14)
        }

        val mirrorShown = isMirrorShown()
        body.stack(SettingsUi.group(this, "Bridge Mirror", listOf(
            SettingsUi.switchRow(
                this,
                title = getString(R.string.projection_setup_mirror_title),
                caption = getString(R.string.projection_setup_mirror_caption),
                icon = R.drawable.ic_tile_mirror,
                accent = AutoBridgeDesign.ACCENT,
                checked = mirrorShown,
            ) { setMirrorShown(!mirrorShown); render() },
        )), gap = 14)

        body.stack(SettingsUi.group(this, getString(R.string.projection_setup_steps_section), listOf(
            stepRow(1, getString(R.string.projection_setup_step_1)),
            stepRow(2, getString(R.string.projection_setup_step_2)),
            stepRow(3, getString(R.string.projection_setup_step_3)),
        )), gap = 14)

        body.stack(TextView(this).apply {
            text = getString(R.string.projection_setup_why)
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            setPadding(dp(4), 0, dp(4), 0)
        })

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.settings_projection_setup),
                    subtitle = getString(R.string.settings_projection_setup_caption),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /** The page's one action: a full-width accent button. */
    private fun primaryButton(label: String, enabled: Boolean, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(AutoBridgeDesign.INK)
        minHeight = dp(52)
        background = AutoBridgeDesign.tappable(
            this@ProjectionSetupActivity, AutoBridgeDesign.ACCENT, 16, AutoBridgeDesign.INK, stroke = AutoBridgeDesign.ACCENT
        )
        alpha = if (enabled) 1f else 0.5f
        isEnabled = enabled
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A numbered step: an accent-tinted number badge beside the instruction. */
    private fun stepRow(number: Int, text: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(TextView(this@ProjectionSetupActivity).apply {
            this.text = number.toString()
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = AutoBridgeDesign.surface(
                this@ProjectionSetupActivity, AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT, 0.16f), 12,
                AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT, 0.3f)
            )
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(TextView(this@ProjectionSetupActivity).apply {
            this.text = text
            textSize = 14.5f
            setTextColor(AutoBridgeDesign.TEXT)
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun onAction() {
        if (working) return
        // No privilege yet: ask Shizuku for permission if it is running, else point at the manual
        // steps below. Root, when present, needs no prompt and is used directly by the controller.
        if (!InstallerSpoofController.canAttempt()) {
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                ShizukuInputBackend.requestPermission()
            } else {
                toast(getString(R.string.projection_setup_start_shizuku))
            }
            return
        }
        working = true
        render()
        scope.launch {
            val result = withContext(Dispatchers.IO) { InstallerSpoofController.makeVisible(this@ProjectionSetupActivity) }
            working = false
            when (result) {
                is InstallerSpoofController.Result.AlreadyTrusted -> toast(getString(R.string.projection_setup_already_visible))
                is InstallerSpoofController.Result.Success ->
                    toast(getString(R.string.projection_setup_done, result.method.toString()))
                is InstallerSpoofController.Result.NoPrivilege -> toast(getString(R.string.projection_setup_no_privilege))
                is InstallerSpoofController.Result.Failed -> toast(getString(R.string.projection_setup_failed, result.detail))
            }
            if (!isFinishing && !isDestroyed) render()
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
        toast(getString(R.string.projection_setup_reconnect))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
