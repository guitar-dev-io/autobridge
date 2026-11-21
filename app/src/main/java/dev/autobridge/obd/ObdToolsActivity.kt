package dev.autobridge.obd

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.obd.coding.CodingGuard
import dev.autobridge.obd.elm.ElmSession as UdsSession
import dev.autobridge.obd.transport.ObdTransport as UdsTransport
import dev.autobridge.obd.uds.Access
import dev.autobridge.obd.uds.AccessProbe
import dev.autobridge.obd.uds.DidDump
import dev.autobridge.obd.uds.EcuInfo
import dev.autobridge.obd.uds.EcuTargets
import dev.autobridge.obd.uds.UdsAnswer
import dev.autobridge.obd.uds.UdsInspector
import dev.autobridge.obd.uds.UdsScanner
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.CarToolViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Module tools on top of the gauge connection: scan modules, check access, back up identifiers, and
 * (hidden, see [CodingGate]) the coding page. It does not own the adapter. [ObdConnection] does;
 * every action here runs inside [ObdConnection.exclusively], which pauses the gauges meanwhile.
 */
class ObdToolsActivity : Activity(), ObdToolsHost {
    companion object {
        fun intent(context: Context): Intent = Intent(context, ObdToolsActivity::class.java)
    }

    private enum class Page { MODULES, CODING }

    /** What a module action found out. */
    private sealed class Outcome<out T> {
        object NotLive : Outcome<Nothing>()
        object Moving : Outcome<Nothing>()
        class Done<T>(val value: T) : Outcome<T>()
        class Failed(val message: String) : Outcome<Nothing>()
    }

    /** The session the module code talks through. */
    private class Car(val session: UdsSession)

    /** The hidden coding page; null in the Play build, which does not carry it (see src/coding). */
    private val codingPage: ObdCodingPage? by lazy { ObdCodingPage.create(this) }

