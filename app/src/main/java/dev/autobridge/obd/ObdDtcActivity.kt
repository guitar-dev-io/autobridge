package dev.autobridge.obd

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Trouble codes: read what the control units have stored and what is pending, and clear them after
 * a repair. Clearing needs the adapter live and an explicit confirmation, because it also resets
 * the readiness monitors an inspection looks at. Reading and clearing are the only things done
 * here; nothing is written to a control unit's memory beyond the standard "clear codes" request.
 */
class ObdDtcActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, ObdDtcActivity::class.java)
    }

    private val scope: CoroutineScope = MainScope()
    private var job: Job? = null
    private var results: LinearLayout? = null
    private var hasCodes = false

    private val accent get() = AutoBridgeDesign.ACCENT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        super.onCreate(savedInstanceState)
        dev.autobridge.settings.ScreenAppearance.applyWindow(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val body = AutoBridgeDesign.body(this)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button(true, R.string.obd_dtc_read) { read() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        buttons.addView(button(false, R.string.obd_dtc_clear) { confirmClear() }, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.stack(buttons, gap = 12)

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        results = container
        body.stack(container)
        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.obd_dtc_title),
                    subtitle = getString(R.string.obd_dtc_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
        // Opening the page reads straight away when the car is connected, so the answer is there
        // without a tap; a page opened with nothing connected just says so.
        read()
    }

    private fun button(primary: Boolean, label: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        setText(label)
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) AutoBridgeDesign.INK else AutoBridgeDesign.TEXT)
        background = AutoBridgeDesign.tappable(
            this@ObdDtcActivity, if (primary) accent else AutoBridgeDesign.INK, 12, accent
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun message(text: String) {
        val box = results ?: return
        box.removeAllViews()
        box.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            setPadding(dp(6), dp(8), dp(6), dp(8))
        })
    }

    private fun read() {
        job?.cancel()
        if (!ObdConnection.isRunning || ObdConnection.state.value.status != ObdState.Status.LIVE) {
            hasCodes = false
            message(getString(R.string.obd_dtc_connect_first))
            return
        }
        message(getString(R.string.obd_dtc_reading))
        job = scope.launch {
            try {
                val report = ObdConnection.readDtcs()
                if (report == null) message(getString(R.string.obd_dtc_connect_first)) else show(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(getString(R.string.obd_dtc_read_failed))
            }
        }
    }

    private fun show(report: ObdConnection.DtcReport) {
        val box = results ?: return
        box.removeAllViews()
        hasCodes = report.stored.isNotEmpty() || report.pending.isNotEmpty()
        unknown = false
        section(box, R.string.obd_dtc_stored, report.stored, pending = false)
        section(box, R.string.obd_dtc_pending, report.pending, pending = true)
        if (unknown) {
            box.stack(
                TextView(this).apply {
                    text = getString(R.string.obd_dtc_lookup)
                    textSize = 12f
                    setTextColor(AutoBridgeDesign.TEXT_MUTED)
                    setPadding(dp(6), dp(4), dp(6), 0)
                }
            )
        }
    }

    /** Set while drawing when some code has no description, so the hint about looking it up shows. */
    private var unknown = false

    private fun section(box: LinearLayout, title: Int, codes: List<String>, pending: Boolean) {
        box.stack(AutoBridgeDesign.sectionLabel(this, getString(title)), gap = 2)
        if (codes.isEmpty()) {
            box.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = getString(R.string.obd_dtc_none),
                    subtitle = "",
                    accent = AutoBridgeDesign.TEXT_MUTED,
                    badgeText = "✓",
                    onClick = {}
                )
            )
            return
        }
        for (code in codes) {
            val dtc = Dtc(code, pending)
            val system = getString(
                when (dtc.system) {
                    DtcSystem.POWERTRAIN -> R.string.obd_dtc_system_powertrain
                    DtcSystem.CHASSIS -> R.string.obd_dtc_system_chassis
                    DtcSystem.BODY -> R.string.obd_dtc_system_body
                    DtcSystem.NETWORK -> R.string.obd_dtc_system_network
                }
            )
            val kind = getString(if (dtc.generic) R.string.obd_dtc_generic else R.string.obd_dtc_ford)
            val description = DtcDatabase.describe(this, code)
            if (description == null) unknown = true
            box.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = code,
                    subtitle = if (description != null) "$description\n$system • $kind" else "$system • $kind",
                    accent = if (pending) AutoBridgeDesign.TEXT_MUTED else ZoneColors.CAUTION,
                    badgeText = code.first().toString(),
                    subtitleMaxLines = 3,
                    onClick = {}
                )
            )
        }
    }

    private fun confirmClear() {
        if (!ObdConnection.isRunning || ObdConnection.state.value.status != ObdState.Status.LIVE) {
            Toast.makeText(this, R.string.obd_dtc_connect_first, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(R.string.obd_dtc_clear_title)
            .setMessage(R.string.obd_dtc_clear_body)
            .setPositiveButton(R.string.obd_dtc_clear) { _, _ -> clear() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun clear() {
        job?.cancel()
        job = scope.launch {
            try {
                val ok = ObdConnection.clearDtcs()
                val text = if (ok == true) R.string.obd_dtc_cleared else R.string.obd_dtc_clear_failed
                Toast.makeText(this@ObdDtcActivity, text, Toast.LENGTH_SHORT).show()
                if (ok == true) read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@ObdDtcActivity, R.string.obd_dtc_clear_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
