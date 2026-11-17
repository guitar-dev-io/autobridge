package dev.autobridge.rear.wifi

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.util.concurrent.Executors

/**
 * Settings › Car & Connection › Rear screen. See `docs/REAR_SCREEN.md`.
 *
 * Both ends of phase 2 start here: on the tablet, "use this device as a rear screen"; on the
 * phone, the tablets found on the Wi-Fi, pairing with the code a tablet shows, and the paired list.
 */
class RearScreenSettingsActivity : Activity() {

    companion object {
        fun intent(context: Context) = Intent(context, RearScreenSettingsActivity::class.java)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val found = linkedMapOf<String, RearFinder.Found>()
    private var finder: RearFinder? = null
    private var releaseBack: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        releaseBack = dev.autobridge.ui.SystemBack.register(this) { finish() }
        render()
    }

    override fun onResume() {
        super.onResume()
        found.clear()
        finder = RearFinder(this) { tablet ->
            found[tablet.info.id] = tablet
            render()
        }.also { it.start() }
        render()
    }

    override fun onPause() {
        super.onPause()
        finder?.stop()
        finder = null
    }

    override fun onDestroy() {
        releaseBack()
        io.shutdown()
        super.onDestroy()
    }

    private fun render() {
        val accent = AutoBridgeDesign.ACCENT
        val body = AutoBridgeDesign.body(this)

        body.addView(AutoBridgeDesign.sectionLabel(this, getString(R.string.rear_settings_this_device)))
        body.stack(note(getString(R.string.rear_settings_this_device_note)))
        body.stack(AutoBridgeDesign.pill(this, getString(R.string.rear_settings_become_rear), primary = true) {
            startActivity(RearReceiverActivity.intent(this))
        })

        val paired = RearTablets.all(this)
        body.addView(AutoBridgeDesign.sectionLabel(this, getString(R.string.rear_settings_paired)))
        if (paired.isEmpty()) body.stack(note(getString(R.string.rear_settings_paired_none)))
        paired.forEachIndexed { index, tablet ->
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = tablet.name.ifBlank { tablet.host },
                    subtitle = if (index == 0) getString(R.string.rear_settings_in_use, "${tablet.host}:${tablet.port}")
                        else "${tablet.host}:${tablet.port}",
                    accent = accent,
                    trailing = getString(R.string.rear_settings_forget),
                    onTrailing = { RearTablets.forget(this, tablet.id); render() },
                ) {
                    // Tapping a paired tablet makes it the one the rear button sends to.
                    RearTablets.save(this, tablet)
                    render()
                }
            )
        }

        body.addView(AutoBridgeDesign.sectionLabel(this, getString(R.string.rear_settings_found)))
        val unpaired = found.values.filter { f -> paired.none { it.id == f.info.id } }
        if (unpaired.isEmpty()) body.stack(note(getString(R.string.rear_settings_searching)))
        unpaired.forEach { tablet ->
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = tablet.info.name,
                    subtitle = "${tablet.host}:${tablet.port}",
                    accent = accent,
                    actionLabel = getString(R.string.rear_settings_pair),
                ) { askCode(tablet.host, tablet.port, tablet.info.name) }
            )
        }
        body.stack(AutoBridgeDesign.pill(this, getString(R.string.rear_settings_add_by_address)) { askAddress() })

        val header = AutoBridgeDesign.header(this, getString(R.string.rear_settings_title), onBack = { finish() })
        AutoBridgeDesign.showPage(this, AutoBridgeDesign.page(this, header = header, body = body))
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(AutoBridgeDesign.TEXT_MUTED)
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setSingleLine()
    }

    private fun askAddress() {
        val address = field(getString(R.string.rear_settings_address_hint), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        AlertDialog.Builder(this)
            .setTitle(R.string.rear_settings_add_by_address)
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(address) })
            .setPositiveButton(R.string.rear_settings_next) { _, _ ->
                val text = address.text.toString().trim()
                val host = text.substringBefore(':')
                val port = text.substringAfter(':', "").toIntOrNull() ?: RearProtocol.PORT
                if (host.isNotEmpty()) askCode(host, port, host)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askCode(host: String, port: Int, name: String) {
        val code = field(getString(R.string.rear_settings_code_hint), InputType.TYPE_CLASS_NUMBER)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rear_settings_pair_with, name))
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(code) })
            .setPositiveButton(R.string.rear_settings_pair) { _, _ -> pair(host, port, code.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pair(host: String, port: Int, code: String) {
        io.execute {
            val info = RearClient.info(host, port)
            val result = if (info == null) RearClient.PairResult.Unreachable else RearClient.pair(host, port, code)
            main.post {
                if (isFinishing) return@post
                val message = when (result) {
                    is RearClient.PairResult.Paired -> {
                        RearTablets.save(this, RearTablets.Tablet(info!!.id, info.name, result.token, host, port))
                        render()
                        getString(R.string.rear_settings_paired_ok, info.name)
                    }
                    RearClient.PairResult.WrongCode -> getString(R.string.rear_settings_wrong_code)
                    RearClient.PairResult.Locked -> getString(R.string.rear_settings_locked)
                    RearClient.PairResult.Unreachable -> getString(R.string.rear_settings_unreachable)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
