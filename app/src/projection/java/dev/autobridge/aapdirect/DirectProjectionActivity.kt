package dev.autobridge.aapdirect

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.aap.AapSession
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AppFonts
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.text.DateFormat

/**
 * Phone screen for Android Auto direct: connection status, the certificate, the car screen's
 * density, wireless listening, sound, and the connection log. A USB connection starts by itself
 * when a car attaches the phone (UsbAccessoryActivity); nothing here has to be open for that.
 */
class DirectProjectionActivity : Activity() {
    companion object {
        private const val REQUEST_CERT = 41
        private const val REQUEST_CAPTURE = 42
        private const val REQUEST_BT_PERMISSION = 43
        private const val REQUEST_MIC_PERMISSION = 44
        private const val GEARHEAD = "com.google.android.projection.gearhead"

        fun intent(context: Context): Intent = Intent(context, DirectProjectionActivity::class.java)
    }

    private val refresh: () -> Unit = { if (!isFinishing) render() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        dev.autobridge.settings.ScreenAppearance.applyWindow(this)
    }

    override fun onResume() {
        super.onResume()
        DirectProjectionService.listen(refresh)
        DirectLog.listen(logChanged)
        // Android may refuse to open the car screen from the background; with this screen in
        // front it can, so try again here.
        DirectProjectionService.showCarHome(this)
        render()
    }

    override fun onPause() {
        DirectProjectionService.unlisten(refresh)
        DirectLog.unlisten(logChanged)
        super.onPause()
    }

    private val logChanged: () -> Unit = { runOnUiThread { logView?.text = tail() } }
    private var logView: TextView? = null

    private enum class StepState { DONE, TODO, WARN, WAITING }

