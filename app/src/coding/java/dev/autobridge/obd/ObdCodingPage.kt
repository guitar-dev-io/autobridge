package dev.autobridge.obd

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R
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
import dev.autobridge.obd.uds.EcuInfo
import dev.autobridge.obd.uds.UdsAnswer
import dev.autobridge.obd.uds.UdsInspector
import dev.autobridge.obd.uds.UdsScanner
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.CarToolViews

/**
 * The hidden coding page of [ObdToolsActivity] (see [CodingGate]): the catalog, the block editor
 * and the backups. Only the sideloaded flavors compile this file; the Play build gets the stub in
 * src/safe, so it carries no path that writes to a module.
 */
internal class ObdCodingPage private constructor(private val host: ObdToolsHost) {
    companion object {
        fun create(host: ObdToolsHost): ObdCodingPage? = ObdCodingPage(host)
    }

    private enum class CodingTab { CATALOG, EDITOR, BACKUPS }

    private val ctx get() = host.activity
    private val accent get() = host.accent
    private val thai get() = host.thai
    private val busyModule get() = host.busyModule
    private val codingBusy get() = host.codingBusy
    private val modules get() = host.modules
    private fun render() = host.render()
    private fun toast(text: String) = host.toast(text)
    private fun tabs(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) = host.tabs(items, onPick)
    private fun chips(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) = host.chips(items, onPick)
    private fun <T> onModule(ecu: EcuInfo, work: (UdsInspector) -> T, done: (T) -> Unit) = host.onModule(ecu, work, done)
    private fun <T> stationary(work: (UdsSession) -> T, done: (T) -> Unit) = host.stationary(work, done)

    private var codingTab = CodingTab.CATALOG
    private var moduleFilter: String? = null
    private var expanded: String? = null
    private var draft: BlockDraft? = null
    private var showRecipe = false
    private var editModule = ""
    private var editDid = ""
    private var recipeText = ""
    private var plans: List<PlanResult>? = null
    private var codingLog: List<String> = emptyList()
    private val catalog: List<CatalogEntry> by lazy {
        runCatching { ctx.assets.open("obd/ranger6g_2024_catalog.csv").bufferedReader().use { CodingCatalog.parse(it.readText()) } }.getOrDefault(emptyList())
    }
    private val catalogReads = HashMap<String, UdsAnswer>()

    // ---- Coding (hidden; see CodingGate) ------------------------------------------------

