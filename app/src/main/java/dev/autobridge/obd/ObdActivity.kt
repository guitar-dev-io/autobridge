package dev.autobridge.obd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.obd.dtc.DtcCode
import dev.autobridge.obd.dtc.DtcDatabase
import dev.autobridge.obd.elm.ObdFailure
import dev.autobridge.obd.obd2.DtcKind
import dev.autobridge.obd.obd2.FreezeFrame
import dev.autobridge.obd.obd2.Monitor
import dev.autobridge.obd.obd2.ObdResult
import dev.autobridge.obd.obd2.PidValue
import dev.autobridge.obd.obd2.Pids
import dev.autobridge.obd.obd2.Readiness
import dev.autobridge.obd.uds.EcuInfo
import dev.autobridge.obd.uds.EcuTargets
import dev.autobridge.obd.uds.UdsScanner
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.CarToolViews

/**
 * Phase 1 of docs/OBD_PLAN.md: connect to a paired ELM327 adapter, watch live values, read and
 * clear trouble codes. Generic OBD-II only - nothing here writes to a module beyond "clear codes".
 */
class ObdActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, ObdActivity::class.java)
        private const val REQUEST_BLUETOOTH = 4107
    }

    private enum class Page { HOME, LIVE, CODES, MODULES }

    private class CodesResult(
        val stored: List<DtcCode>, val pending: List<DtcCode>, val permanent: List<DtcCode>,
        val readiness: Readiness?, val freeze: FreezeFrame?
    )

    private var page = Page.HOME
    private var codes: CodesResult? = null
    private var codesError: String? = null
    private var modules: List<EcuInfo>? = null
    private var scanning = false
    private var bleScanning = false
    private val bleFound = LinkedHashMap<String, BluetoothDevice>()
    private val handler = Handler(Looper.getMainLooper())
    private val stopBleScan = Runnable { endBleScan() }
    private val bleCallback = object : ScanCallback() {
        @Suppress("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name != null && bleFound.put(result.device.address, result.device) == null) render()
        }
    }
    private val prefs by lazy { getSharedPreferences("obd", Context.MODE_PRIVATE) }
    private val liveViews = HashMap<Int, TextView>()
    private val accent: Int get() = AutoBridgeDesign.ACCENT
    private val thai: Boolean get() = resources.configuration.locales[0].language == "th"

    private val listener = object : ObdManager.Listener {
        override fun onStatus(status: ObdManager.Status) {
            if (status.state != ObdManager.State.CONNECTED && page != Page.HOME) page = Page.HOME
            render()
        }

        override fun onValues(values: Map<Int, PidValue>) {
            values.forEach { (id, v) -> liveViews[id]?.text = v.text }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        dev.autobridge.settings.ScreenAppearance.applyWindow(this)
        render()
    }

    override fun onStart() {
        super.onStart()
        ObdManager.addListener(listener)
        render()
    }

    override fun onStop() {
        endBleScan()
        ObdManager.removeListener(listener)
        ObdManager.requestPids(this, emptySet())
        super.onStop()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != Page.HOME) { go(Page.HOME) } else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun go(target: Page) {
        page = target
        ObdManager.requestPids(this, if (target == Page.LIVE) liveIds().toSet() else emptySet())
        render()
    }

    private fun liveIds(): List<Int> = Pids.defaultLive.filter { it in ObdManager.status.supportedPids }

    private fun render() {
        val status = ObdManager.status
        val body = AutoBridgeDesign.body(this)
        liveViews.clear()
        when (page) {
            Page.HOME -> buildHome(body, status)
            Page.LIVE -> buildLive(body)
            Page.CODES -> buildCodes(body)
            Page.MODULES -> buildModules(body)
        }
        val subtitle = when (status.state) {
            ObdManager.State.CONNECTED -> getString(R.string.obd_status_connected, status.adapterName ?: "")
            ObdManager.State.CONNECTING -> getString(R.string.obd_status_connecting, status.adapterName ?: "")
            ObdManager.State.DISCONNECTED -> getString(R.string.obd_status_disconnected)
        }
        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = when (page) {
                        Page.HOME -> getString(R.string.obd_title)
                        Page.LIVE -> getString(R.string.obd_live)
                        Page.CODES -> getString(R.string.obd_codes)
                        Page.MODULES -> getString(R.string.obd_modules)
                    },
                    subtitle = subtitle,
                    onBack = { if (page != Page.HOME) go(Page.HOME) else finish() }
                ),
                body = body
            )
        )
    }

    // ---- Home: connection --------------------------------------------------------------

    private fun buildHome(body: LinearLayout, status: ObdManager.Status) {
        status.error?.let { body.stack(CarToolViews.footnote(this, getString(R.string.obd_error_prefix, it)), gap = 8) }
        when (status.state) {
            ObdManager.State.CONNECTED -> {
                body.stack(CarToolViews.card(this) {
                    status.info?.let { info ->
                        addView(CarToolViews.text(context, info.identity, 16f, bold = true))
                        addView(CarToolViews.text(context, getString(R.string.obd_subtitle_protocol, info.protocol, info.voltage ?: "—"), 13f, AutoBridgeDesign.TEXT_MUTED, top = 4))
                    }
                    status.vin?.let { addView(CarToolViews.text(context, getString(R.string.obd_vin, it), 13f, AutoBridgeDesign.TEXT_MUTED, top = 4)) }
                })
                body.stack(CarToolViews.pillRow(this, listOf(
                    AutoBridgeDesign.pill(this, getString(R.string.obd_live), primary = true, accent = accent) { go(Page.LIVE) },
                    AutoBridgeDesign.pill(this, getString(R.string.obd_codes), accent = accent) { go(Page.CODES) },
                    AutoBridgeDesign.pill(this, getString(R.string.obd_modules), accent = accent) { go(Page.MODULES) },
                    AutoBridgeDesign.pill(this, getString(R.string.obd_disconnect), accent = accent) { ObdManager.disconnect() }
                )), gap = 12)
            }
            ObdManager.State.CONNECTING -> body.stack(CarToolViews.footnote(this, getString(R.string.obd_status_connecting, status.adapterName ?: "")), gap = 8)
            ObdManager.State.DISCONNECTED -> buildAdapterList(body)
        }
    }

    /** Connecting needs BLUETOOTH_CONNECT; scanning also BLUETOOTH_SCAN (Android 12+) or location (before). */
    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasBluetoothPermission(): Boolean =
        neededPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    @Suppress("MissingPermission")
    private fun startBleScan() {
        val scanner = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner ?: return
        bleFound.clear()
        bleScanning = true
        runCatching { scanner.startScan(bleCallback) }.onFailure { bleScanning = false }
        handler.postDelayed(stopBleScan, 10_000)
        render()
    }

    @Suppress("MissingPermission")
    private fun endBleScan() {
        handler.removeCallbacks(stopBleScan)
        if (!bleScanning) return
        bleScanning = false
        runCatching { (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(bleCallback) }
        render()
    }

    private fun buildAdapterList(body: LinearLayout) {
        body.stack(CarToolViews.footnote(this, getString(R.string.obd_pair_hint)), gap = 4)
        if (!hasBluetoothPermission()) {
            body.stack(CarToolViews.footnote(this, getString(R.string.obd_no_permission)), gap = 8)
            body.stack(AutoBridgeDesign.pill(this, getString(R.string.obd_grant), primary = true, accent = accent) {
                requestPermissions(neededPermissions(), REQUEST_BLUETOOTH)
            }, gap = 8)
            return
        }
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.obd_paired_adapters)), gap = 0)
        val adapter: BluetoothAdapter? = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        @Suppress("MissingPermission")
        val last = prefs.getString("last_address", null)
        val devices = runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
            .sortedByDescending { it.address == last }
        if (devices.isEmpty()) body.stack(CarToolViews.footnote(this, getString(R.string.obd_none_paired)), gap = 8)
        for (device in devices) {
            @Suppress("MissingPermission")
            val name = device.name ?: device.address
            body.stack(
                AutoBridgeDesign.contentRow(context = this, title = name, subtitle = if (device.address == last) "${device.address} · ${getString(R.string.obd_last_used)}" else device.address, accent = accent, badgeText = name.take(1).uppercase()) {
                    prefs.edit().putString("last_address", device.address).apply()
                    ObdManager.connect(applicationContext, BluetoothSppTransport(device), name)
                },
                gap = 8
            )
        }
        body.stack(AutoBridgeDesign.pill(this, getString(R.string.obd_open_bt_settings), accent = accent) {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }, gap = 12)

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.obd_ble_found)), gap = 0)
        for (device in bleFound.values) {
            @Suppress("MissingPermission")
            val name = device.name ?: device.address
            body.stack(
                AutoBridgeDesign.contentRow(context = this, title = name, subtitle = device.address, accent = accent, badgeText = "LE") {
                    endBleScan()
                    ObdManager.connect(applicationContext, BleTransport(applicationContext, device), name)
                },
                gap = 8
            )
        }
        if (!bleScanning && bleFound.isEmpty()) body.stack(CarToolViews.footnote(this, getString(R.string.obd_ble_none)), gap = 4)
        body.stack(AutoBridgeDesign.pill(this, getString(if (bleScanning) R.string.obd_ble_scanning else R.string.obd_ble_scan), primary = true, accent = accent) {
            if (!bleScanning) startBleScan()
        }, gap = 8)
    }

    // ---- Live data ---------------------------------------------------------------------

    private fun buildLive(body: LinearLayout) {
        val ids = liveIds()
        if (ids.isEmpty()) {
            body.stack(CarToolViews.footnote(this, getString(R.string.obd_live_none)), gap = 8)
            return
        }
        body.stack(CarToolViews.card(this) {
            for (id in ids) {
                val pid = Pids.find(id) ?: continue
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, context.dp(8), 0, context.dp(8))
                }
                row.addView(CarToolViews.text(context, pid.name, 14f, AutoBridgeDesign.TEXT_MUTED), LinearLayout.LayoutParams(0, -2, 1f))
                val value = CarToolViews.text(context, "—", 18f, bold = true)
                liveViews[id] = value
                row.addView(value)
                addView(row)
            }
        })
        body.stack(CarToolViews.pillRow(this, listOf(
            AutoBridgeDesign.pill(this, getString(if (ObdManager.recording) R.string.obd_log_stop else R.string.obd_log_start), primary = !ObdManager.recording, accent = accent) {
                if (!ObdManager.recording) ObdManager.log.clear()
                ObdManager.recording = !ObdManager.recording
                render()
            },
            AutoBridgeDesign.pill(this, getString(R.string.obd_log_export), accent = accent) { exportLog() }
        )), gap = 12)
        body.stack(CarToolViews.footnote(this, getString(R.string.obd_live_waiting)), gap = 4)
    }

    // ---- Modules -----------------------------------------------------------------------

    private fun buildModules(body: LinearLayout) {
        body.stack(CarToolViews.footnote(this, getString(R.string.obd_modules_hint)), gap = 4)
        body.stack(AutoBridgeDesign.pill(this, getString(if (scanning) R.string.obd_scanning else R.string.obd_scan), primary = true, accent = accent) {
            if (!scanning) scanModules()
        }, gap = 8)
        val list = modules ?: return
        if (list.isEmpty()) { body.stack(CarToolViews.footnote(this, getString(R.string.obd_modules_none)), gap = 8); return }
        for (ecu in list) {
            body.stack(CarToolViews.card(this) {
                addView(CarToolViews.text(context, listOfNotNull(ecu.name, "${ecu.requestId} → ${ecu.replyId}").joinToString(" · "), 16f, bold = true))
                ecu.values.forEach { (k, v) -> addView(CarToolViews.text(context, "$k: $v", 14f, AutoBridgeDesign.TEXT_MUTED, top = 4)) }
            }, gap = 8)
        }
    }

    private fun scanModules() {
        scanning = true
        render()
        ObdManager.submit({ c -> UdsScanner(c.session, EcuTargets.ford).scan() }) { result ->
            scanning = false
            modules = result.getOrDefault(emptyList())
            render()
        }
    }

    // ---- Trouble codes -----------------------------------------------------------------

    private fun buildCodes(body: LinearLayout) {
        body.stack(CarToolViews.pillRow(this, listOf(
            AutoBridgeDesign.pill(this, getString(R.string.obd_read_codes), primary = true, accent = accent) { readCodes() },
            AutoBridgeDesign.pill(this, getString(R.string.obd_clear_codes), accent = accent) { confirmClear() }
        )), gap = 4)
        codesError?.let { body.stack(CarToolViews.footnote(this, getString(R.string.obd_codes_failed, it)), gap = 8) }
        val result = codes
        if (result == null) {
            if (codesError == null) body.stack(CarToolViews.footnote(this, getString(R.string.obd_codes_hint)), gap = 8)
            return
        }
        result.readiness?.let { r ->
            body.stack(CarToolViews.footnote(this, if (r.milOn) getString(R.string.obd_mil_on, r.codeCount) else getString(R.string.obd_mil_off)), gap = 8)
        }
        codeSection(body, R.string.obd_codes_stored, result.stored)
        codeSection(body, R.string.obd_codes_pending, result.pending)
        codeSection(body, R.string.obd_codes_permanent, result.permanent)
        result.freeze?.let { ff ->
            body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.obd_freeze)), gap = 0)
            body.stack(CarToolViews.card(this) {
                ff.triggeringCode?.let { addView(CarToolViews.text(context, getString(R.string.obd_freeze_code, it.code), 13f, AutoBridgeDesign.TEXT_MUTED)) }
                ff.values.forEach { v -> addView(CarToolViews.text(context, "${v.pid.name}: ${v.text}", 14f, top = 6)) }
            }, gap = 8)
        }
        result.readiness?.let { r ->
            body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.obd_readiness)), gap = 0)
            body.stack(CarToolViews.card(this) {
                r.monitors.forEach { m -> addView(CarToolViews.text(context, "${m.name} — ${monitorText(m)}", 14f, if (m.supported && !m.complete) AutoBridgeDesign.DANGER else AutoBridgeDesign.TEXT, top = 6)) }
            }, gap = 8)
        }
    }

    private fun monitorText(m: Monitor) = getString(
        when {
            !m.supported -> R.string.obd_monitor_unsupported
            m.complete -> R.string.obd_monitor_ready
            else -> R.string.obd_monitor_not_ready
        }
    )

    private fun codeSection(body: LinearLayout, title: Int, list: List<DtcCode>) {
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(title)), gap = 0)
        if (list.isEmpty()) { body.stack(CarToolViews.footnote(this, getString(R.string.obd_codes_none)), gap = 4); return }
        for (code in list) {
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this, title = code.code, subtitle = DtcDatabase.describe(code, thai), accent = accent,
                    badgeText = code.system.toString(), subtitleMaxLines = 3, onClick = {}
                ),
                gap = 8
            )
        }
    }

    private fun readCodes() {
        ObdManager.submit({ c ->
            fun list(kind: DtcKind) = when (val r = c.readDtcs(kind)) {
                is ObdResult.Ok -> r.value
                is ObdResult.Err -> throw IllegalStateException(r.failure.message)
            }
            val stored = list(DtcKind.STORED)
            // Pending and permanent are optional on older cars; a failure there is not a failure here.
            val pending = (c.readDtcs(DtcKind.PENDING) as? ObdResult.Ok)?.value.orEmpty()
            val permanent = (c.readDtcs(DtcKind.PERMANENT) as? ObdResult.Ok)?.value.orEmpty()
            val readiness = (c.readiness() as? ObdResult.Ok)?.value
            val freeze = if (stored.isNotEmpty()) (c.freezeFrame() as? ObdResult.Ok)?.value else null
            CodesResult(stored, pending, permanent, readiness, freeze)
        }) { result ->
            result.onSuccess { codes = it; codesError = null }.onFailure { codes = null; codesError = it.message }
            render()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.obd_clear_title)
            .setMessage(R.string.obd_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.obd_clear_confirm) { _, _ -> clearCodes() }
            .show()
    }

    private fun clearCodes() {
        ObdManager.submit({ c ->
            // Refuse while the car reports it is moving; a car that does not report speed is let through.
            val speed = (c.readPid(Pids.SPEED) as? ObdResult.Ok)?.value?.value ?: 0.0
            if (speed > 0.0) null else c.clearDtcs()
        }) { result ->
            val outcome = result.getOrNull()
            when {
                result.isFailure -> toast(getString(R.string.obd_clear_failed, result.exceptionOrNull()?.message ?: ""))
                outcome == null -> toast(getString(R.string.obd_clear_moving))
                outcome is ObdResult.Err -> toast(getString(R.string.obd_clear_failed, outcome.failure.message))
                else -> { toast(getString(R.string.obd_clear_done)); readCodes() }
            }
        }
    }

    /** Shares the recording as CSV, read access granted to the chosen app only. */
    private fun exportLog() {
        if (ObdManager.log.size == 0) { toast(getString(R.string.obd_log_empty)); return }
        val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
        val file = java.io.File(dir, "obd-log.csv").apply { writeText(ObdManager.log.csv()) }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.obd_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.obd_log_export))) }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