    private fun render() {
        val body = AutoBridgeDesign.body(this)
        val status = DirectProjectionService.status
        val credentials = DirectStore.loadCredentials(this)
        val androidAuto = androidAutoState()
        val connected = status.state != AapSession.State.CLOSED

        // ── Where things stand, in one sentence ──────────────────────────────
        body.stack(card(headline(status, credentials != null), strong = true), gap = 14)

        // ── Steps ────────────────────────────────────────────────────────────
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.aa_direct_section_steps)), gap = 2)

        // 1. Certificate
        if (credentials == null) {
            body.stack(step(1, StepState.TODO, getString(R.string.aa_direct_step_cert),
                getString(R.string.aa_direct_step_cert_missing), getString(R.string.aa_direct_action_upload)) { pickCertificate() })
        } else {
            val expiry = credentials.notAfter?.let { DateFormat.getDateInstance().format(it) }.orEmpty()
            body.stack(step(1, StepState.DONE, getString(R.string.aa_direct_step_cert),
                getString(R.string.aa_direct_step_cert_done, DirectStore.certificateName(this) ?: credentials.subject, expiry),
                getString(R.string.aa_direct_action_change)) { pickCertificate() })
        }

        // 2. Google's Android Auto must not take the car first
        when (androidAuto) {
            AndroidAutoState.ENABLED -> body.stack(step(2, StepState.WARN, getString(R.string.aa_direct_step_gearhead),
                getString(R.string.aa_direct_step_gearhead_enabled), getString(R.string.aa_direct_action_open)) { openAndroidAutoInfo() })
            AndroidAutoState.DISABLED -> body.stack(step(2, StepState.DONE, getString(R.string.aa_direct_step_gearhead),
                getString(R.string.aa_direct_step_gearhead_disabled), null) { openAndroidAutoInfo() })
            AndroidAutoState.MISSING -> body.stack(step(2, StepState.DONE, getString(R.string.aa_direct_step_gearhead),
                getString(R.string.aa_direct_step_gearhead_missing), null) {})
        }

        // 3. Connect
        when {
            connected -> body.stack(step(3, StepState.DONE, getString(R.string.aa_direct_step_connect),
                connectionDetail(status), getString(R.string.aa_direct_stop)) { DirectProjectionService.stop(this) })
            status.listening -> body.stack(step(3, StepState.WAITING, getString(R.string.aa_direct_step_connect),
                getString(R.string.aa_direct_step_connect_listening), getString(R.string.aa_direct_stop_listening)) {
                DirectProjectionService.stopListening(this)
            })
            else -> body.stack(step(3, if (credentials == null) StepState.WAITING else StepState.TODO, getString(R.string.aa_direct_step_connect),
                getString(R.string.aa_direct_step_connect_how), getString(R.string.aa_direct_action_wireless)) { listenWireless() })
        }
        if (status.soundReady && !status.soundOn) {
            body.stack(step(4, StepState.TODO, getString(R.string.aa_direct_sound_allow),
                getString(R.string.aa_direct_sound_allow_caption), getString(R.string.aa_direct_action_allow)) { allowSound() })
        }

        // ── Options ──────────────────────────────────────────────────────────
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.aa_direct_section_options)), gap = 14)
        val dpi = DirectStore.dpi(this)
        body.stack(TextView(this).apply {
            text = if (dpi > 0) getString(R.string.aa_direct_dpi, dpi) else getString(R.string.aa_direct_dpi_auto)
            textSize = 15f
            setTextColor(AutoBridgeDesign.TEXT)
            setPadding(dp(4), 0, 0, 0)
        }, gap = 6)
        body.stack(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(AutoBridgeDesign.pill(this@DirectProjectionActivity, getString(R.string.aa_direct_smaller)) { stepDpi(-1) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(this@DirectProjectionActivity), LinearLayout.LayoutParams(dp(8), 1))
            addView(AutoBridgeDesign.pill(this@DirectProjectionActivity, getString(R.string.aa_direct_larger)) { stepDpi(1) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }, gap = 10)
        val soundOn = DirectStore.sendAudio(this)
        body.stack(AutoBridgeDesign.contentRow(
            context = this,
            title = getString(R.string.aa_direct_sound_title),
            subtitle = getString(R.string.aa_direct_sound_caption),
            accent = if (soundOn) AutoBridgeDesign.ACCENT else AutoBridgeDesign.TEXT_MUTED,
            badgeText = if (soundOn) "ON" else "OFF",
            subtitleMaxLines = 3,
            onClick = {
                DirectStore.setSendAudio(this, !soundOn)
                render()
            },
        ))
        if (credentials != null) {
            body.stack(AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.aa_direct_clear),
                subtitle = getString(R.string.aa_direct_clear_caption),
                accent = AutoBridgeDesign.DANGER,
                badgeText = "✕",
                subtitleMaxLines = 2,
                onClick = {
                    DirectStore.clearCredentials(this)
                    render()
                },
            ))
        }

        // ── Log ──────────────────────────────────────────────────────────────
        body.stack(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(AutoBridgeDesign.sectionLabel(this@DirectProjectionActivity, getString(R.string.aa_direct_section_log)),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(AutoBridgeDesign.pill(this@DirectProjectionActivity, getString(R.string.aa_direct_copy_log)) { copyLog() })
        }, gap = 6)
        val log = TextView(this).apply {
            text = tail()
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            setTextIsSelectable(true)
            background = AutoBridgeDesign.surface(this@DirectProjectionActivity, AutoBridgeDesign.SURFACE, 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        logView = log
        body.stack(log, gap = 10)
        body.stack(card(getString(R.string.aa_direct_certificate_notice)), gap = 24)

        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.aa_direct_title),
                    subtitle = getString(R.string.aa_direct_subtitle_short),
                    onBack = { finish() },
                ),
                body = body,
            ),
        )
    }

    /** One line that says what is happening and, when nothing is, what to do next. */
    private fun headline(status: DirectProjectionService.Status, hasCertificate: Boolean): String {
        val main = when {
            status.state == AapSession.State.STREAMING -> getString(R.string.aa_direct_state_streaming)
            status.state == AapSession.State.WAITING_FOR_CAR || status.state == AapSession.State.HANDSHAKE ->
                getString(R.string.aa_direct_state_handshake)
            status.state != AapSession.State.CLOSED -> getString(R.string.aa_direct_state_connecting)
            !hasCertificate -> getString(R.string.aa_direct_next_cert)
            status.listening -> getString(R.string.aa_direct_state_listening)
            else -> getString(R.string.aa_direct_next_connect)
        }
        val ended = status.endReason?.takeIf { status.state == AapSession.State.CLOSED && !status.listening }
        return if (ended != null) main + "\n" + getString(R.string.aa_direct_last_end, ended) else main
    }

    private fun connectionDetail(status: DirectProjectionService.Status): String {
        val lines = ArrayList<String>()
        lines += status.headUnit?.let { getString(R.string.aa_direct_car, it.label) } ?: getString(R.string.aa_direct_state_handshake)
        status.video?.let { lines += getString(R.string.aa_direct_video, it.width, it.height, it.framesPerSecond) }
        if (status.soundOn) lines += getString(R.string.aa_direct_state_sound)
        return lines.joinToString("\n")
    }

    private enum class AndroidAutoState { ENABLED, DISABLED, MISSING }

    private fun androidAutoState(): AndroidAutoState = try {
        if (packageManager.getApplicationInfo(GEARHEAD, 0).enabled) AndroidAutoState.ENABLED else AndroidAutoState.DISABLED
    } catch (e: PackageManager.NameNotFoundException) {
        AndroidAutoState.MISSING
    }

    /** A numbered step: ✓ when done, its number when it is the thing to do, ! for a warning, … while waiting. */
    private fun step(number: Int, state: StepState, title: String, subtitle: String, action: String?, onAction: () -> Unit): View {
        val palette = dev.autobridge.design.ThemeHolder.palette
        return AutoBridgeDesign.contentRow(
            context = this,
            title = "$number. $title",
            subtitle = subtitle,
            accent = when (state) {
                StepState.DONE -> palette.good
                StepState.TODO -> AutoBridgeDesign.ACCENT
                StepState.WARN -> palette.warn
                StepState.WAITING -> AutoBridgeDesign.TEXT_MUTED
            },
            badgeText = when (state) {
                StepState.DONE -> "✓"
                StepState.TODO -> "$number"
                StepState.WARN -> "!"
                StepState.WAITING -> "…"
            },
            subtitleMaxLines = 4,
            actionLabel = action,
            onTrailing = action?.let { { onAction() } },
            onClick = onAction,
        )
    }

    private fun stepDpi(direction: Int) {
        val current = DirectStore.dpi(this).takeIf { it > 0 } ?: DirectProjectionService.status.video?.dpi?.takeIf { it > 0 } ?: 160
        DirectStore.setDpi(this, current + direction * DirectStore.DPI_STEP)
        DirectProjectionService.densityChanged(this)
        render()
    }

    private fun pickCertificate() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { startActivityForResult(pick, REQUEST_CERT) }
            .onFailure { toast(getString(R.string.aa_direct_no_picker)) }
    }

    private fun listenWireless() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT_PERMISSION)
            return
        }
        DirectProjectionService.listenWireless(this)
    }

    /** Sound needs the microphone permission (playback capture is a recording) and screen-capture consent. */
    private fun allowSound() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC_PERMISSION)
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE) }
            .onFailure { toast(it.message.orEmpty()) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            toast(getString(R.string.aa_direct_permission_needed))
            return
        }
        when (requestCode) {
            REQUEST_BT_PERMISSION -> DirectProjectionService.listenWireless(this)
            REQUEST_MIC_PERMISSION -> allowSound()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) DirectProjectionService.soundConsent(this, resultCode, data)
            return
        }
        if (requestCode != REQUEST_CERT || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let(::add)
        }
        val text = StringBuilder()
        val names = ArrayList<String>()
        for (uri in uris) {
            val content = runCatching { readSmall(uri) }.getOrNull()
            if (content == null) {
                toast(getString(R.string.aa_direct_upload_failed, uri.lastPathSegment.orEmpty()))
                return
            }
            text.append(content).append('\n')
            names += displayName(uri)
        }
        try {
            DirectStore.saveCredentials(this, text.toString(), names.joinToString(", "))
            DirectLog.add("certificate uploaded: ${names.joinToString(", ")}")
            toast(getString(R.string.aa_direct_upload_ok))
        } catch (e: Exception) {
            toast(getString(R.string.aa_direct_upload_invalid, e.message.orEmpty()))
        }
        render()
    }

    /** PEM files are a few kB; anything over 256 kB is not one. */
    private fun readSmall(uri: Uri): String? = contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readBytes()
        if (bytes.size > 256 * 1024) null else String(bytes, Charsets.UTF_8)
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    /**
     * When Google's Android Auto app is installed and enabled, Android may hand the car's USB
     * connection to it instead. Its app-info page is where the user disables it or clears its
     * "open by default" choice; AutoBridge does not change another app's state itself.
     */
    private fun openAndroidAutoInfo() {
        val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$GEARHEAD"))
        runCatching { startActivity(info) }.onFailure { toast(getString(R.string.aa_direct_android_auto_missing)) }
    }

    private fun copyLog() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("AutoBridge Android Auto direct log", DirectLog.text()))
        toast(getString(R.string.aa_direct_log_copied))
    }

    private fun tail(): String = DirectLog.text().lines().takeLast(14).joinToString("\n")
        .ifBlank { getString(R.string.aa_direct_log_empty) }

    private fun card(text: String, strong: Boolean = false): View = TextView(this).apply {
        this.text = text
        textSize = if (strong) 16f else 13f
        setTextColor(if (strong) AutoBridgeDesign.TEXT else AutoBridgeDesign.TEXT_MUTED)
        if (strong) typeface = AppFonts.medium()
        background = AutoBridgeDesign.surface(this@DirectProjectionActivity, AutoBridgeDesign.SURFACE, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