    fun build(body: LinearLayout) {
        body.stack(CarToolViews.footnote(ctx, ctx.getString(R.string.coding_banner)), gap = 4)
        body.stack(tabs(listOf(
            ctx.getString(R.string.coding_tab_catalog) to (codingTab == CodingTab.CATALOG),
            ctx.getString(R.string.coding_tab_editor) + (if (draft?.changed?.isNotEmpty() == true) " •" else "") to (codingTab == CodingTab.EDITOR),
            ctx.getString(R.string.coding_tab_backups) to (codingTab == CodingTab.BACKUPS)
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
        body.stack(chips(listOf(ctx.getString(R.string.catalog_filter_all) to (moduleFilter == null)) +
            groups.map { (ecu, name) -> "$name $ecu" to (moduleFilter == ecu) }) { i ->
            moduleFilter = if (i == 0) null else groups[i - 1].first
            render()
        }, gap = 4)
        body.stack(CarToolViews.footnote(ctx, ctx.getString(R.string.catalog_hint)), gap = 4)
        val shown = catalog.filter { moduleFilter == null || it.ecu == moduleFilter }
        body.stack(AutoBridgeDesign.pill(ctx, ctx.getString(if (codingBusy) R.string.obdt_working else R.string.catalog_check_all), primary = true, accent = accent) {
            if (!codingBusy && busyModule == null) checkAll(shown)
        }, gap = 8)
        val answers = shown.mapNotNull { catalogReads[it.id] }
        if (answers.isNotEmpty()) {
            val present = answers.count { it is UdsAnswer.Positive }
            val absent = answers.count { absent(it) }
            body.stack(CarToolViews.text(ctx, ctx.getString(R.string.catalog_check_summary, present, absent, answers.size - present - absent, shown.size - answers.size), 13f, top = 2), gap = 4)
        }
        for (e in shown) {
            val open = expanded == e.id
            val read = catalogReads[e.id]
            body.stack(CarToolViews.card(ctx) {
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
                    addView(CarToolViews.text(context, when {
                        read is UdsAnswer.Positive -> "✓"
                        read != null && absent(read) -> "✕"
                        read != null -> "?"
                        open -> "▴"
                        else -> "▾"
                    }, 15f, when {
                        read is UdsAnswer.Positive -> AutoBridgeDesign.ACCENT_ONLINE
                        else -> AutoBridgeDesign.TEXT_MUTED
                    }))
                })
                if (!open) return@card
                addView(CarToolViews.text(context, listOf(e.guiParameter, e.settingNotes).filter { it.isNotBlank() }.joinToString("\n"), 13f, top = 8))
                addView(CarToolViews.text(context, listOf(e.requiresEquipmentText(), e.engineeringNotes, "${e.communityTab} · ${e.thaiValidation}").filter { it.isNotBlank() }.joinToString("\n"), 12f, AutoBridgeDesign.TEXT_MUTED, top = 4))
                read?.let { a ->
                    addView(CarToolViews.text(context, when (a) {
                        is UdsAnswer.Positive -> catalogValue(e, a.data)
                        is UdsAnswer.Negative -> ctx.getString(R.string.catalog_refused, a.text)
                        UdsAnswer.Silent -> ctx.getString(R.string.catalog_silent)
                    }, 14f, top = 8).apply { typeface = android.graphics.Typeface.MONOSPACE })
                }
                val blocked = CodingGuard.check(e.ecu, e.did)
                val ecu = ecuFor(e.ecu)
                addView(CarToolViews.pillRow(context, listOfNotNull(
                    AutoBridgeDesign.pill(context, ctx.getString(if (busyModule == ecu.requestId) R.string.obdt_working else R.string.catalog_read), primary = read !is UdsAnswer.Positive, accent = accent) {
                        if (busyModule == null) onModule(ecu, { it.readDid(e.did) }) { catalogReads[e.id] = it }
                    },
                    (read as? UdsAnswer.Positive)?.takeIf { blocked == null }?.let { r ->
                        AutoBridgeDesign.pill(context, ctx.getString(R.string.catalog_edit), primary = true, accent = accent) {
                            openDraft(BlockDraft(e.ecu, e.did, r.data, CodingCatalog.marked(e, r.data).second, e.id))
                        }
                    }
                )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
                if (blocked != null) addView(CarToolViews.text(context, ctx.getString(R.string.catalog_blocked), 12f, AutoBridgeDesign.DANGER, top = 6))
            }, gap = 8)
        }
    }

    /** The module answered that it has no such block: the car does not carry this setting. */
    private fun absent(a: UdsAnswer) = a is UdsAnswer.Negative && a.code == 0x31

    /**
     * Reads every block behind [entries], one module at a time, and marks each row: the car has the
     * block, the module says it has none, or no usable answer. Reading only; nothing is written.
     * A module that does not answer its first read is not asked for the rest, which would only be
     * one timeout after another.
     */
    private fun checkAll(entries: List<CatalogEntry>) {
        if (entries.isEmpty()) return
        host.reading({ s ->
            val out = HashMap<String, UdsAnswer>()
            entries.groupBy { it.ecu.uppercase() }.forEach { (module, list) ->
                val ecu = ecuFor(module)
                try {
                    if (!s.addressEcu(ecu.requestId, ecu.replyId)) {
                        list.forEach { out[it.id] = UdsAnswer.Silent }
                        return@forEach
                    }
                    val inspector = UdsInspector(s)
                    val byDid = HashMap<Int, UdsAnswer>()
                    for (e in list) {
                        val answer = byDid.getOrPut(e.did) {
                            if (byDid.isNotEmpty() && byDid.values.all { it === UdsAnswer.Silent }) UdsAnswer.Silent
                            else inspector.readDid(e.did)
                        }
                        out[e.id] = answer
                    }
                } finally {
                    s.resetAddressing()
                }
            }
            out
        }) { result -> catalogReads.putAll(result) }
    }

    private fun CatalogEntry.requiresEquipmentText(): String = if (equipment.isBlank()) "" else ctx.getString(R.string.catalog_needs, equipment)

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
        if (marks.isEmpty() && e.markedNibbles.isNotEmpty()) out.append("\n").append(ctx.getString(R.string.catalog_layout_unknown))
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
            body.stack(CarToolViews.footnote(ctx, ctx.getString(R.string.editor_empty)), gap = 4)
            body.stack(CarToolViews.card(ctx) {
                val module = hexField(ctx.getString(R.string.editor_module_hint), editModule, 3) { editModule = it }
                val did = hexField(ctx.getString(R.string.editor_did_hint), editDid, 4) { editDid = it }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(module, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) })
                    addView(did, LinearLayout.LayoutParams(0, -2, 1f))
                })
                addView(AutoBridgeDesign.pill(context, ctx.getString(if (busyModule != null) R.string.obdt_working else R.string.editor_read), primary = true, accent = accent) { readForEditor() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
            }, gap = 8)
        } else {
            buildDraft(body, d)
        }
        buildRecipeBox(body)
        body.stack(CarToolViews.footnote(ctx, ctx.getString(R.string.coding_warning)), gap = 12)
    }

    private fun hexField(hint: String, value: String, length: Int, onChange: (String) -> Unit) = EditText(ctx).apply {
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
        if (module == null || did == null) { toast(ctx.getString(R.string.editor_bad_address)); return }
        CodingGuard.check(module, did)?.let { toast(it); return }
        onModule(ecuFor(module), { it.readDid(did) }) { a ->
            when (a) {
                is UdsAnswer.Positive -> openDraft(BlockDraft(module, did, a.data))
                is UdsAnswer.Negative -> toast(ctx.getString(R.string.catalog_refused, a.text))
                UdsAnswer.Silent -> toast(ctx.getString(R.string.catalog_silent))
            }
        }
    }

    private fun buildDraft(body: LinearLayout, d: BlockDraft) {
        val entry = d.sourceId?.let { id -> catalog.firstOrNull { it.id == id } }
        body.stack(CarToolViews.card(ctx) {
            addView(CarToolViews.text(context, "${d.module} · %04X".format(d.did), 17f, bold = true).apply { typeface = android.graphics.Typeface.MONOSPACE })
            entry?.let {
                addView(CarToolViews.text(context, if (thai) it.featureTh else it.featureEn, 14f, top = 2))
                if (it.settingNotes.isNotBlank()) addView(CarToolViews.text(context, it.settingNotes, 13f, accent, top = 4))
            }
            addView(CarToolViews.text(context, ctx.getString(R.string.editor_hint_tap), 12f, AutoBridgeDesign.TEXT_MUTED, top = 8))
            addView(nibbleGrid(d), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
            if (d.locked.isNotEmpty()) addView(CarToolViews.text(context, ctx.getString(R.string.editor_locked), 12f, AutoBridgeDesign.TEXT_MUTED, top = 6))
            addView(CarToolViews.text(context, compare(ctx.getString(R.string.editor_before), d.original, emptySet()), 14f, top = 10).apply { typeface = android.graphics.Typeface.MONOSPACE })
            addView(CarToolViews.text(context, compare(ctx.getString(R.string.editor_after), d.value, d.changed), 14f, top = 2).apply { typeface = android.graphics.Typeface.MONOSPACE })
            if (d.changed.isNotEmpty()) addView(CarToolViews.text(context, d.recipeLine(), 12f, AutoBridgeDesign.TEXT_MUTED, top = 6).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        }, gap = 8)

        val ready = plans?.singleOrNull() as? PlanResult.Ready
        body.stack(CarToolViews.pillRow(ctx, listOfNotNull(
            AutoBridgeDesign.pill(ctx, ctx.getString(R.string.editor_close), accent = accent) { draft = null; plans = null; render() },
            if (d.changed.isNotEmpty()) AutoBridgeDesign.pill(ctx, ctx.getString(R.string.editor_reset), accent = accent) { d.reset(); plans = null; render() } else null,
            if (ready == null) AutoBridgeDesign.pill(ctx, ctx.getString(if (codingBusy) R.string.obdt_working else R.string.editor_check), primary = d.changed.isNotEmpty(), accent = accent) {
                if (!codingBusy) checkDraft(d)
            } else AutoBridgeDesign.pill(ctx, ctx.getString(R.string.coding_apply), primary = true, accent = accent) {
                if (!codingBusy) confirmApply(listOf(ready.plan))
            }
        )), gap = 8)
        planLines(body)
    }

    private fun planLines(body: LinearLayout) {
        plans?.forEach { r ->
            body.stack(CarToolViews.text(ctx, when (r) {
                is PlanResult.Ready -> ctx.getString(R.string.coding_plan_ok, r.plan.summary)
                is PlanResult.Blocked -> ctx.getString(R.string.coding_plan_blocked, r.reason)
            }, 13f, if (r is PlanResult.Blocked) AutoBridgeDesign.DANGER else AutoBridgeDesign.TEXT, top = 2), gap = 2)
        }
        codingLog.forEach { body.stack(CarToolViews.text(ctx, it, 13f, top = 2), gap = 2) }
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
    private fun nibbleGrid(d: BlockDraft) = LinearLayout(ctx).apply {
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
                    addView(CarToolViews.text(context, ctx.getString(R.string.editor_byte, b), 10f, AutoBridgeDesign.TEXT_MUTED).apply { gravity = Gravity.CENTER },
                        LinearLayout.LayoutParams(0, -2, 1f))
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
        }
    }

    private fun pickNibble(d: BlockDraft, index: Int) {
        val now = d.nibble(index)
        val labels = Array(16) { v ->
            "%X    %s%s".format(v, BlockDraft.binary(v), if (v == d.nibble(index, d.original)) "   " + ctx.getString(R.string.editor_original_mark) else "")
        }
        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.editor_pick_title, index + 1, index / 2, "%X".format(now), BlockDraft.binary(now)))
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
        val change = d.toChange() ?: run { toast(ctx.getString(R.string.editor_no_change)); return }
        codingLog = emptyList()
        stationary({ c -> UdsWriter(c).plan(change) }) { plans = listOf(it) }
    }

    /** The original text recipe, kept for several blocks at once or pasted recipes. */
    private fun buildRecipeBox(body: LinearLayout) {
        body.stack(AutoBridgeDesign.pill(ctx, ctx.getString(R.string.editor_recipe_toggle) + if (showRecipe) "  ▴" else "  ▾", accent = accent) {
            showRecipe = !showRecipe; render()
        }, gap = 12)
        if (!showRecipe) return
        body.stack(CarToolViews.footnote(ctx, ctx.getString(R.string.coding_format)), gap = 6)
        body.stack(EditText(ctx).apply {
            hint = ctx.getString(R.string.coding_recipe_hint)
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
        body.stack(CarToolViews.pillRow(ctx, listOfNotNull(
            draft?.takeIf { it.changed.isNotEmpty() }?.let { d -> AutoBridgeDesign.pill(ctx, ctx.getString(R.string.editor_to_recipe), accent = accent) {
                recipeText = (recipeText.trimEnd().takeIf { it.isNotEmpty() }?.plus("\n") ?: "") + d.recipeLine()
                render()
            } },
            AutoBridgeDesign.pill(ctx, ctx.getString(if (codingBusy) R.string.obdt_working else R.string.coding_preview), primary = ready == null, accent = accent) { if (!codingBusy) { draft = null; previewCoding() } },
            ready?.let { AutoBridgeDesign.pill(ctx, ctx.getString(R.string.coding_apply), primary = true, accent = accent) { if (!codingBusy) confirmApply(it.map { r -> (r as PlanResult.Ready).plan }) } }
        )), gap = 8)
        if (draft == null) planLines(body)
    }

    private fun buildBackups(body: LinearLayout) {
        body.stack(CarToolViews.footnote(ctx, ctx.getString(R.string.coding_backups_hint)), gap = 4)
        planLines(body)
        val saved = CodingBackupStore.list(ctx)
        if (saved.isEmpty()) body.stack(CarToolViews.footnote(ctx, ctx.getString(R.string.coding_no_backups)), gap = 4)
        saved.take(30).forEach { b ->
            val value = CodingChange.hex(b.before)
            body.stack(AutoBridgeDesign.contentRow(
                context = ctx, title = "${b.module} %04X".format(b.did), subtitle = "$value · ${java.text.DateFormat.getDateTimeInstance().format(b.timeMs)}",
                accent = accent, badgeText = b.module.take(3), actionLabel = ctx.getString(R.string.coding_restore), onTrailing = { confirmRestore(b) }, onClick = {}
            ), gap = 8)
        }
    }

    private fun previewCoding() {
        codingLog = emptyList()
        when (val parsed = CodingRecipe.parse(recipeText)) {
            is CodingRecipe.Parsed.Error -> { plans = null; toast(ctx.getString(R.string.coding_parse_error, parsed.line, parsed.message)) }
            is CodingRecipe.Parsed.Ok -> stationary({ c -> val w = UdsWriter(c); parsed.recipe.changes.map { w.plan(it) } }) { plans = it }
        }
    }

    private fun confirmApply(ready: List<CodingPlan>) {
        val input = EditText(ctx).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; setSingleLine() }
        val highImpact = ready.any { it.change.module.uppercase() in CodingGuard.highImpactModules }
        val message = ctx.getString(R.string.coding_confirm_message, ready.size) +
            (if (highImpact) "\n\n" + ctx.getString(R.string.coding_confirm_high_impact) else "") +
            "\n\n" + ready.joinToString("\n") { it.summary }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.coding_confirm_title)
            .setMessage(message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.coding_apply) { _, _ ->
                if (input.text.toString().trim().equals(ctx.getString(R.string.coding_confirm_word), ignoreCase = true)) applyCoding(ready)
            }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = s.toString().trim().equals(ctx.getString(R.string.coding_confirm_word), ignoreCase = true)
            }
        })
    }

    private fun describe(outcome: WriteOutcome): String = when (outcome) {
        is WriteOutcome.Done -> ctx.getString(R.string.coding_result_done, outcome.plan.summary)
        is WriteOutcome.Refused -> ctx.getString(R.string.coding_result_refused, outcome.reason)
        is WriteOutcome.Failed -> ctx.getString(R.string.coding_result_failed, outcome.reason, ctx.getString(if (outcome.restored) R.string.coding_yes else R.string.coding_no))
    }

    /**
     * The car's VIN from the engine module (22 F190), or null. It ties a backup to the car it came
     * from, so Restore cannot put one car's value into another.
     */
    private fun readVin(c: UdsSession): String? = try {
        if (!c.addressEcu("7E0", "7E8")) null
        else (UdsInspector(c).readDid(0xF190) as? UdsAnswer.Positive)?.data
            ?.let { UdsScanner.render(it) }?.takeIf { it.length == 17 }
    } finally {
        c.resetAddressing()
    }

    private fun applyCoding(ready: List<CodingPlan>) {
        stationary({ c ->
            val vin = readVin(c)
            val writer = UdsWriter(c)
            val lines = ArrayList<String>()
            val written = ArrayList<CodingPlan>()
            for (plan in ready) {
                // The old value is on disk before anything is sent.
                val saved = CodingBackupStore.save(ctx.applicationContext, plan.change.module, plan.change.did, plan.before, plan.after, vin)
                val outcome = writer.apply(plan)
                lines.add(describe(outcome))
                if (outcome is WriteOutcome.Done) { written.add(plan); continue }
                // Nothing reached the module: the backup has nothing to restore.
                if (outcome is WriteOutcome.Refused) saved.file.delete()
                // A recipe is all or nothing: put back the blocks already written, newest first.
                for (done in written.asReversed()) {
                    lines.add(ctx.getString(R.string.coding_undo, describe(writer.restore(done.change.module, done.change.did, done.before))))
                }
                break
            }
            lines
        }) { lines ->
            plans = null
            draft = null
            codingLog = lines + ctx.getString(R.string.coding_after)
        }
    }

    private fun confirmRestore(b: CodingBackup) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.coding_restore)
            .setMessage(ctx.getString(R.string.coding_restore_message, CodingChange.hex(b.before), "${b.module} %04X".format(b.did)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.coding_restore) { _, _ ->
                stationary({ c ->
                    val vin = readVin(c)
                    when {
                        // A backup that names its car is only put back into that car.
                        b.vin.isNotEmpty() && vin == null -> ctx.getString(R.string.coding_vin_unknown)
                        b.vin.isNotEmpty() && !b.vin.equals(vin, ignoreCase = true) -> ctx.getString(R.string.coding_vin_mismatch, b.vin, vin)
                        else -> describe(UdsWriter(c).restore(b.module, b.did, b.before))
                    }
                }) { line ->
                    plans = null
                    codingLog = listOf(line, ctx.getString(R.string.coding_after))
                }
            }
            .show()
    }
}
