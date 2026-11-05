package dev.autobridge.obd

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.widget.EditText
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.obd.coding.BlockDraft
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

    private enum class Page { MODULES, CODING }
    private enum class CodingTab { CATALOG, EDITOR, BACKUPS }

    /** What a module action found out. */
    private sealed class Outcome<out T> {
        object NotLive : Outcome<Nothing>()
        object Moving : Outcome<Nothing>()
        class Done<T>(val value: T) : Outcome<T>()
        class Failed(val message: String) : Outcome<Nothing>()
    }

    /** The session the module code talks through. */
    private class Car(val session: UdsSession)

    private var page = Page.MODULES
    private var codingTab = CodingTab.CATALOG
    private var moduleFilter: String? = null
    private var expanded: String? = null
    private var draft: BlockDraft? = null
    private var showRecipe = false
    private var editModule = ""
    private var editDid = ""
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
        super.onBackPressed()
    }

    private fun go(target: Page) { page = target; render() }

    private fun render() {
        val live = ObdConnection.state.value.status == ObdState.Status.LIVE
        val coding = CodingGate.available(this)
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
            Page.CODING -> buildCoding(body)
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
    private fun tabs(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) =
        CarToolViews.pillRow(this, items.mapIndexed { i, (label, on) ->
            AutoBridgeDesign.pill(this, label, primary = on, accent = accent) { if (!on) onPick(i) }
        })

    /** Chips that scroll sideways when they do not fit. */
    private fun chips(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEachIndexed { i, (label, on) ->
                addView(AutoBridgeDesign.pill(context, label, primary = on, accent = accent) { onPick(i) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(8) })
            }
        })
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
        body.stack(CarToolViews.footnote(this, getString(R.string.coding_banner)), gap = 4)
        body.stack(tabs(listOf(
            getString(R.string.coding_tab_catalog) to (codingTab == CodingTab.CATALOG),
            getString(R.string.coding_tab_editor) + (if (draft?.changed?.isNotEmpty() == true) " •" else "") to (codingTab == CodingTab.EDITOR),
            getString(R.string.coding_tab_backups) to (codingTab == CodingTab.BACKUPS)
        )) { i -> codingTab = CodingTab.values()[i]; render() }, gap = 8)
        when (codingTab) {
            CodingTab.CATALOG -> buildCatalog(body)
            CodingTab.EDITOR -> buildEditor(body)
            CodingTab.BACKUPS -> buildBackups(body)
        }
    }

    private fun ecuFor(module: String): EcuInfo =
        modules?.firstOrNull { it.requestId.equals(module, ignoreCase = true) }
            ?: EcuInfo(requestId = module.uppercase(), replyId = "%X".format(module.toInt(16) + 8), values = emptyMap())

    private fun riskColor(risk: String): Int = when (risk.lowercase()) {
        "low" -> AutoBridgeDesign.ACCENT_ONLINE
        "high", "critical" -> AutoBridgeDesign.DANGER
        else -> AutoBridgeDesign.TEXT_MUTED
    }

    /** Where the community says each setting lives, and what the car holds there now. Reading only. */
    private fun buildCatalog(body: LinearLayout) {
        if (catalog.isEmpty()) return
        val groups = catalog.map { it.ecu to it.moduleName }.distinct()
        body.stack(chips(listOf(getString(R.string.catalog_filter_all) to (moduleFilter == null)) +
            groups.map { (ecu, name) -> "$name $ecu" to (moduleFilter == ecu) }) { i ->
            moduleFilter = if (i == 0) null else groups[i - 1].first
            render()
        }, gap = 4)
        body.stack(CarToolViews.footnote(this, getString(R.string.catalog_hint)), gap = 4)
        for (e in catalog.filter { moduleFilter == null || it.ecu == moduleFilter }) {
            val open = expanded == e.id
            val read = catalogReads[e.id]
            body.stack(CarToolViews.card(this) {
                isClickable = true
                setOnClickListener { expanded = if (open) null else e.id; render() }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(CarToolViews.text(context, if (thai) e.featureTh else e.featureEn, 15f, bold = true))
                        addView(CarToolViews.text(context, android.text.SpannableStringBuilder("${e.address} · ").apply {
                            val start = length
                            append(e.risk)
                            setSpan(android.text.style.ForegroundColorSpan(riskColor(e.risk)), start, length, 0)
                        }, 12f, AutoBridgeDesign.TEXT_MUTED, top = 2).apply { typeface = android.graphics.Typeface.MONOSPACE })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(CarToolViews.text(context, if (read is UdsAnswer.Positive) "✓" else if (open) "▴" else "▾", 15f, AutoBridgeDesign.TEXT_MUTED))
                })
                if (!open) return@card
                addView(CarToolViews.text(context, listOf(e.guiParameter, e.settingNotes).filter { it.isNotBlank() }.joinToString("\n"), 13f, top = 8))
                addView(CarToolViews.text(context, listOf(e.requiresEquipmentText(), e.engineeringNotes, "${e.communityTab} · ${e.thaiValidation}").filter { it.isNotBlank() }.joinToString("\n"), 12f, AutoBridgeDesign.TEXT_MUTED, top = 4))
                read?.let { a ->
                    addView(CarToolViews.text(context, when (a) {
                        is UdsAnswer.Positive -> catalogValue(e, a.data)
                        is UdsAnswer.Negative -> getString(R.string.catalog_refused, a.text)
                        UdsAnswer.Silent -> getString(R.string.catalog_silent)
                    }, 14f, top = 8).apply { typeface = android.graphics.Typeface.MONOSPACE })
                }
                val blocked = CodingGuard.check(e.ecu, e.did)
                val ecu = ecuFor(e.ecu)
                addView(CarToolViews.pillRow(context, listOfNotNull(
                    AutoBridgeDesign.pill(context, getString(if (busyModule == ecu.requestId) R.string.obdt_working else R.string.catalog_read), primary = read !is UdsAnswer.Positive, accent = accent) {
                        if (busyModule == null) onModule(ecu, { it.readDid(e.did) }) { catalogReads[e.id] = it }
                    },
                    (read as? UdsAnswer.Positive)?.takeIf { blocked == null }?.let { r ->
                        AutoBridgeDesign.pill(context, getString(R.string.catalog_edit), primary = true, accent = accent) {
                            openDraft(BlockDraft(e.ecu, e.did, r.data, CodingCatalog.marked(e, r.data).second, e.id))
                        }
                    }
                )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
                if (blocked != null) addView(CarToolViews.text(context, getString(R.string.catalog_blocked), 12f, AutoBridgeDesign.DANGER, top = 6))
            }, gap = 8)
        }
    }

    private fun CatalogEntry.requiresEquipmentText(): String = if (equipment.isBlank()) "" else getString(R.string.catalog_needs, equipment)

    /** DID bytes in groups of four digits; digits the catalog pattern marks are highlighted. */
    private fun catalogValue(e: CatalogEntry, data: ByteArray): CharSequence {
        val (hex, marks) = CodingCatalog.marked(e, data)
        val out = android.text.SpannableStringBuilder("%04X  ".format(e.did))
        hex.forEachIndexed { i, ch ->
            val start = out.length
            out.append(ch)
            if (i in marks) {
                out.setSpan(android.text.style.BackgroundColorSpan(accent), start, out.length, 0)
                out.setSpan(android.text.style.ForegroundColorSpan(AutoBridgeDesign.ON_ACCENT), start, out.length, 0)
            }
            if (i % 4 == 3) out.append(' ')
        }
        if (marks.isEmpty() && e.markedNibbles.isNotEmpty()) out.append("\n").append(getString(R.string.catalog_layout_unknown))
        return out
    }

    private fun openDraft(d: BlockDraft) {
        draft = d
        plans = null
        codingLog = emptyList()
        codingTab = CodingTab.EDITOR
        render()
    }

    // ---- Editor ---------------------------------------------------------------------------

    private fun buildEditor(body: LinearLayout) {
        val d = draft
        if (d == null) {
            body.stack(CarToolViews.footnote(this, getString(R.string.editor_empty)), gap = 4)
            body.stack(CarToolViews.card(this) {
                val module = hexField(getString(R.string.editor_module_hint), editModule, 3) { editModule = it }
                val did = hexField(getString(R.string.editor_did_hint), editDid, 4) { editDid = it }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(module, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) })
                    addView(did, LinearLayout.LayoutParams(0, -2, 1f))
                })
                addView(AutoBridgeDesign.pill(context, getString(if (busyModule != null) R.string.obdt_working else R.string.editor_read), primary = true, accent = accent) { readForEditor() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
            }, gap = 8)
        } else {
            buildDraft(body, d)
        }
        buildRecipeBox(body)
        body.stack(CarToolViews.footnote(this, getString(R.string.coding_warning)), gap = 12)
    }

    private fun hexField(hint: String, value: String, length: Int, onChange: (String) -> Unit) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = 16f
        setTextColor(AutoBridgeDesign.TEXT)
        setHintTextColor(AutoBridgeDesign.TEXT_MUTED)
        filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(length))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        background = AutoBridgeDesign.surface(context, AutoBridgeDesign.INK, 12)
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString().orEmpty().uppercase()) }
        })
    }

    private fun readForEditor() {
        if (busyModule != null) return
        val module = editModule.takeIf { Regex("[0-9A-F]{3}").matches(it) }
        val did = editDid.takeIf { Regex("[0-9A-F]{4}").matches(it) }?.toInt(16)
        if (module == null || did == null) { toast(getString(R.string.editor_bad_address)); return }
        CodingGuard.check(module, did)?.let { toast(it); return }
        onModule(ecuFor(module), { it.readDid(did) }) { a ->
            when (a) {
                is UdsAnswer.Positive -> openDraft(BlockDraft(module, did, a.data))
                is UdsAnswer.Negative -> toast(getString(R.string.catalog_refused, a.text))
                UdsAnswer.Silent -> toast(getString(R.string.catalog_silent))
            }
        }
    }

    private fun buildDraft(body: LinearLayout, d: BlockDraft) {
        val entry = d.sourceId?.let { id -> catalog.firstOrNull { it.id == id } }
        body.stack(CarToolViews.card(this) {
            addView(CarToolViews.text(context, "${d.module} · %04X".format(d.did), 17f, bold = true).apply { typeface = android.graphics.Typeface.MONOSPACE })
            entry?.let {
                addView(CarToolViews.text(context, if (thai) it.featureTh else it.featureEn, 14f, top = 2))
                if (it.settingNotes.isNotBlank()) addView(CarToolViews.text(context, it.settingNotes, 13f, accent, top = 4))
            }
            addView(CarToolViews.text(context, getString(R.string.editor_hint_tap), 12f, AutoBridgeDesign.TEXT_MUTED, top = 8))
            addView(nibbleGrid(d), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
            if (d.locked.isNotEmpty()) addView(CarToolViews.text(context, getString(R.string.editor_locked), 12f, AutoBridgeDesign.TEXT_MUTED, top = 6))
            addView(CarToolViews.text(context, compare(getString(R.string.editor_before), d.original, emptySet()), 14f, top = 10).apply { typeface = android.graphics.Typeface.MONOSPACE })
            addView(CarToolViews.text(context, compare(getString(R.string.editor_after), d.value, d.changed), 14f, top = 2).apply { typeface = android.graphics.Typeface.MONOSPACE })
            if (d.changed.isNotEmpty()) addView(CarToolViews.text(context, d.recipeLine(), 12f, AutoBridgeDesign.TEXT_MUTED, top = 6).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        }, gap = 8)

        val ready = plans?.singleOrNull() as? PlanResult.Ready
        body.stack(CarToolViews.pillRow(this, listOfNotNull(
            AutoBridgeDesign.pill(this, getString(R.string.editor_close), accent = accent) { draft = null; plans = null; render() },
            if (d.changed.isNotEmpty()) AutoBridgeDesign.pill(this, getString(R.string.editor_reset), accent = accent) { d.reset(); plans = null; render() } else null,
            if (ready == null) AutoBridgeDesign.pill(this, getString(if (codingBusy) R.string.obdt_working else R.string.editor_check), primary = d.changed.isNotEmpty(), accent = accent) {
                if (!codingBusy) checkDraft(d)
            } else AutoBridgeDesign.pill(this, getString(R.string.coding_apply), primary = true, accent = accent) {
                if (!codingBusy) confirmApply(listOf(ready.plan))
            }
        )), gap = 8)
        planLines(body)
    }

    private fun planLines(body: LinearLayout) {
        plans?.forEach { r ->
            body.stack(CarToolViews.text(this, when (r) {
                is PlanResult.Ready -> getString(R.string.coding_plan_ok, r.plan.summary)
                is PlanResult.Blocked -> getString(R.string.coding_plan_blocked, r.reason)
            }, 13f, if (r is PlanResult.Blocked) AutoBridgeDesign.DANGER else AutoBridgeDesign.TEXT, top = 2), gap = 2)
        }
        codingLog.forEach { body.stack(CarToolViews.text(this, it, 13f, top = 2), gap = 2) }
    }

    /** Old or new value, four digits a group, changed digits filled. */
    private fun compare(label: String, value: ByteArray, changed: Set<Int>): CharSequence {
        val out = android.text.SpannableStringBuilder(label.padEnd(6) + " ")
        CodingChange.hex(value).forEachIndexed { i, ch ->
            val start = out.length
            out.append(ch)
            if (i in changed) {
                out.setSpan(android.text.style.BackgroundColorSpan(accent), start, out.length, 0)
                out.setSpan(android.text.style.ForegroundColorSpan(AutoBridgeDesign.ON_ACCENT), start, out.length, 0)
            }
            if (i % 4 == 3) out.append(' ')
        }
        return out
    }

    /** Each hex digit is a cell; four bytes a row. Tap a cell to pick its new value. */
    private fun nibbleGrid(d: BlockDraft) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val changed = d.changed
        for (rowStart in 0 until d.digits step 8) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (i in rowStart until minOf(rowStart + 8, d.digits)) {
                    val locked = i in d.locked
                    val isChanged = i in changed
                    val cell = TextView(context).apply {
                        text = "%X".format(d.nibble(i))
                        textSize = 18f
                        gravity = Gravity.CENTER
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextColor(when {
                            isChanged -> AutoBridgeDesign.ON_ACCENT
                            locked -> AutoBridgeDesign.TEXT_MUTED
                            else -> AutoBridgeDesign.TEXT
                        })
                        background = AutoBridgeDesign.surface(context,
                            if (isChanged) accent else if (locked) AutoBridgeDesign.INK else AutoBridgeDesign.SURFACE_RAISED, 8,
                            if (i in d.focus) accent else AutoBridgeDesign.HAIRLINE)
                        if (!locked) setOnClickListener { pickNibble(d, i) }
                    }
                    addView(cell, LinearLayout.LayoutParams(0, context.dp(44), 1f).apply {
                        // A wider gap between bytes than between the two digits of one byte.
                        marginEnd = context.dp(if (i % 2 == 1) 8 else 3)
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(6) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (b in rowStart / 2 until minOf(rowStart / 2 + 4, d.digits / 2)) {
                    addView(CarToolViews.text(context, getString(R.string.editor_byte, b), 10f, AutoBridgeDesign.TEXT_MUTED).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(0, -2, 1f))
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
        }
    }

    private fun pickNibble(d: BlockDraft, index: Int) {
        val now = d.nibble(index)
        val labels = Array(16) { v ->
            "%X    %s%s".format(v, BlockDraft.binary(v), if (v == d.nibble(index, d.original)) "   " + getString(R.string.editor_original_mark) else "")
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.editor_pick_title, index + 1, index / 2, "%X".format(now), BlockDraft.binary(now)))
            .setSingleChoiceItems(labels, now) { dialog, which ->
                d.set(index, which)
                plans = null
                dialog.dismiss()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkDraft(d: BlockDraft) {
        val change = d.toChange() ?: run { toast(getString(R.string.editor_no_change)); return }
        codingLog = emptyList()
        stationary({ c -> UdsWriter(c.session).plan(change) }) { plans = listOf(it) }
    }

    /** The original text recipe, kept for several blocks at once or pasted recipes. */
    private fun buildRecipeBox(body: LinearLayout) {
        body.stack(AutoBridgeDesign.pill(this, getString(R.string.editor_recipe_toggle) + if (showRecipe) "  ▴" else "  ▾", accent = accent) {
            showRecipe = !showRecipe; render()
        }, gap = 12)
        if (!showRecipe) return
        body.stack(CarToolViews.footnote(this, getString(R.string.coding_format)), gap = 6)
        body.stack(EditText(this).apply {
            hint = getString(R.string.coding_recipe_hint)
            setText(recipeText)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(AutoBridgeDesign.TEXT)
            setHintTextColor(AutoBridgeDesign.TEXT_MUTED)
            minLines = 5
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            background = AutoBridgeDesign.surface(context, AutoBridgeDesign.SURFACE, 12)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) { recipeText = s?.toString().orEmpty() }
            })
        }, gap = 8)
        val ready = plans?.takeIf { p -> draft == null && p.isNotEmpty() && p.all { it is PlanResult.Ready } }
        body.stack(CarToolViews.pillRow(this, listOfNotNull(
            draft?.takeIf { it.changed.isNotEmpty() }?.let { d -> AutoBridgeDesign.pill(this, getString(R.string.editor_to_recipe), accent = accent) {
                recipeText = (recipeText.trimEnd().takeIf { it.isNotEmpty() }?.plus("\n") ?: "") + d.recipeLine()
                render()
            } },
            AutoBridgeDesign.pill(this, getString(if (codingBusy) R.string.obdt_working else R.string.coding_preview), primary = ready == null, accent = accent) { if (!codingBusy) { draft = null; previewCoding() } },
            ready?.let { AutoBridgeDesign.pill(this, getString(R.string.coding_apply), primary = true, accent = accent) { if (!codingBusy) confirmApply(it.map { r -> (r as PlanResult.Ready).plan }) } }
        )), gap = 8)
        if (draft == null) planLines(body)
    }

    private fun buildBackups(body: LinearLayout) {
        body.stack(CarToolViews.footnote(this, getString(R.string.coding_backups_hint)), gap = 4)
        planLines(body)
        val saved = CodingBackupStore.list(this)
        if (saved.isEmpty()) body.stack(CarToolViews.footnote(this, getString(R.string.coding_no_backups)), gap = 4)
        saved.take(30).forEach { b ->
            val value = CodingChange.hex(b.before)
            body.stack(AutoBridgeDesign.contentRow(
                context = this, title = "${b.module} %04X".format(b.did), subtitle = "$value · ${java.text.DateFormat.getDateTimeInstance().format(b.timeMs)}",
                accent = accent, badgeText = b.module.take(3), actionLabel = getString(R.string.coding_restore), onTrailing = { confirmRestore(b) }, onClick = {}
            ), gap = 8)
        }
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
            draft = null
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
