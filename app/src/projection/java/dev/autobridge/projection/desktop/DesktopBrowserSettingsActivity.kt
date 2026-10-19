package dev.autobridge.projection.desktop

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.browser.DesktopBrowserAdapter
import dev.autobridge.desktop.browser.DesktopBrowserId
import dev.autobridge.desktop.browser.DesktopBrowsers
import dev.autobridge.desktop.install.ComponentState
import dev.autobridge.desktop.install.EngineChoice
import dev.autobridge.desktop.install.InstallState
import dev.autobridge.desktop.install.RuntimeSource
import dev.autobridge.desktop.runtime.ChildProcessLimit
import dev.autobridge.desktop.session.DesktopSession
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Settings › Desktop Browser Engine: choose which browser the Desktop Browser entries open,
 * install / update / remove the shared Linux runtime and each browser, and see what the runtime
 * is doing. Every button runs a real engine operation; progress comes from the engine's state.
 */
class DesktopBrowserSettingsActivity : Activity() {
    private val scope = MainScope()
    private var collectJob: Job? = null
    private var structureKey: String? = null
    private val progressViews = HashMap<String, Pair<ProgressBar, TextView>>()
    private var diagnosticsText: TextView? = null
    private var storage: DesktopEngine.Storage? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DesktopEngine.init(this)
    }

    override fun onResume() {
        super.onResume()
        structureKey = null
        collectJob = scope.launch {
            combine(DesktopEngine.runtimeState, DesktopEngine.browserStates, DesktopEngine.busy, DesktopEngine.session) { r, b, busy, s ->
                Snapshot(r, b, busy, s)
            }.collect { render(it) }
        }
        refreshStorage()
        scope.launch {
            while (true) {
                delay(2000)
                diagnosticsText?.text = diagnostics(currentSnapshot ?: continue)
            }
        }
    }

    override fun onPause() {
        collectJob?.cancel()
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class Snapshot(
        val runtime: ComponentState,
        val browsers: Map<DesktopBrowserId, ComponentState>,
        val busy: Boolean,
        val session: DesktopSession?,
    )

    private var currentSnapshot: Snapshot? = null

    /** Rebuilds the page only when something structural changed; progress ticks update in place. */
    private fun render(s: Snapshot) {
        // An install, update or removal just finished: the sizes on screen are stale.
        if (currentSnapshot?.busy == true && !s.busy) refreshStorage()
        currentSnapshot = s
        val key = buildString {
            append(s.runtime.state).append(s.runtime.version).append(s.runtime.lastError).append(s.busy)
            append(DesktopEngine.choice)
            DesktopBrowsers.all.forEach { a ->
                val b = s.browsers[a.id]
                append(a.id).append(b?.state).append(b?.version).append(b?.candidateVersion).append(b?.lastError)
            }
            append(s.session?.adapter?.id)
            append(storage?.runtimeTotal)
        }
        if (key != structureKey) {
            structureKey = key
            build(s)
        } else {
            updateProgress("runtime", s.runtime)
            DesktopBrowsers.all.forEach { a -> s.browsers[a.id]?.let { updateProgress(a.id.key, it) } }
        }
    }

    private var page: View? = null

    private fun build(s: Snapshot) {
        // Rebuilding replaces the page; keep the reader where they were (a stepper tap rebuilt it
        // and jumped to the top, so pressing + twice needed a scroll in between).
        val keepScroll = page?.let { AutoBridgeDesign.pageScroll(it)?.scrollY } ?: 0
        progressViews.clear()
        val body = AutoBridgeDesign.body(this)
        if (!DesktopEngine.supported) {
            body.stack(notice(getString(R.string.desktop_settings_unsupported), AutoBridgeDesign.SIGNAL_SLOW))
        }

        childProcessNotice()?.let { body.stack(it, gap = 10) }

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.desktop_settings_choice_section)), gap = 2)
        body.stack(choiceRow(EngineChoice.AUTOBRIDGE, getString(R.string.desktop_settings_choice_autobridge), getString(R.string.desktop_settings_choice_autobridge_caption), true))
        DesktopBrowsers.all.forEach { a ->
            val installed = (s.browsers[a.id]?.state ?: InstallState.NOT_INSTALLED).installed
            val choice = if (a.id == DesktopBrowserId.CHROME) EngineChoice.CHROME else EngineChoice.BRAVE
            body.stack(choiceRow(choice, getString(R.string.desktop_settings_browser_desktop, a.displayName),
                if (installed) getString(R.string.desktop_settings_choice_installed) else getString(R.string.desktop_settings_choice_not_installed), installed))
        }

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.desktop_prefs_section)), gap = 2)
        body.stack(preferencesCard())

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.desktop_settings_runtime_section)), gap = 2)
        body.stack(runtimeCard(s))

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.desktop_settings_browsers_section)), gap = 2)
        DesktopBrowsers.all.forEach { a -> body.stack(browserCard(a, s), gap = 10) }

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.desktop_settings_diagnostics_section)), gap = 2)
        body.stack(diagnosticsCard(s))

        val built = AutoBridgeDesign.page(
            context = this,
            header = AutoBridgeDesign.header(
                context = this,
                title = getString(dev.autobridge.desktop.R.string.desktop_engine_title),
                subtitle = getString(dev.autobridge.desktop.R.string.desktop_engine_subtitle),
                onBack = { finish() }
            ),
            body = body
        )
        page = built
        setContentView(built)
        AutoBridgeDesign.pageScroll(built)?.let { scroll -> scroll.post { scroll.scrollTo(0, keepScroll) } }
    }

    // ── Cards ───────────────────────────────────────────────────────────────

    private fun choiceRow(choice: EngineChoice, title: String, caption: String, enabled: Boolean): View {
        val selected = DesktopEngine.choice == choice
        return AutoBridgeDesign.contentRow(
            context = this,
            title = title,
            subtitle = caption,
            accent = if (selected) AutoBridgeDesign.ACCENT_ONLINE else AutoBridgeDesign.ACCENT_WEB,
            // The row's fallback badge is the title's first character, which in Thai is a lone
            // leading vowel ("เ"); the browser's Latin initial reads right in both languages.
            badgeText = if (selected) "✓" else when (choice) {
                EngineChoice.AUTOBRIDGE -> "A"
                EngineChoice.CHROME -> "G"
                EngineChoice.BRAVE -> "B"
            },
            trailing = if (selected) "●" else "○",
        ) {
            if (!enabled) {
                Toast.makeText(this, R.string.desktop_settings_install_first, Toast.LENGTH_SHORT).show()
            } else {
                DesktopEngine.choice = choice
                structureKey = null
                currentSnapshot?.let(::render)
            }
        }.apply { alpha = if (enabled) 1f else 0.6f }
    }

    private fun runtimeCard(s: Snapshot): View {
        val r = s.runtime
        val card = card()
        card.addView(title(getString(R.string.desktop_settings_runtime_title)))
        card.addView(caption(RuntimeSource.VERSION_LABEL + (DesktopEngine.store.glibcVersion?.let { " · $it" } ?: "")))
        card.addView(status(stateLabel(r) + sizeSuffix(storage?.runtimeTotal), stateColor(r)))
        r.lastError?.let { card.addView(error(it)) }
        addProgress(card, "runtime", r)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when {
            r.state.busy -> actions += getString(R.string.desktop_settings_cancel) to { DesktopEngine.cancel() }
            r.state.installed -> actions += getString(R.string.desktop_settings_remove_runtime) to { confirmRemoveRuntime() }
            DesktopEngine.supported -> actions += getString(
                R.string.desktop_settings_install_runtime,
                Formatter.formatShortFileSize(this, RuntimeSource.LAYER_SIZE_BYTES),
                Formatter.formatShortFileSize(this, RuntimeSource.ESTIMATED_INSTALLED_BYTES)
            ) to { DesktopEngine.installRuntime() }
        }
        card.addView(buttons(actions, s.busy && !r.state.busy))
        return card
    }

    private fun browserCard(a: DesktopBrowserAdapter, s: Snapshot): View {
        val b = s.browsers[a.id] ?: ComponentState(InstallState.NOT_INSTALLED)
        val card = card()
        card.addView(title(getString(R.string.desktop_settings_browser_desktop, a.displayName)))
        val sizes = storage?.perBrowser?.get(a.id)
        val sizeLine = if (b.state.installed && sizes != null) {
            getString(
                R.string.desktop_settings_sizes,
                Formatter.formatShortFileSize(this, sizes.first),
                Formatter.formatShortFileSize(this, sizes.second),
                Formatter.formatShortFileSize(this, sizes.third)
            )
        } else {
            getString(
                R.string.desktop_settings_download_estimate,
                Formatter.formatShortFileSize(this, a.estimatedDownloadBytes),
                Formatter.formatShortFileSize(this, a.estimatedInstalledBytes)
            )
        }
        val versionLine = listOfNotNull(
            b.version?.let { getString(R.string.desktop_settings_version, it) },
            b.candidateVersion?.takeIf { it != b.version }?.let { getString(R.string.desktop_settings_candidate, it) },
        ).joinToString(" · ")
        card.addView(status(if (DesktopEngine.supported) stateLabel(b) else getString(dev.autobridge.desktop.R.string.desktop_state_unsupported), stateColor(b)))
        if (versionLine.isNotEmpty()) card.addView(caption(versionLine))
        card.addView(caption(sizeLine))
        b.lastError?.let { card.addView(error(it)) }
        addProgress(card, a.id.key, b)

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val other = s.busy && !b.state.busy
        when {
            !DesktopEngine.supported -> Unit
            b.state.busy -> actions += getString(R.string.desktop_settings_cancel) to { DesktopEngine.cancel() }
            b.state.installed -> {
                actions += getString(R.string.desktop_settings_launch) to { launch(a.id) }
                if (b.state == InstallState.UPDATE_AVAILABLE) {
                    actions += getString(R.string.desktop_settings_update) to { DesktopEngine.updateBrowser(a.id) }
                } else {
                    actions += getString(R.string.desktop_settings_check_updates) to { DesktopEngine.checkForUpdates(a.id) }
                }
                actions += getString(R.string.desktop_settings_clear_cache) to { confirm(R.string.desktop_settings_clear_cache_confirm, a) { DesktopEngine.clearCache(a.id); refreshStorageSoon() } }
                actions += getString(R.string.desktop_settings_reset_profile) to { confirm(R.string.desktop_settings_reset_profile_confirm, a) { DesktopEngine.resetProfile(a.id); refreshStorageSoon() } }
                actions += getString(R.string.desktop_settings_uninstall) to { confirmUninstall(a) }
            }
            else -> actions += getString(R.string.desktop_settings_install) to { install(a) }
        }
        card.addView(buttons(actions, other))
        return card
    }

    private fun diagnosticsCard(s: Snapshot): View {
        val card = card()
        diagnosticsText = TextView(this).apply {
            text = diagnostics(s)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            setTextIsSelectable(true)
        }
        card.addView(diagnosticsText)
        val seccomp = DesktopEngine.store.seccompAcceleration
        card.addView(buttons(listOf(
            getString(R.string.desktop_settings_copy_diagnostics) to {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AutoBridge desktop browser", diagnostics(currentSnapshot ?: s)))
                Toast.makeText(this, R.string.desktop_settings_copied, Toast.LENGTH_SHORT).show()
            },
            getString(if (seccomp) R.string.desktop_settings_seccomp_off else R.string.desktop_settings_seccomp_on) to {
                DesktopEngine.setSeccompAcceleration(!seccomp)
                structureKey = null
                currentSnapshot?.let(::render)
            },
            getString(R.string.desktop_settings_stop_browser) to { Thread { DesktopEngine.stopSession() }.start() },
        ), false))
        return card
    }

    private fun diagnostics(s: Snapshot): String {
        val session = DesktopEngine.session.value
        val status = session?.status?.value
        return buildString {
            appendLine("runtime: ${s.runtime.state} ${s.runtime.version ?: ""}")
            appendLine("proot: ${if (DesktopEngine.runtime.binariesPresent()) "present" else "missing"} · seccomp=${DesktopEngine.store.seccompAcceleration}")
            appendLine("glibc: ${DesktopEngine.store.glibcVersion ?: "-"}")
            appendLine("child process limit: ${ChildProcessLimit.status(this@DesktopBrowserSettingsActivity)}")
            DesktopBrowsers.all.forEach { a ->
                val b = s.browsers[a.id]
                appendLine("${a.id.key}: ${b?.state} ${b?.version ?: ""}")
            }
            appendLine("session: ${session?.adapter?.id?.key ?: "none"} ${status?.phase ?: ""} ${session?.let { "${it.width}x${it.height}" } ?: ""}")
            appendLine("x11: ${if (session?.x11Connected == true) "connected" else "not connected"}")
            appendLine("browser process: ${if (session?.isAlive == true) "alive" else "not running"}")
            status?.firstFrameAfterMs?.let { appendLine("first paint after: $it ms") }
            session?.let { appendLine("fps: ${"%.1f".format(it.renderer.fps)} · frames: ${it.renderer.framesDrawn}") }
            appendLine("memory (runtime RSS): ${Formatter.formatShortFileSize(this@DesktopBrowserSettingsActivity, DesktopEngine.runtimeMemoryBytes())}")
            storage?.let { appendLine("storage: ${Formatter.formatShortFileSize(this@DesktopBrowserSettingsActivity, it.runtimeTotal)}") }
            val error = status?.takeIf { it.phase == DesktopSession.Phase.FAILED }?.message
                ?: s.runtime.lastError ?: s.browsers.values.firstNotNullOfOrNull { it.lastError }
            error?.let { append("last error: ").append(it.take(600)) }
        }.trimEnd()
    }

    /** Start on launch, touch sensitivity and each browser's zoom. */
    private fun preferencesCard(): View {
        val store = DesktopEngine.store
        val card = card()
        fun refresh() { structureKey = null; currentSnapshot?.let(::render) }
        fun section(titleText: String, captionText: String) {
            card.addView(title(titleText).apply { setPadding(0, dp(10), 0, 0); textSize = 15f })
            card.addView(caption(captionText))
        }

        section(getString(R.string.desktop_prefs_start_on_launch), getString(R.string.desktop_prefs_start_on_launch_caption))
        card.addView(choice(listOf(
            getString(R.string.desktop_prefs_on) to store.startOnLaunch,
            getString(R.string.desktop_prefs_off) to !store.startOnLaunch,
        )) { i ->
            store.startOnLaunch = i == 0
            if (i == 0) Thread { DesktopEngine.prestartIfEnabled() }.start()
            refresh()
        })

        section(getString(R.string.desktop_prefs_sensitivity), getString(R.string.desktop_prefs_sensitivity_caption))
        val sens = store.touchSensitivity
        card.addView(stepper("%.2f×".format(java.util.Locale.US, sens),
            canDown = sens > DesktopEngine.SENSITIVITY_MIN + 0.01f,
            canUp = sens < DesktopEngine.SENSITIVITY_MAX - 0.01f,
        ) { delta ->
            DesktopEngine.setTouchSensitivity(sens + delta * 0.25f)
            refresh()
        })

        for (adapter in DesktopBrowsers.all) {
            section(getString(R.string.desktop_prefs_zoom, adapter.displayName), getString(R.string.desktop_prefs_zoom_caption))
            val zoom = store.zoom(adapter.id)
            card.addView(stepper("$zoom%", zoom > DesktopEngine.ZOOM_MIN, zoom < DesktopEngine.ZOOM_MAX) { delta ->
                DesktopEngine.setZoom(adapter.id, zoom + delta * 10)
                refresh()
            })
        }
        return card
    }

    /** Pills where exactly one is selected (filled). */
    private fun choice(options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): View {
        val flow = FlowRow(this, dp(8))
        options.forEachIndexed { i, (label, selected) ->
            flow.addView(AutoBridgeDesign.pill(this, (if (selected) "✓ " else "") + label, primary = selected) { onPick(i) })
        }
        return flow.apply { setPadding(0, dp(8), 0, 0) }
    }

    /** − value + . */
    private fun stepper(value: String, canDown: Boolean, canUp: Boolean, onStep: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        row.addView(AutoBridgeDesign.pill(this, "−") { if (canDown) onStep(-1) }.apply { alpha = if (canDown) 1f else 0.4f })
        row.addView(TextView(this).apply {
            text = value
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.TEXT)
            minWidth = dp(84)
        })
        row.addView(AutoBridgeDesign.pill(this, "+") { if (canUp) onStep(1) }.apply { alpha = if (canUp) 1f else 0.4f })
        return row
    }

    /** Android 12+ kills app child processes past a small limit; a browser needs many. */
    private fun childProcessNotice(): View? {
        val status = ChildProcessLimit.status(this)
        if (!DesktopEngine.supported || status == ChildProcessLimit.Status.NOT_APPLICABLE || status == ChildProcessLimit.Status.LIFTED) return null
        val card = card()
        card.addView(title(getString(R.string.desktop_settings_child_limit_title)))
        card.addView(caption(getString(
            if (status == ChildProcessLimit.Status.ACTIVE) R.string.desktop_settings_child_limit_14 else R.string.desktop_settings_child_limit_12
        )).apply { setTextColor(AutoBridgeDesign.TEXT) })
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (status == ChildProcessLimit.Status.ACTIVE) {
            actions += getString(R.string.desktop_settings_open_developer_options) to {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            }
        }
        actions += getString(R.string.desktop_settings_copy_adb) to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", ChildProcessLimit.ADB_COMMAND))
            Toast.makeText(this, R.string.desktop_settings_copied, Toast.LENGTH_SHORT).show()
        }
        card.addView(buttons(actions, false))
        card.background = AutoBridgeDesign.surface(this, AutoBridgeDesign.tint(AutoBridgeDesign.SIGNAL_SLOW, 0.08f), 20, AutoBridgeDesign.tint(AutoBridgeDesign.SIGNAL_SLOW, 0.3f))
        return card
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private fun install(a: DesktopBrowserAdapter) {
        if (a.id == DesktopBrowserId.CHROME && DesktopEngine.store.chromeTermsAcceptedAt == 0L) {
            AlertDialog.Builder(this)
                .setTitle(R.string.desktop_settings_chrome_terms_title)
                .setMessage(R.string.desktop_settings_chrome_terms_message)
                .setPositiveButton(R.string.desktop_settings_chrome_terms_accept) { _, _ ->
                    DesktopEngine.store.chromeTermsAcceptedAt = System.currentTimeMillis()
                    DesktopEngine.installBrowser(a.id)
                }
                .setNeutralButton(R.string.desktop_settings_chrome_terms_read) { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/chrome/terms/")))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.desktop_settings_install_title, a.displayName))
                .setMessage(getString(R.string.desktop_settings_install_message, a.displayName,
                    Formatter.formatShortFileSize(this, a.estimatedDownloadBytes + if (DesktopEngine.runtimeState.value.state.installed) 0 else RuntimeSource.LAYER_SIZE_BYTES + 200L * 1024 * 1024)))
                .setPositiveButton(R.string.desktop_settings_install) { _, _ -> DesktopEngine.installBrowser(a.id) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun launch(id: DesktopBrowserId) {
        startActivity(DesktopBrowserActivity.intent(this, id))
    }

    private fun confirmUninstall(a: DesktopBrowserAdapter) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.desktop_settings_uninstall_title, a.displayName))
            .setMessage(R.string.desktop_settings_uninstall_message)
            .setPositiveButton(R.string.desktop_settings_uninstall_keep_profile) { _, _ -> DesktopEngine.removeBrowser(a.id, keepProfile = true); refreshStorageSoon() }
            .setNeutralButton(R.string.desktop_settings_uninstall_delete_profile) { _, _ -> DesktopEngine.removeBrowser(a.id, keepProfile = false); refreshStorageSoon() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveRuntime() {
        AlertDialog.Builder(this)
            .setTitle(R.string.desktop_settings_remove_runtime)
            .setMessage(R.string.desktop_settings_remove_runtime_message)
            .setPositiveButton(R.string.desktop_settings_remove_runtime) { _, _ -> DesktopEngine.removeRuntime(); refreshStorageSoon() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirm(message: Int, a: DesktopBrowserAdapter, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(a.displayName)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshStorage() {
        scope.launch {
            storage = withContext(Dispatchers.IO) { runCatching { DesktopEngine.storage() }.getOrNull() }
            structureKey = null
            currentSnapshot?.let(::render)
        }
    }

    private fun refreshStorageSoon() {
        scope.launch {
            while (DesktopEngine.busy.value) delay(500)
            refreshStorage()
        }
    }

    // ── Views ───────────────────────────────────────────────────────────────

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AutoBridgeDesign.surface(this@DesktopBrowserSettingsActivity, AutoBridgeDesign.SURFACE, 20)
        setPadding(dp(16), dp(14), dp(16), dp(12))
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(AutoBridgeDesign.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(AutoBridgeDesign.TEXT_MUTED)
        setPadding(0, dp(2), 0, 0)
    }

    private fun status(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(color)
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun error(text: String) = TextView(this).apply {
        this.text = text.take(400)
        textSize = 12f
        setTextColor(AutoBridgeDesign.DANGER)
        setPadding(0, dp(4), 0, 0)
    }

    private fun notice(text: String, accent: Int) = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(AutoBridgeDesign.TEXT)
        background = AutoBridgeDesign.surface(this@DesktopBrowserSettingsActivity, AutoBridgeDesign.tint(accent, 0.1f), 16, AutoBridgeDesign.tint(accent, 0.3f))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun addProgress(card: LinearLayout, key: String, state: ComponentState) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = if (state.state.busy) View.VISIBLE else View.GONE
        }
        val label = caption("").apply { visibility = bar.visibility }
        card.addView(bar, LinearLayout.LayoutParams(-1, dp(18)).apply { topMargin = dp(6) })
        card.addView(label)
        progressViews[key] = bar to label
        updateProgress(key, state)
    }

    private fun updateProgress(key: String, state: ComponentState) {
        val (bar, label) = progressViews[key] ?: return
        val visible = if (state.state.busy) View.VISIBLE else View.GONE
        bar.visibility = visible
        label.visibility = visible
        val p = state.progress
        bar.isIndeterminate = p == null
        if (p != null) bar.progress = (p * 1000).toInt()
        label.text = buildString {
            append(stateLabel(state))
            if (state.bytesTotal > 0) append(" · ")
                .append(Formatter.formatShortFileSize(this@DesktopBrowserSettingsActivity, state.bytesDone))
                .append(" / ")
                .append(Formatter.formatShortFileSize(this@DesktopBrowserSettingsActivity, state.bytesTotal))
            else if (p != null) append(" · ").append((p * 100).toInt()).append('%')
            state.detail?.let { append(" · ").append(it.take(80)) }
        }
    }

    /** Pills that wrap onto as many rows as they need, so a long (Thai) label never hides another. */
    private fun buttons(actions: List<Pair<String, () -> Unit>>, disabled: Boolean): View {
        val flow = FlowRow(this, dp(8))
        actions.forEachIndexed { i, (label, action) ->
            flow.addView(AutoBridgeDesign.pill(this, label, primary = i == 0) { if (!disabled) action() }.apply {
                alpha = if (disabled) 0.45f else 1f
                isEnabled = !disabled
            })
        }
        return flow.apply { setPadding(0, dp(10), 0, 0) }
    }

    private fun stateLabel(s: ComponentState): String = getString(
        when (s.state) {
            InstallState.NOT_INSTALLED -> dev.autobridge.desktop.R.string.desktop_state_not_installed
            InstallState.DOWNLOADING -> dev.autobridge.desktop.R.string.desktop_state_downloading
            InstallState.VERIFYING -> dev.autobridge.desktop.R.string.desktop_state_verifying
            InstallState.EXTRACTING -> dev.autobridge.desktop.R.string.desktop_state_extracting
            InstallState.CONFIGURING -> dev.autobridge.desktop.R.string.desktop_state_configuring
            InstallState.READY -> dev.autobridge.desktop.R.string.desktop_state_ready
            InstallState.RUNNING -> dev.autobridge.desktop.R.string.desktop_state_running
            InstallState.FAILED -> dev.autobridge.desktop.R.string.desktop_state_failed
            InstallState.UPDATE_AVAILABLE -> dev.autobridge.desktop.R.string.desktop_state_update_available
        }
    )

    private fun stateColor(s: ComponentState): Int = when (s.state) {
        InstallState.READY, InstallState.RUNNING -> AutoBridgeDesign.ACCENT_ONLINE
        InstallState.FAILED -> AutoBridgeDesign.DANGER
        InstallState.UPDATE_AVAILABLE -> AutoBridgeDesign.SIGNAL_SLOW
        InstallState.NOT_INSTALLED -> AutoBridgeDesign.TEXT_MUTED
        else -> AutoBridgeDesign.ACCENT
    }

    private fun sizeSuffix(bytes: Long?): String = bytes?.takeIf { it > 0 }?.let { " · " + Formatter.formatShortFileSize(this, it) } ?: ""

    /** Minimal wrapping row. */
    private class FlowRow(context: Context, private val gap: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0; y += rowHeight + gap; rowHeight = 0
                }
                x += child.measuredWidth + gap
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowHeight + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var rowHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (x > 0 && x + child.measuredWidth > width) {
                    x = 0; y += rowHeight + gap; rowHeight = 0
                }
                child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
                x += child.measuredWidth + gap
                rowHeight = maxOf(rowHeight, child.measuredHeight)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DesktopBrowserSettingsActivity::class.java)
    }
}