    private var page = Page.MODULES
    override var modules: List<EcuInfo>? = null
        private set
    private var scanning = false
    override var busyModule: String? = null
        private set
    override var codingBusy = false
        private set
    private val probes = HashMap<String, AccessProbe>()
    private val backups = HashMap<String, List<DidDump>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val activity: Activity get() = this
    override val accent: Int get() = AutoBridgeDesign.ACCENT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        dev.autobridge.settings.ScreenAppearance.applyWindow(this)
        scope.launch { ObdConnection.state.collect { render() } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun go(target: Page) { page = target; render() }

    override fun render() {
        val live = ObdConnection.state.value.status == ObdState.Status.LIVE
        val coding = codingPage != null && CodingGate.available(this)
        if (!coding) page = Page.MODULES
        val body = AutoBridgeDesign.body(this)
        if (coding) body.stack(tabs(listOf(
            getString(R.string.obdt_modules) to (page == Page.MODULES),
            getString(R.string.coding_title) to (page == Page.CODING)
        )) { i -> go(if (i == 0) Page.MODULES else Page.CODING) }, gap = 4)
        if (!live) body.stack(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 14)
            setPadding(context.dp(14), context.dp(8), context.dp(8), context.dp(8))
            addView(CarToolViews.text(context, getString(R.string.obdt_not_live), 14f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(AutoBridgeDesign.pill(context, getString(R.string.obdt_go_gauges), primary = true, accent = accent) { finish() })
        }, gap = 8)
        when (page) {
            Page.MODULES -> buildModules(body)
            Page.CODING -> codingPage?.build(body)
        }
        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.obdt_title),
                    subtitle = if (live) getString(R.string.obdt_live) else getString(R.string.obdt_not_live),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /** A row of equal tabs; the selected one is filled. */
    override fun tabs(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): android.view.View =
        CarToolViews.pillRow(this, items.mapIndexed { i, (label, on) ->
            AutoBridgeDesign.pill(this, label, primary = on, accent = accent) { if (!on) onPick(i) }
        })

    /** Chips that scroll sideways when they do not fit. */
    override fun chips(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): android.view.View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEachIndexed { i, (label, on) ->
                addView(AutoBridgeDesign.pill(context, label, primary = on, accent = accent) { onPick(i) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(8) })
            }
        })
    }

    override val thai: Boolean get() = resources.configuration.locales[0].language == "th"

    override fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /**
     * Runs [work] against the adapter with the gauges paused. With [strict], the car must report a
     * speed of zero; otherwise only a reported speed above zero stops it. The work runs on the IO
     * dispatcher because the module code blocks.
     */
    private fun <T> withCar(strict: Boolean, work: (Car) -> T, finish: (Outcome<T>) -> Unit) {
        scope.launch {
            val outcome: Outcome<T> = try {
                val r = ObdConnection.exclusively { main ->
                    val speed = ObdConnection.telemetry.value.value(Gauge.SPEED, SystemClock.elapsedRealtime())
                    if (speed == null && strict || speed != null && speed > 0f) Outcome.Moving
                    else withContext(Dispatchers.IO) { Outcome.Done(work(Car(UdsSession(MainElmTransport(main))))) }
                }
                r ?: Outcome.NotLive
            } catch (e: Exception) {
                Outcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            finish(outcome)
        }
    }

    private fun <T> deliver(outcome: Outcome<T>, done: (T) -> Unit) {
        when (outcome) {
            is Outcome.Done -> done(outcome.value)
            Outcome.Moving -> toast(getString(R.string.obdt_stop_first))
            Outcome.NotLive -> toast(getString(R.string.obdt_connect_first))
            is Outcome.Failed -> toast(getString(R.string.obdt_failed, outcome.message))
        }
    }

    /** Runs [work] against one addressed module, only while the car is not moving. */
    override fun <T> onModule(ecu: EcuInfo, work: (UdsInspector) -> T, done: (T) -> Unit) {
        busyModule = ecu.requestId
        render()
        withCar(strict = false, work = { c ->
            try {
                check(c.session.addressEcu(ecu.requestId, ecu.replyId)) { "module not reachable" }
                work(UdsInspector(c.session))
            } finally {
                c.session.resetAddressing()
            }
        }) { outcome ->
            busyModule = null
            deliver(outcome, done)
            render()
        }
    }

    /** Runs a coding action, only while the car reports that it is stopped. */
    override fun <T> stationary(work: (UdsSession) -> T, done: (T) -> Unit) {
        codingBusy = true
        render()
        withCar(strict = true, work = { c -> try { work(c.session) } finally { c.session.resetAddressing() } }) { outcome ->
            codingBusy = false
            deliver(outcome, done)
            render()
        }
    }

    override fun <T> reading(work: (UdsSession) -> T, done: (T) -> Unit) {
        codingBusy = true
        render()
        withCar(strict = false, work = { c -> try { work(c.session) } finally { c.session.resetAddressing() } }) { outcome ->
            codingBusy = false
            deliver(outcome, done)
            render()
        }
    }

    private fun scanModules() {
        scanning = true
        render()
        withCar(strict = false, work = { c -> UdsScanner(c.session, EcuTargets.ford).scan() }) { outcome ->
            scanning = false
            deliver(outcome) { modules = it }
            render()
        }
    }

    private fun buildModules(body: LinearLayout) {
        body.stack(CarToolViews.footnote(this, getString(R.string.obdt_modules_hint)), gap = 4)
        body.stack(AutoBridgeDesign.pill(this, getString(if (scanning) R.string.obdt_scanning else R.string.obdt_scan), primary = true, accent = accent) {
            if (!scanning) scanModules()
        }, gap = 8)
        val list = modules ?: return
        if (list.isEmpty()) { body.stack(CarToolViews.footnote(this, getString(R.string.obdt_modules_none)), gap = 8); return }
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.obdt_modules_found, list.size)), gap = 4)
        for (ecu in list) {
            body.stack(CarToolViews.card(this) {
                addView(CarToolViews.text(context, listOfNotNull(ecu.name, "${ecu.requestId} → ${ecu.replyId}").joinToString(" · "), 16f, bold = true))
                ecu.values.forEach { (k, v) -> addView(CarToolViews.text(context, "$k: $v", 14f, AutoBridgeDesign.TEXT_MUTED, top = 4)) }
                probes[ecu.requestId]?.let { p ->
                    addView(CarToolViews.text(context, getString(R.string.obdt_access_extended, accessText(p.extendedSession, p.extendedRefusal)), 13f, top = 8))
                    val security = when {
                        p.alreadyUnlocked -> getString(R.string.obdt_access_open)
                        p.security == Access.ACCEPTED -> getString(R.string.obdt_access_locked, p.seedLength)
                        else -> accessText(p.security, p.securityRefusal)
                    }
                    addView(CarToolViews.text(context, getString(R.string.obdt_access_security, security), 13f, top = 2))
                }
                backups[ecu.requestId]?.let { addView(CarToolViews.text(context, getString(R.string.obdt_backup_result, it.size), 13f, top = 6)) }
                val busy = busyModule != null
                addView(CarToolViews.pillRow(context, listOfNotNull(
                    if (ecu.requestId.uppercase() in CodingGuard.noProbeModules) null
                    else AutoBridgeDesign.pill(context, getString(if (busyModule == ecu.requestId) R.string.obdt_working else R.string.obdt_check_access), accent = accent) { if (!busy) checkAccess(ecu) },
                    AutoBridgeDesign.pill(context, getString(R.string.obdt_backup), accent = accent) { if (!busy) backUp(ecu) },
                    backups[ecu.requestId]?.let { AutoBridgeDesign.pill(context, getString(R.string.obdt_backup_export), accent = accent) { exportBackup(ecu) } }
                )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
            }, gap = 8)
        }
    }

    private fun accessText(a: Access, refusal: String?) = when (a) {
        Access.ACCEPTED -> getString(R.string.obdt_access_ok)
        Access.REFUSED -> getString(R.string.obdt_access_refused, refusal ?: "")
        Access.SILENT -> getString(R.string.obdt_access_silent)
    }

    private fun checkAccess(ecu: EcuInfo) = onModule(ecu, { it.probe() }) { probes[ecu.requestId] = it }

    private fun backUp(ecu: EcuInfo) = onModule(ecu, { i -> i.backup(UdsInspector.DEFAULT_RANGES.flatMap { it.toList() }) }) { backups[ecu.requestId] = it }

    private fun exportBackup(ecu: EcuInfo) {
        val dumps = backups[ecu.requestId] ?: return
        val dir = java.io.File(cacheDir, "exports").apply { mkdirs() }
        val file = java.io.File(dir, "obd-backup-${ecu.requestId}.csv").apply { writeText(UdsInspector.csv(null, ecu, dumps)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.updates", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.obdt_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.obdt_backup_export))) }
    }
}
