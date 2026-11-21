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

    private fun render() {
        val body = AutoBridgeDesign.body(this)
        val status = DirectProjectionService.status

        body.stack(card(statusText(status)), gap = 10)
        body.stack(card(getString(R.string.aa_direct_certificate_notice), muted = true), gap = 14)

        // ── Car screen ───────────────────────────────────────────────────────
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.aa_direct_section_screen)), gap = 2)
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
        }, gap = 14)

        // ── Certificate ──────────────────────────────────────────────────────
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.aa_direct_section_certificate)), gap = 2)
        body.stack(row(getString(R.string.aa_direct_upload), getString(R.string.aa_direct_upload_caption)) { pickCertificate() })
        if (DirectStore.hasCredentials(this)) {
            body.stack(row(getString(R.string.aa_direct_clear), getString(R.string.aa_direct_clear_caption), AutoBridgeDesign.DANGER) {
                DirectStore.clearCredentials(this)
                render()
            })
        }

        // ── Connection ───────────────────────────────────────────────────────
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.aa_direct_section_connection)), gap = 2)
        if (status.listening) {
            body.stack(row(getString(R.string.aa_direct_stop_listening), getString(R.string.aa_direct_stop_listening_caption)) {
                DirectProjectionService.stopListening(this)
            })
        } else if (status.state == AapSession.State.CLOSED) {
            body.stack(row(getString(R.string.aa_direct_listen), getString(R.string.aa_direct_listen_caption)) { listenWireless() })
        }
        val soundOn = DirectStore.sendAudio(this)
        body.stack(row(
            getString(if (soundOn) R.string.aa_direct_sound_on else R.string.aa_direct_sound_off),
            getString(R.string.aa_direct_sound_caption),
        ) {
            DirectStore.setSendAudio(this, !soundOn)
            render()
        })
        if (status.soundReady && !status.soundOn) {
            body.stack(row(getString(R.string.aa_direct_sound_allow), getString(R.string.aa_direct_sound_allow_caption)) { allowSound() })
        }
        body.stack(row(getString(R.string.aa_direct_android_auto), getString(R.string.aa_direct_android_auto_caption)) { openAndroidAutoInfo() })
        body.stack(row(getString(R.string.aa_direct_copy_log), getString(R.string.aa_direct_copy_log_caption)) { copyLog() })
        if (DirectProjectionService.running) {
            body.stack(row(getString(R.string.aa_direct_stop), getString(R.string.aa_direct_stop_caption), AutoBridgeDesign.DANGER) {
                DirectProjectionService.stop(this)
            })
        }

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.aa_direct_section_log)), gap = 2)
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
        body.stack(log, gap = 24)

        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.aa_direct_title),
                    subtitle = getString(R.string.aa_direct_subtitle),
                    onBack = { finish() },
                ),
                body = body,
            ),
        )
    }

    private fun statusText(status: DirectProjectionService.Status): String {
        val lines = ArrayList<String>()
        lines += when (status.state) {
            AapSession.State.CLOSED -> getString(R.string.aa_direct_state_idle)
            AapSession.State.WAITING_FOR_CAR, AapSession.State.HANDSHAKE -> getString(R.string.aa_direct_state_handshake)
            AapSession.State.AUTHENTICATED, AapSession.State.CHANNELS_OPEN -> getString(R.string.aa_direct_state_connecting)
            AapSession.State.STREAMING -> getString(R.string.aa_direct_state_streaming)
        }
        status.headUnit?.let { lines += getString(R.string.aa_direct_car, it.label) }
        status.video?.let { lines += getString(R.string.aa_direct_video, it.width, it.height, it.framesPerSecond) }
        if (status.listening) lines += getString(R.string.aa_direct_state_listening)
        if (status.soundOn) lines += getString(R.string.aa_direct_state_sound)
        if (status.state == AapSession.State.CLOSED && status.endReason != null) {
            lines += getString(R.string.aa_direct_last_end, status.endReason)
        }
        val credentials = DirectStore.loadCredentials(this)
        lines += if (credentials == null) {
            getString(R.string.aa_direct_no_certificate)
        } else {
            val expiry = credentials.notAfter?.let { DateFormat.getDateInstance().format(it) }.orEmpty()
            getString(R.string.aa_direct_certificate, DirectStore.certificateName(this) ?: credentials.subject, expiry)
        }
        return lines.joinToString("\n")
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

    private fun card(text: String, muted: Boolean = false): View = TextView(this).apply {
        this.text = text
        textSize = if (muted) 13f else 15f
        setTextColor(if (muted) AutoBridgeDesign.TEXT_MUTED else AutoBridgeDesign.TEXT)
        if (!muted) typeface = AppFonts.medium()
        background = AutoBridgeDesign.surface(this@DirectProjectionActivity, AutoBridgeDesign.SURFACE, 16)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun row(title: String, subtitle: String, accent: Int = AutoBridgeDesign.ACCENT, onClick: () -> Unit): View =
        AutoBridgeDesign.contentRow(
            context = this,
            title = title,
            subtitle = subtitle,
            accent = accent,
            badgeText = title.take(1),
            subtitleMaxLines = 2,
            onClick = onClick,
        )

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
