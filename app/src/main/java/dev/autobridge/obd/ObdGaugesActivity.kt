package dev.autobridge.obd

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
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
import kotlin.math.min

/**
 * The phone's live gauges: pick the OBD2 adapter, connect, and watch the car.
 *
 * The page is built once and updated in place four times a second, because a rebuild would drop
 * the buttons under a finger. The connection itself ([ObdConnection]) belongs to the process, so
 * leaving this screen does not end it.
 */
class ObdGaugesActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, ObdGaugesActivity::class.java)
        private const val REQUEST_BLUETOOTH = 41
        private const val TICK_MS = 250L

        private val DIALS = listOf(Gauge.RPM, Gauge.SPEED)
        private val TILES = listOf(
            Gauge.COOLANT, Gauge.ATF, Gauge.BOOST, Gauge.BATTERY, Gauge.LOAD, Gauge.INTAKE,
            Gauge.THROTTLE, Gauge.FUEL_LEVEL, Gauge.AMBIENT, Gauge.MAP, Gauge.BARO,
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope: CoroutineScope = MainScope()
    private var scanJob: Job? = null
    private var pendingAfterPermission: (() -> Unit)? = null

    private var imperial = false
    private val dials = HashMap<Gauge, DialView>()
    private val tiles = HashMap<Gauge, Tile>()
    private var adapterTitle: TextView? = null
    private var statusLine: TextView? = null
    private var connectButton: TextView? = null

    private val accent get() = AutoBridgeDesign.ACCENT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        super.onCreate(savedInstanceState)
        dev.autobridge.settings.ScreenAppearance.applyWindow(this)
        // Gauges are glanced at; the screen sleeping under them would defeat the point.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        render()
        autoConnectIfAsked()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    // ---- Page ---------------------------------------------------------------------------------

    private fun render() {
        ObdSettings.applyProfile(this)
        imperial = ObdSettings.imperial(this)
        dials.clear()
        tiles.clear()
        val body = AutoBridgeDesign.body(this)

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.obd_section_adapter)), gap = 2)
        body.stack(connectionCard())

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.obd_title)), gap = 2)
        val dialRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((index, gauge) in DIALS.withIndex()) {
            val dial = DialView(this, GaugeLabels.text(this, gauge))
            dials[gauge] = dial
            dialRow.addView(dial, LinearLayout.LayoutParams(0, dp(170), 1f).apply {
                if (index == 0) marginEnd = dp(8)
            })
        }
        body.stack(dialRow)

        for (pair in TILES.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((index, gauge) in pair.withIndex()) {
                val tile = Tile(this, GaugeLabels.text(this, gauge))
                tiles[gauge] = tile
                row.addView(tile.view, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index == 0) marginEnd = dp(8)
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
            body.stack(row)
        }

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.obd_section_vehicle)), gap = 2)
        val vehicle = ObdSettings.profile(this)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.obd_transmission),
                subtitle = profileName(vehicle),
                accent = accent,
                badgeText = vehicle.make.take(1).ifEmpty { "?" },
                onClick = { chooseProfile(vehicle) }
            )
        )
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.obd_imperial),
                subtitle = getString(if (imperial) R.string.obd_imperial_on else R.string.obd_imperial_off),
                accent = if (imperial) accent else AutoBridgeDesign.TEXT_MUTED,
                badgeText = if (imperial) "ON" else "OFF",
                onClick = {
                    ObdSettings.setImperial(this, !imperial)
                    render()
                }
            )
        )
        val auto = ObdSettings.autoConnect(this)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.obd_auto),
                subtitle = getString(R.string.obd_auto_caption),
                accent = if (auto) accent else AutoBridgeDesign.TEXT_MUTED,
                badgeText = if (auto) "ON" else "OFF",
                onClick = {
                    ObdSettings.setAutoConnect(this, !auto)
                    render()
                }
            )
        )

        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.obd_dtc_title),
                subtitle = getString(R.string.obd_dtc_row_caption),
                accent = accent,
                badgeText = "!",
                onClick = { startActivity(ObdDtcActivity.intent(this)) }
            )
        )
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.obd_layout_row),
                subtitle = getString(R.string.obd_layout_row_caption),
                accent = accent,
                badgeText = "▦",
                onClick = { startActivity(ObdLayoutActivity.intent(this)) }
            )
        )
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.obd_diag),
                subtitle = getString(R.string.obd_diag_caption),
                accent = AutoBridgeDesign.TEXT_MUTED,
                badgeText = "i",
                onClick = { showDiagnostics() }
            )
        )

        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.obd_title),
                    subtitle = getString(R.string.obd_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
        refresh()
    }

    private fun connectionCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoBridgeDesign.surface(this@ObdGaugesActivity, AutoBridgeDesign.SURFACE, 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        adapterTitle = TextView(this).apply {
            textSize = 16f
            setTextColor(AutoBridgeDesign.TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusLine = TextView(this).apply {
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
        }
        card.addView(adapterTitle)
        card.addView(statusLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        connectButton = button(primary = true) { toggleConnection() }
        buttons.addView(connectButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        buttons.addView(
            button(primary = false, label = getString(R.string.obd_change_adapter)) { chooseAdapter() },
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        // Drops the link and opens it again: for an adapter that stopped answering, or one that was
        // switched off and on again, without choosing it a second time.
        card.addView(
            button(primary = false, label = getString(R.string.obd_reconnect)) { reconnect() },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) }
        )
        return card
    }

    private fun button(primary: Boolean, label: String = "", onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) AutoBridgeDesign.INK else AutoBridgeDesign.TEXT)
        background = AutoBridgeDesign.tappable(
            this@ObdGaugesActivity,
            if (primary) accent else AutoBridgeDesign.INK,
            12,
            accent
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    // ---- Live values --------------------------------------------------------------------------

    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        val telemetry = ObdConnection.telemetry.value
        for ((gauge, dial) in dials) {
            val value = telemetry.value(gauge, now)
            if (value == null) {
                dial.show(null, "—", GaugeFormat.unit(gauge, imperial), AutoBridgeDesign.TEXT_MUTED)
            } else {
                val spec = GaugeSpec.of(gauge)
                dial.show(
                    spec.fraction(value),
                    GaugeFormat.text(gauge, value, imperial),
                    GaugeFormat.unit(gauge, imperial),
                    zoneColor(spec.zone(value))
                )
            }
        }
        for ((gauge, tile) in tiles) {
            val value = telemetry.value(gauge, now)
            if (value == null) {
                tile.show("—", GaugeFormat.unit(gauge, imperial), AutoBridgeDesign.TEXT_MUTED)
            } else {
                val spec = GaugeSpec.of(gauge)
                val color = if (spec.zones != null) zoneColor(spec.zone(value)) else AutoBridgeDesign.TEXT
                tile.show(GaugeFormat.text(gauge, value, imperial), GaugeFormat.unit(gauge, imperial), color)
            }
        }

        val adapter = ObdSettings.adapter(this)
        val state = ObdConnection.state.value
        adapterTitle?.text = ObdAdapterMatcher.display(adapter?.name) ?: getString(R.string.obd_no_adapter)
        statusLine?.text = statusText(state)
        connectButton?.text = getString(if (ObdConnection.isRunning) R.string.obd_disconnect else R.string.obd_connect)
    }

    private fun statusText(state: ObdState): String = when (state.status) {
        ObdState.Status.IDLE -> getString(if (state.message == ObdConnection.GAVE_UP) R.string.obd_status_gave_up else R.string.obd_status_idle)
        ObdState.Status.CONNECTING -> getString(R.string.obd_status_connecting)
        ObdState.Status.INITIALIZING -> getString(R.string.obd_status_init)
        ObdState.Status.LIVE -> state.protocol?.let { getString(R.string.obd_status_live_protocol, it) }
            ?: getString(R.string.obd_status_live)
        ObdState.Status.RETRYING -> getString(R.string.obd_status_retry, state.message.orEmpty())
    }

    private fun zoneColor(zone: Zone): Int = ZoneColors.of(zone)

    // ---- Connecting ---------------------------------------------------------------------------

    private fun toggleConnection() {
        if (ObdConnection.isRunning) {
            ObdConnection.stop()
            refresh()
            return
        }
        connect()
    }

    private fun connect() {
        val adapter = ObdSettings.adapter(this) ?: return chooseAdapter()
        withBluetooth {
            ObdConnection.start(this, adapter, ObdSettings.profile(this))
            refresh()
        }
    }

    private fun reconnect() {
        if (ObdSettings.adapter(this) == null) return chooseAdapter()
        ObdConnection.stop()
        connect()
    }

    private fun autoConnectIfAsked() {
        ObdConnection.startIfWanted(this)
    }

    /** Runs [action] once Bluetooth is allowed, asking first when it is not. */
    private fun withBluetooth(action: () -> Unit) {
        val missing = ObdPermissions.missing(this)
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingAfterPermission = action
        Toast.makeText(this, R.string.obd_permission_needed, Toast.LENGTH_LONG).show()
        requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (action != null && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            action()
        }
    }

    /** The adapter's recent conversation, so a missing reading can be explained (and copied to share). */
    private fun showDiagnostics() {
        val text = ObdLog.snapshot(getString(R.string.obd_diag_enhanced), getString(R.string.obd_diag_recent))
        val view = TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(R.string.obd_diag)
            .setView(scroll)
            .setPositiveButton(R.string.obd_diag_copy) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("OBD", text))
            }
            .setNegativeButton(R.string.obd_diag_close, null)
            .show()
    }

    private fun profileName(profile: dev.autobridge.obd.profile.VehicleProfile): String =
        if (profile.verified) profile.name else profile.name + " • " + getString(R.string.obd_profile_unverified)

    private fun chooseProfile(current: dev.autobridge.obd.profile.VehicleProfile) {
        val options = dev.autobridge.obd.profile.BuiltInProfiles.all
        val labels = options.map { (if (it.id == current.id) "● " else "   ") + profileName(it) }
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(R.string.obd_transmission)
            .setItems(labels.toTypedArray()) { _, index ->
                ObdSettings.setProfileId(this, options[index].id)
                // The running connection chose what to ask the car at start; restart it to apply.
                if (ObdConnection.isRunning) {
                    ObdConnection.stop()
                    connect()
                }
                render()
            }
            .show()
    }

    private fun chooseAdapter(showAll: Boolean = false) {
        withBluetooth { showScanDialog(showAll) }
    }

    private fun showScanDialog(showAll: Boolean) {
        val found = ArrayList<AdapterHit>()
        val labels = ArrayList<String>()
        val list = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val builder = AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(R.string.obd_scanning)
            .setAdapter(list) { _, index -> found.getOrNull(index)?.let { chosen(it) } }
            .setNegativeButton(R.string.action_cancel, null)
        if (!showAll) builder.setNeutralButton(R.string.obd_show_all) { _, _ -> showScanDialog(true) }
        val dialog = builder.create()
        dialog.setOnDismissListener { scanJob?.cancel() }
        dialog.show()

        // Paired adapters first: they are listed even when they are not advertising right now.
        ObdScanner.paired(this).filter { showAll || it.likely }.forEach { hit ->
            found.add(hit)
            labels.add("${ObdAdapterMatcher.display(hit.name) ?: hit.address}   ${getString(R.string.obd_paired)}")
        }
        list.notifyDataSetChanged()

        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                ObdScanner.scan(this@ObdGaugesActivity).collect { hit ->
                    if (!showAll && !hit.likely) return@collect
                    if (found.any { it.address == hit.address }) return@collect
                    found.add(hit)
                    labels.add("${ObdAdapterMatcher.display(hit.name) ?: hit.address}   ${hit.rssi} dBm")
                    list.notifyDataSetChanged()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(
                    this@ObdGaugesActivity,
                    getString(R.string.obd_scan_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun chosen(hit: AdapterHit) {
        ObdConnection.stop()
        ObdSettings.setAdapter(this, ObdSettings.Adapter(hit.address, hit.name ?: hit.address))
        render()
        connect()
    }

    // ---- Views --------------------------------------------------------------------------------

    /** A small card: the name on top, the value large, the unit beside it. */
    private class Tile(context: Context, name: String) {
        private val value = TextView(context).apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AutoBridgeDesign.TEXT)
        }
        private val unit = TextView(context).apply {
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
        }
        val view: View = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 14)
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            addView(TextView(context).apply {
                text = name
                textSize = 12f
                maxLines = 1
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
            })
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            line.addView(value)
            line.addView(unit, LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = context.dp(4)
                bottomMargin = context.dp(4)
            })
            addView(line)
        }

        fun show(text: String, unitText: String, color: Int) {
            value.text = text
            value.setTextColor(color)
            unit.text = unitText
        }
    }

    /** A 270 degree dial: the coloured arc grows with the value, the number sits in the middle. */
    private class DialView(context: Context, private val name: String) : View(context) {
        private var fraction: Float? = null
        private var valueText = "—"
        private var unitText = ""
        private var color = AutoBridgeDesign.TEXT_MUTED

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = AutoBridgeDesign.tint(AutoBridgeDesign.TEXT_MUTED, 0.25f)
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = AutoBridgeDesign.TEXT_MUTED
        }
        private val box = RectF()

        init {
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 16)
        }

        fun show(fraction: Float?, text: String, unit: String, color: Int) {
            if (this.fraction == fraction && valueText == text && unitText == unit && this.color == color) return
            this.fraction = fraction
            valueText = text
            unitText = unit
            this.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val size = min(width, height).toFloat()
            val stroke = size * 0.075f
            val pad = stroke
            box.set(
                (width - size) / 2f + pad, (height - size) / 2f + pad,
                (width + size) / 2f - pad, (height + size) / 2f - pad,
            )
            track.strokeWidth = stroke
            arc.strokeWidth = stroke
            arc.color = color
            canvas.drawArc(box, 135f, 270f, false, track)
            fraction?.let { canvas.drawArc(box, 135f, 270f * it, false, arc) }

            number.color = color
            number.textSize = size * 0.24f
            val centerX = width / 2f
            val centerY = height / 2f
            canvas.drawText(valueText, centerX, centerY + number.textSize * 0.15f, number)
            caption.textSize = size * 0.09f
            canvas.drawText(unitText, centerX, centerY + number.textSize * 0.15f + caption.textSize * 1.5f, caption)
            canvas.drawText(name, centerX, height - stroke * 0.6f, caption)
        }
    }
}
