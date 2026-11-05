package dev.autobridge.obd

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.obd.coding.CatalogEntry
import dev.autobridge.obd.coding.CodingCatalog
import dev.autobridge.obd.coding.CodingChange
import dev.autobridge.obd.coding.CodingGuard
import dev.autobridge.obd.coding.CodingPlan
import dev.autobridge.obd.coding.CodingRecipe
import dev.autobridge.obd.coding.PlanResult
import dev.autobridge.obd.coding.UdsWriter
import dev.autobridge.obd.coding.WriteOutcome
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Module tools on top of the gauge connection: scan modules, check access, back up identifiers, and
 * (hidden, see [CodingGate]) the coding page. It does not own the adapter. [ObdConnection] does;
 * every action here runs inside [ObdConnection.exclusively], which pauses the gauges meanwhile.
 */
class ObdToolsActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, ObdToolsActivity::class.java)
    }

    private enum class Page { HOME, MODULES, CODING }

    /** What a module action found out. */
    private sealed class Outcome<out T> {
        object NotLive : Outcome<Nothing>()
        object Moving : Outcome<Nothing>()
        class Done<T>(val value: T) : Outcome<T>()
        class Failed(val message: String) : Outcome<Nothing>()
    }

    /** The session the module code talks through. */
    private class Car(val session: UdsSession)

    private var page = Page.HOME
    private var modules: List<EcuInfo>? = null
    private var scanning = false
    private var busyModule: String? = null
    private var recipeText = ""
    private var plans: List<PlanResult>? = null
    private var codingLog: List<String> = emptyList()
    private var codingBusy = false
    private val probes = HashMap<String, AccessProbe>()
    private val backups = HashMap<String, List<DidDump>>()
    private val catalog: List<CatalogEntry> by lazy {
        runCatching { assets.open("obd/ranger6g_2024_catalog.csv").bufferedReader().use { CodingCatalog.parse(it.readText()) } }.getOrDefault(emptyList())
    }
    private val catalogReads = HashMap<String, UdsAnswer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val accent: Int get() = AutoBridgeDesign.ACCENT

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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page != Page.HOME) { page = Page.HOME; render() } else super.onBackPressed()
    }

    private fun go(target: Page) { page = target; render() }

    private fun render() {
        val live = ObdConnection.state.value.status == ObdState.Status.LIVE
        val body = AutoBridgeDesign.body(this)
        when {
            page == Page.MODULES -> buildModules(body)
            page == Page.CODING && CodingGate.available(this) -> buildCoding(body)
            else -> { page = Page.HOME; buildHome(body, live) }
        }
        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = when (page) {
                        Page.HOME -> getString(R.string.obdt_title)
                        Page.MODULES -> getString(R.string.obdt_modules)
                        Page.CODING -> getString(R.string.coding_title)
                    },
                    subtitle = if (live) getString(R.string.obdt_live) else getString(R.string.obdt_not_live),
                    onBack = { if (page != Page.HOME) go(Page.HOME) else finish() }
                ),
                body = body
            )
        )
    }

    private fun buildHome(body: LinearLayout, live: Boolean) {
        if (!live) body.stack(CarToolViews.footnote(this, getString(R.string.obdt_connect_first)), gap = 8)
        body.stack(CarToolViews.pillRow(this, listOfNotNull(
            AutoBridgeDesign.pill(this, getString(R.string.obdt_modules), primary = true, accent = accent) { go(Page.MODULES) },
            if (CodingGate.available(this)) AutoBridgeDesign.pill(this, getString(R.string.coding_title), accent = accent) { go(Page.CODING) } else null
        )), gap = 8)
    }

    private val thai: Boolean get() = resources.configuration.locales[0].language == "th"

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

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
    private fun <T> onModule(ecu: EcuInfo, work: (UdsInspector) -> T, done: (T) -> Unit) {
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
    private fun <T> stationary(work: (Car) -> T, done: (T) -> Unit) {
        codingBusy = true
        render()
        withCar(strict = true, work = { c -> try { work(c) } finally { c.session.resetAddressing() } }) { outcome ->
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
                    AutoBridgeDesign.pill(context, getString(if (busyModule == ecu.requestId) R.string.obdt_working else R.string.obdt_check_access), accent = accent) { if (!busy) checkAccess(ecu) },
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

    /** Runs [work] against one addressed module, only while the car reports it is stationary. */

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


    // ---- Coding (hidden; see CodingGate) ------------------------------------------------

    private fun buildCoding(body: LinearLayout) {
        body.stack(CarToolViews.footnote(this, getString(R.string.coding_warning)), gap = 6)
        body.stack(CarToolViews.footnote(this, getString(R.string.coding_format)), gap = 6)
        body.stack(EditText(this).apply {
            hint = getString(R.string.coding_recipe_hint)
            setText(recipeText)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT)
            setHintTextColor(AutoBridgeDesign.TEXT_MUTED)
            minLines = 6
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 12)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) { recipeText = s?.toString().orEmpty() }
            })
        }, gap = 8)
        val ready = plans?.takeIf { p -> p.isNotEmpty() && p.all { it is PlanResult.Ready } }
        body.stack(CarToolViews.pillRow(this, listOfNotNull(
            AutoBridgeDesign.pill(this, getString(if (codingBusy) R.string.obdt_working else R.string.coding_preview), primary = ready == null, accent = accent) { if (!codingBusy) previewCoding() },
            ready?.let { AutoBridgeDesign.pill(this, getString(R.string.coding_apply), primary = true, accent = accent) { if (!codingBusy) confirmApply(it.map { r -> (r as PlanResult.Ready).plan }) } }
        )), gap = 8)
        plans?.forEach { r ->
            body.stack(CarToolViews.footnote(this, when (r) {
                is PlanResult.Ready -> getString(R.string.coding_plan_ok, r.plan.summary)
                is PlanResult.Blocked -> getString(R.string.coding_plan_blocked, r.reason)
            }), gap = 2)
        }
        codingLog.forEach { body.stack(CarToolViews.footnote(this, it), gap = 2) }

        buildCatalog(body)

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.coding_backups)), gap = 0)
        val saved = CodingBackupStore.list(this)
        if (saved.isEmpty()) body.stack(CarToolViews.footnote(this, getString(R.string.coding_no_backups)), gap = 4)
        saved.take(20).forEach { b ->
            val value = CodingChange.hex(b.before)
            body.stack(AutoBridgeDesign.contentRow(
                context = this, title = "${b.module} %04X".format(b.did), subtitle = "$value · ${java.text.DateFormat.getDateTimeInstance().format(b.timeMs)}",
                accent = accent, badgeText = b.module.take(3), actionLabel = getString(R.string.coding_restore), onTrailing = { confirmRestore(b) }, onClick = {}
            ), gap = 8)
        }
    }

    /** Read-only reference list: where the community says each setting lives, and what the car holds there now. */
    private fun buildCatalog(body: LinearLayout) {
        if (catalog.isEmpty()) return
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.catalog_title)), gap = 0)
        body.stack(CarToolViews.footnote(this, getString(R.string.catalog_hint)), gap = 6)
        val th = resources.configuration.locales[0].language == "th"
        for (e in catalog) {
            val ecu = modules?.firstOrNull { it.requestId.equals(e.ecu, ignoreCase = true) }
            body.stack(CarToolViews.card(this) {
                addView(CarToolViews.text(context, if (th) e.featureTh else e.featureEn, 15f, bold = true))
                addView(CarToolViews.text(context, "${e.moduleName} ${e.address} · ${e.risk} · ${e.communityTab} · ${e.thaiValidation}", 12f, AutoBridgeDesign.TEXT_MUTED, top = 2))
                addView(CarToolViews.text(context, listOf(e.guiParameter, e.settingNotes, e.engineeringNotes).filter { it.isNotBlank() }.joinToString("\n"), 12f, AutoBridgeDesign.TEXT_MUTED, top = 4))
                catalogReads[e.id]?.let { a ->
                    addView(CarToolViews.text(context, when (a) {
                        is UdsAnswer.Positive -> catalogValue(e, a.data)
                        is UdsAnswer.Negative -> getString(R.string.catalog_refused, a.text)
                        UdsAnswer.Silent -> getString(R.string.catalog_silent)
                    }, 13f, top = 6).apply { typeface = android.graphics.Typeface.MONOSPACE })
                }
                val label = when {
                    ecu == null -> getString(R.string.catalog_not_scanned)
                    busyModule == ecu.requestId -> getString(R.string.obdt_working)
                    else -> getString(R.string.catalog_read)
                }
                addView(CarToolViews.pillRow(context, listOf(
                    AutoBridgeDesign.pill(context, label, accent = accent) {
                        if (ecu != null && busyModule == null) onModule(ecu, { it.readDid(e.did) }) { catalogReads[e.id] = it }
                    }
                )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
            }, gap = 8)
        }
    }

    /** DID bytes in groups of four digits; digits the catalog pattern marks are highlighted. */
    private fun catalogValue(e: CatalogEntry, data: ByteArray): CharSequence {
        val (hex, marks) = CodingCatalog.marked(e, data)
        val out = android.text.SpannableStringBuilder("%04X: ".format(e.did))
        hex.forEachIndexed { i, ch ->
            val start = out.length
            out.append(ch)
            if (i in marks) out.setSpan(android.text.style.BackgroundColorSpan(accent), start, out.length, 0)
            if (i % 4 == 3) out.append(' ')
        }
        if (marks.isEmpty() && e.markedNibbles.isNotEmpty()) out.append("\n").append(getString(R.string.catalog_layout_unknown))
        return out
    }


    private fun previewCoding() {
        codingLog = emptyList()
        when (val parsed = CodingRecipe.parse(recipeText)) {
            is CodingRecipe.Parsed.Error -> { plans = null; toast(getString(R.string.coding_parse_error, parsed.line, parsed.message)) }
            is CodingRecipe.Parsed.Ok -> stationary({ c -> val w = UdsWriter(c.session); parsed.recipe.changes.map { w.plan(it) } }) { plans = it }
        }
    }

    private fun confirmApply(ready: List<CodingPlan>) {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; setSingleLine() }
        val highImpact = ready.any { it.change.module.uppercase() in CodingGuard.highImpactModules }
        val message = getString(R.string.coding_confirm_message, ready.size) +
            (if (highImpact) "\n\n" + getString(R.string.coding_confirm_high_impact) else "") +
            "\n\n" + ready.joinToString("\n") { it.summary }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.coding_confirm_title)
            .setMessage(message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.coding_apply) { _, _ ->
                if (input.text.toString().trim().equals(getString(R.string.coding_confirm_word), ignoreCase = true)) applyCoding(ready)
            }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = s.toString().trim().equals(getString(R.string.coding_confirm_word), ignoreCase = true)
            }
        })
    }

    private fun describe(outcome: WriteOutcome): String = when (outcome) {
        is WriteOutcome.Done -> getString(R.string.coding_result_done, outcome.plan.summary)
        is WriteOutcome.Refused -> getString(R.string.coding_result_refused, outcome.reason)
        is WriteOutcome.Failed -> getString(R.string.coding_result_failed, outcome.reason, getString(if (outcome.restored) R.string.coding_yes else R.string.coding_no))
    }

    private fun applyCoding(ready: List<CodingPlan>) {
        val vin: String? = null
        stationary({ c ->
            val writer = UdsWriter(c.session)
            val lines = ArrayList<String>()
            for (plan in ready) {
                // The old value is on disk before anything is sent.
                CodingBackupStore.save(applicationContext, plan.change.module, plan.change.did, plan.before, plan.after, vin)
                val outcome = writer.apply(plan)
                lines.add(describe(outcome))
                if (outcome !is WriteOutcome.Done) break
            }
            lines
        }) { lines ->
            plans = null
            codingLog = lines + getString(R.string.coding_after)
        }
    }

    private fun confirmRestore(b: CodingBackup) {
        AlertDialog.Builder(this)
            .setTitle(R.string.coding_restore)
            .setMessage(getString(R.string.coding_restore_message, CodingChange.hex(b.before), "${b.module} %04X".format(b.did)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.coding_restore) { _, _ ->
                stationary({ c -> describe(UdsWriter(c.session).restore(b.module, b.did, b.before)) }) { line ->
                    plans = null
                    codingLog = listOf(line, getString(R.string.coding_after))
                }
            }
            .show()
    }
}
