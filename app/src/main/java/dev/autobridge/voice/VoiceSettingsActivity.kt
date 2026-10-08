package dev.autobridge.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.SettingsUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings &gt; Voice Recognition: the switches for voice commands, and the Whisper models -
 * download, cancel, retry, use, delete, import - without rebuilding the APK.
 *
 * The page is rebuilt when a model changes state (installed, deleted, selected, a download starting
 * or failing). Download progress, which ticks many times a second, only updates that model's bar
 * and label in place, so the page does not jump while something downloads.
 */
class VoiceSettingsActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, VoiceSettingsActivity::class.java)
        private const val REQUEST_IMPORT = 4101
    }

    private val accent = AutoBridgeDesign.ACCENT_SYSTEM
    private val scope = MainScope()
    private lateinit var store: WhisperModelStore

    /** Live progress views by model id, valid for the current render only. */
    private val progressViews = mutableMapOf<String, Pair<ProgressBar, TextView>>()
    private var renderedSignature: String? = null
    private var page: View? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VoiceRuntime.modelStore(this)
        scope.launch {
            combine(store.downloads, store.revision) { downloads, _ -> downloads }.collect { downloads ->
                val signature = signatureOf(downloads)
                if (signature != renderedSignature) render() else updateProgress(downloads)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** What decides the page's structure: each model's state kind, plus what is installed and selected. */
    private fun signatureOf(downloads: Map<String, DownloadState>): String = buildString {
        store.listAvailableModels().forEach { info ->
            append(info.id).append(':').append(
                when (val state = downloads[info.id]) {
                    null -> "idle"
                    is DownloadState.Running -> "run"
                    DownloadState.Verifying -> "verify"
                    is DownloadState.Failed -> "fail-${state.error}"
                    DownloadState.Idle -> "idle"
                }
            ).append(';')
        }
        append(store.revision.value)
    }

    // ------------------------------------------------------------------------------------ page

    private fun render() {
        val downloads = store.downloads.value
        renderedSignature = signatureOf(downloads)
        progressViews.clear()
        val scrollY = page?.let { AutoBridgeDesign.pageScroll(it)?.scrollY } ?: 0

        val body = AutoBridgeDesign.body(this)
        val supported = VoiceRuntime.isSupported(this)
        val installed = store.getInstalledModels()
        val selected = store.getSelectedModel()

        if (!supported) body.stack(hint(getString(R.string.voice_unsupported)), gap = 12)

        body.stack(settingsGroup(selected, installed), gap = 14)
        body.stack(shortcutsGroup(), gap = 14)

        if (installed.isEmpty()) {
            body.stack(requiredCard(downloads), gap = 14)
        }

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.voice_group_models)), gap = 2)
        store.listAvailableModels().forEach { info ->
            body.stack(modelCard(info, downloads[info.id], selected?.id == info.id), gap = 8)
        }
        body.stack(
            SettingsUi.card(this, listOf(
                SettingsUi.row(
                    this, getString(R.string.voice_import_title), getString(R.string.voice_import_caption),
                    R.drawable.ic_browser_download, accent
                ) { pickImportFile() },
                SettingsUi.row(
                    this, getString(R.string.voice_verify_title), getString(R.string.voice_verify_caption),
                    R.drawable.ic_tile_settings, accent
                ) { verifyActive() }
            )),
            gap = 14
        )

        body.stack(advancedGroup(), gap = 14)

        val built = AutoBridgeDesign.page(
            context = this,
            header = AutoBridgeDesign.header(
                context = this,
                title = getString(R.string.voice_settings_title),
                subtitle = getString(R.string.voice_settings_subtitle),
                onBack = { finish() }
            ),
            body = body
        )
        page = built
        setContentView(built)
        AutoBridgeDesign.pageScroll(built)?.post { AutoBridgeDesign.pageScroll(built)?.scrollTo(0, scrollY) }
    }

    private fun settingsGroup(selected: WhisperModelInfo?, installed: List<WhisperModelInfo>): View {
        val enabled = VoiceSettings.enabled(this)
        val translate = VoiceSettings.translate(this)
        val autoExecute = VoiceSettings.autoExecute(this)
        return SettingsUi.group(this, getString(R.string.voice_group_commands), listOf(
            SettingsUi.switchRow(
                this, getString(R.string.voice_enable_title),
                getString(if (enabled) R.string.voice_enable_on else R.string.voice_enable_off),
                R.drawable.ic_car_mic, accent, enabled
            ) { VoiceSettings.setEnabled(this, !enabled); render() },
            SettingsUi.valueRow(this, getString(R.string.voice_language_title), languageLabel(VoiceSettings.language(this))) {
                choose(
                    getString(R.string.voice_language_title),
                    VoiceLanguage.entries,
                    VoiceSettings.language(this),
                    ::languageLabel
                ) { VoiceSettings.setLanguage(this, it) }
            },
            SettingsUi.valueRow(
                this, getString(R.string.voice_model_title),
                selected?.displayName ?: getString(R.string.voice_model_none)
            ) {
                if (installed.size > 1) {
                    choose(getString(R.string.voice_model_title), installed, selected, { it?.displayName.orEmpty() }) { pick ->
                        pick?.let { useModel(it) }
                    }
                }
            },
            SettingsUi.switchRow(
                this, getString(R.string.voice_translate_title),
                getString(if (translate) R.string.voice_translate_on else R.string.voice_translate_off),
                R.drawable.ic_tile_language, accent, translate
            ) { VoiceSettings.setTranslate(this, !translate); render() },
            SettingsUi.switchRow(
                this, getString(R.string.voice_auto_execute_title),
                getString(if (autoExecute) R.string.voice_auto_execute_on else R.string.voice_auto_execute_off),
                R.drawable.ic_browser_play, accent, autoExecute
            ) { VoiceSettings.setAutoExecute(this, !autoExecute); render() },
            SettingsUi.valueRow(this, getString(R.string.voice_downloaded_title), VoiceFormat.size(store.installedBytes())) {},
            SettingsUi.row(
                this, getString(R.string.voice_test_title), getString(R.string.voice_test_caption),
                R.drawable.ic_car_mic, accent
            ) { startActivity(VoiceTestActivity.intent(this)) }
        ))
    }

    /** Shown while nothing is installed: the recommended model first, then three alternatives. */
    private fun requiredCard(downloads: Map<String, DownloadState>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AutoBridgeDesign.surface(this@VoiceSettingsActivity, AutoBridgeDesign.tint(accent, 0.08f), 20, AutoBridgeDesign.tint(accent, 0.35f))
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(text(getString(R.string.voice_model_required_title), 17f, AutoBridgeDesign.TEXT, bold = true))
        addView(text(getString(R.string.voice_model_required_body), 13f, AutoBridgeDesign.TEXT_MUTED).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        val recommended = WhisperModelCatalog.recommended
        addView(text(getString(R.string.voice_recommended).uppercase(), 12f, accent, bold = true))
        addView(compactSuggestion(recommended, downloads[recommended.id]))
        addView(text(getString(R.string.voice_other_models).uppercase(), 12f, AutoBridgeDesign.TEXT_MUTED, bold = true).apply {
            setPadding(0, dp(12), 0, 0)
        })
        WhisperModelCatalog.suggested.filter { it.id != recommended.id }.forEach { info ->
            addView(compactSuggestion(info, downloads[info.id]))
        }
    }

    private fun compactSuggestion(info: WhisperModelInfo, state: DownloadState?): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(4))
        val labels = LinearLayout(this@VoiceSettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(info.displayName, 15f, AutoBridgeDesign.TEXT, bold = true))
        labels.addView(text("~" + VoiceFormat.size(info.sizeBytes) + " · " + tierLabel(info), 12.5f, AutoBridgeDesign.TEXT_MUTED))
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        if (state is DownloadState.Running || state == DownloadState.Verifying) {
            addView(text(stateLabel(state), 13f, accent))
        } else {
            addView(AutoBridgeDesign.pill(this@VoiceSettingsActivity, getString(R.string.voice_action_download), primary = true, accent = accent) {
                startDownload(info)
            })
        }
    }

    /** One model, in whichever of its states it is in. */
    private fun modelCard(info: WhisperModelInfo, state: DownloadState?, active: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AutoBridgeDesign.surface(
            this@VoiceSettingsActivity, AutoBridgeDesign.SURFACE, 16,
            if (active) AutoBridgeDesign.tint(accent, 0.6f) else AutoBridgeDesign.HAIRLINE
        )
        setPadding(dp(14), dp(12), dp(12), dp(12))
        val installed = store.isModelInstalled(info.id)

        val top = LinearLayout(this@VoiceSettingsActivity).apply { gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(this@VoiceSettingsActivity).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(info.displayName, 15.5f, AutoBridgeDesign.TEXT, bold = true))
        val meta = buildString {
            append(VoiceFormat.size(if (installed) store.modelFile(info).length() else info.sizeBytes))
            append(" · ")
            append(tierLabel(info))
        }
        labels.addView(text(meta, 12.5f, AutoBridgeDesign.TEXT_MUTED))
        top.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        addView(top)

        val buttons = LinearLayout(this@VoiceSettingsActivity).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        fun button(label: Int, primary: Boolean = false, action: () -> Unit) {
            buttons.addView(
                AutoBridgeDesign.pill(this@VoiceSettingsActivity, getString(label), primary = primary, accent = accent, onClick = action),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
            )
        }

        when {
            state is DownloadState.Running || state == DownloadState.Verifying -> {
                val label = text(stateLabel(state), 13f, accent)
                val bar = ProgressBar(this@VoiceSettingsActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    isIndeterminate = state == DownloadState.Verifying
                    progress = (state as? DownloadState.Running)?.percent ?: 0
                    progressTintList = ColorStateList.valueOf(accent)
                    progressBackgroundTintList = ColorStateList.valueOf(AutoBridgeDesign.HAIRLINE)
                }
                addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                addView(bar, LinearLayout.LayoutParams(-1, -2))
                progressViews[info.id] = bar to label
                if (state is DownloadState.Running) button(R.string.voice_action_cancel) { store.cancelDownload(info.id) }
            }
            active -> {
                addView(text(getString(R.string.voice_state_active), 13.5f, AutoBridgeDesign.ACCENT_ONLINE, bold = true).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            }
            installed -> {
                addView(text(getString(R.string.voice_state_installed), 13.5f, AutoBridgeDesign.ACCENT_ONLINE).apply {
                    setPadding(0, dp(6), 0, 0)
                })
                button(R.string.voice_action_delete) { confirmDelete(info) }
                button(R.string.voice_action_use, primary = true) { useModel(info) }
            }
            state is DownloadState.Failed -> {
                addView(text(errorLabel(state.error), 13f, AutoBridgeDesign.DANGER).apply { setPadding(0, dp(6), 0, 0) })
                if (info.downloadable) {
                    if (store.partialBytes(info.id) > 0) button(R.string.voice_action_delete) {
                        store.deleteModel(info.id)
                        store.clearFailure(info.id)
                    }
                    button(R.string.voice_action_retry, primary = true) { startDownload(info) }
                }
            }
            !info.downloadable -> {
                addView(text(getString(R.string.voice_import_only), 12.5f, AutoBridgeDesign.TEXT_MUTED).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            }
            else -> {
                val partial = store.partialBytes(info.id)
                if (partial > 0) {
                    addView(text(getString(R.string.voice_state_partial, VoiceFormat.size(partial)), 12.5f, AutoBridgeDesign.TEXT_MUTED).apply {
                        setPadding(0, dp(6), 0, 0)
                    })
                }
                button(if (partial > 0) R.string.voice_action_retry else R.string.voice_action_download, primary = true) {
                    startDownload(info)
                }
            }
        }
        if (buttons.childCount > 0) addView(buttons, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * "My commands": the driver's own phrases and where each goes ([VoiceShortcut]). Listed as
     * "phrase → destination"; tapping one offers to delete it.
     */
    private fun shortcutsGroup(): View {
        val rows = VoiceShortcutStore.all(this).map { shortcut ->
            SettingsUi.valueRow(this, "“${shortcut.phrase}”", destination(shortcut)) { confirmRemove(shortcut) }
        } + SettingsUi.row(
            this, getString(R.string.voice_shortcut_add), getString(R.string.voice_shortcut_add_caption),
            R.drawable.ic_car_mic, accent
        ) { addShortcut() }
        return SettingsUi.group(this, getString(R.string.voice_group_shortcuts), rows)
    }

    private fun destination(shortcut: VoiceShortcut): String = when (shortcut.kind) {
        VoiceShortcut.Kind.SCREEN -> VoiceScreen.entries.firstOrNull { it.name == shortcut.value }
            ?.let { getString(it.section.titleRes) } ?: shortcut.value
        VoiceShortcut.Kind.URL -> shortcut.value.removePrefix("https://").removePrefix("http://")
        VoiceShortcut.Kind.COMMAND -> "“${shortcut.value}”"
    }

    private fun confirmRemove(shortcut: VoiceShortcut) {
        AlertDialog.Builder(this)
            .setTitle("“${shortcut.phrase}”")
            .setMessage(destination(shortcut))
            .setPositiveButton(R.string.voice_shortcut_delete) { _, _ ->
                VoiceShortcutStore.remove(this, shortcut)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Step 1: the words. Step 2: what they do. Step 3: where (a screen, an address, a command). */
    private fun addShortcut() {
        val phrase = android.widget.EditText(this).apply {
            hint = getString(R.string.voice_shortcut_phrase_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_shortcut_phrase_title)
            .setView(padded(phrase))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val words = phrase.text.toString().trim()
                if (words.length < 2) toast(getString(R.string.voice_shortcut_too_short)) else chooseKind(words)
            }
            .show()
    }

    private fun chooseKind(words: String) {
        val kinds = listOf(VoiceShortcut.Kind.SCREEN, VoiceShortcut.Kind.URL, VoiceShortcut.Kind.COMMAND)
        val labels = listOf(R.string.voice_shortcut_kind_screen, R.string.voice_shortcut_kind_url, R.string.voice_shortcut_kind_command)
            .map { getString(it) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_shortcut_kind_title, words))
            .setItems(labels.toTypedArray()) { _, index ->
                when (kinds[index]) {
                    VoiceShortcut.Kind.SCREEN -> chooseScreen(words)
                    VoiceShortcut.Kind.URL -> askValue(words, VoiceShortcut.Kind.URL, R.string.voice_shortcut_url_hint)
                    VoiceShortcut.Kind.COMMAND -> askValue(words, VoiceShortcut.Kind.COMMAND, R.string.voice_shortcut_command_hint)
                }
            }
            .show()
    }

    private fun chooseScreen(words: String) {
        val screens = VoiceScreen.entries
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_shortcut_kind_title, words))
            .setItems(screens.map { getString(it.section.titleRes) }.toTypedArray()) { _, index ->
                save(VoiceShortcut(words, VoiceShortcut.Kind.SCREEN, screens[index].name))
            }
            .show()
    }

    private fun askValue(words: String, kind: VoiceShortcut.Kind, hintRes: Int) {
        val field = android.widget.EditText(this).apply {
            hint = getString(hintRes)
            setSingleLine()
            if (kind == VoiceShortcut.Kind.URL) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_shortcut_kind_title, words))
            .setView(padded(field))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = field.text.toString().trim()
                if (value.isEmpty()) return@setPositiveButton
                save(VoiceShortcut(words, kind, value))
            }
            .show()
    }

    private fun save(shortcut: VoiceShortcut) {
        VoiceShortcutStore.add(this, shortcut)
        toast(getString(R.string.voice_shortcut_saved, shortcut.phrase))
        render()
    }

    private fun padded(view: View): View = LinearLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    private fun advancedGroup(): View {
        val silence = VoiceSettings.silenceTimeoutMs(this)
        val maxListen = VoiceSettings.maxListenMs(this)
        val threads = VoiceSettings.threads(this)
        val autoThreads = VoiceSettings.autoThreads(Runtime.getRuntime().availableProcessors())
        fun seconds(ms: Int) = getString(R.string.voice_seconds, VoiceFormat.seconds(ms.toLong()))
        fun threadLabel(value: Int) =
            if (value == 0) getString(R.string.voice_threads_auto, autoThreads) else value.toString()
        // No GPU option: the build has no GPU backend (see whisper/src/main/cpp/CMakeLists.txt), and
        // a switch that changes nothing is worse than no switch.
        return SettingsUi.group(this, getString(R.string.voice_group_advanced), listOf(
            SettingsUi.valueRow(this, getString(R.string.voice_silence_title), seconds(silence)) {
                choose(getString(R.string.voice_silence_title), VoiceSettings.SILENCE_OPTIONS_MS, silence, ::seconds) {
                    VoiceSettings.setSilenceTimeoutMs(this, it)
                }
            },
            SettingsUi.valueRow(this, getString(R.string.voice_max_listen_title), seconds(maxListen)) {
                choose(getString(R.string.voice_max_listen_title), VoiceSettings.MAX_LISTEN_OPTIONS_MS, maxListen, ::seconds) {
                    VoiceSettings.setMaxListenMs(this, it)
                }
            },
            SettingsUi.valueRow(this, getString(R.string.voice_threads_title), threadLabel(threads)) {
                choose(getString(R.string.voice_threads_title), VoiceSettings.THREAD_OPTIONS, threads, ::threadLabel) {
                    VoiceSettings.setThreads(this, it)
                }
            }
        ))
    }

    private fun updateProgress(downloads: Map<String, DownloadState>) {
        progressViews.forEach { (id, views) ->
            val state = downloads[id] ?: return@forEach
            val (bar, label) = views
            label.text = stateLabel(state)
            if (state is DownloadState.Running) {
                bar.isIndeterminate = false
                bar.progress = state.percent
            }
        }
    }

    // --------------------------------------------------------------------------------- actions

    private fun startDownload(info: WhisperModelInfo) {
        store.clearFailure(info.id)
        when (networkKind()) {
            NetworkKind.NONE -> toast(getString(R.string.voice_download_no_network))
            NetworkKind.METERED -> AlertDialog.Builder(this)
                .setTitle(R.string.voice_download_metered_title)
                .setMessage(getString(R.string.voice_download_metered_body, info.displayName, VoiceFormat.size(info.sizeBytes)))
                .setNegativeButton(R.string.voice_action_cancel, null)
                .setPositiveButton(R.string.voice_action_download) { _, _ -> store.downloadModel(info.id) }
                .show()
            NetworkKind.UNMETERED -> store.downloadModel(info.id)
        }
    }

    /**
     * Makes [info] the active model. When another model is resident, it is released and this one
     * loaded straight away, so the next microphone press does not wait for the switch.
     */
    private fun useModel(info: WhisperModelInfo) {
        if (!store.selectModel(info.id)) return
        val engine = VoiceRuntime.engine(this)
        val file = store.getModelPath(info.id)
        if (engine.loadedModelId != null && engine.loadedModelId != info.id && file != null) {
            scope.launch(Dispatchers.Default) { runCatching { engine.ensureLoaded(info, file) } }
        }
        render()
    }

    private fun confirmDelete(info: WhisperModelInfo) {
        if (store.getSelectedModel()?.id == info.id) {
            toast(getString(R.string.voice_delete_active_blocked))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_delete_confirm_title, info.displayName))
            .setMessage(R.string.voice_delete_confirm_body)
            .setNegativeButton(R.string.voice_action_cancel, null)
            .setPositiveButton(R.string.voice_action_delete) { _, _ ->
                VoiceRuntime.releaseIfLoaded(this, info.id)
                scope.launch {
                    val result = withContext(Dispatchers.IO) { store.deleteModel(info.id) }
                    if (result == ModelDeletion.ACTIVE_MODEL) toast(getString(R.string.voice_delete_active_blocked))
                    render()
                }
            }
            .show()
    }

    private fun verifyActive() {
        val active = store.getSelectedModel() ?: return toast(getString(R.string.voice_err_no_model))
        scope.launch {
            val result = withContext(Dispatchers.IO) { store.verifyModel(active.id) }
            toast(getString(if (result == ModelVerification.OK) R.string.voice_verify_ok else R.string.voice_verify_bad))
        }
    }

    private fun pickImportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_IMPORT) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importFrom(uri)
    }

    private fun importFrom(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                    } ?: -1L
                    val input = contentResolver.openInputStream(uri) ?: throw ModelException(ModelError.CORRUPTED, "unreadable")
                    store.importModel(input, size)
                }
            }
            result.onSuccess { toast(getString(R.string.voice_import_done, it.displayName)) }
            result.onFailure { error ->
                toast(errorLabel((error as? ModelException)?.error ?: ModelError.CORRUPTED))
            }
            render()
        }
    }

    // -------------------------------------------------------------------------------- helpers

    private enum class NetworkKind { NONE, METERED, UNMETERED }

    private fun networkKind(): NetworkKind {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.UNMETERED
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return NetworkKind.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.NONE
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) NetworkKind.UNMETERED else NetworkKind.METERED
    }

    private fun stateLabel(state: DownloadState): String = when (state) {
        is DownloadState.Running -> getString(R.string.voice_state_downloading, state.percent) +
            "  " + VoiceFormat.size(state.bytes) + " / " + VoiceFormat.size(state.total)
        DownloadState.Verifying -> getString(R.string.voice_state_verifying)
        is DownloadState.Failed -> errorLabel(state.error)
        DownloadState.Idle -> ""
    }

    private fun tierLabel(info: WhisperModelInfo): String = getString(
        when (info.tier) {
            WhisperModelTier.RECOMMENDED -> R.string.voice_tier_recommended
            WhisperModelTier.LOW_END -> R.string.voice_tier_low_end
            WhisperModelTier.FAST -> R.string.voice_tier_fast
            WhisperModelTier.BALANCED -> R.string.voice_tier_balanced
            WhisperModelTier.ACCURATE -> R.string.voice_tier_accurate
            WhisperModelTier.FULL_PRECISION -> R.string.voice_tier_full
        }
    )

    private fun errorLabel(error: ModelError): String = getString(
        when (error) {
            ModelError.NETWORK, ModelError.CANCELLED -> R.string.model_error_network
            ModelError.HTTP -> R.string.model_error_http
            ModelError.INSUFFICIENT_STORAGE -> R.string.model_error_storage
            ModelError.INCOMPLETE -> R.string.model_error_incomplete
            ModelError.CHECKSUM_MISMATCH -> R.string.model_error_checksum
            ModelError.CORRUPTED -> R.string.model_error_corrupted
            ModelError.NOT_DOWNLOADABLE -> R.string.model_error_not_downloadable
            ModelError.NOT_RECOGNIZED -> R.string.model_error_not_recognized
            ModelError.ALREADY_INSTALLED -> R.string.model_error_already
        }
    )

    private fun languageLabel(language: VoiceLanguage): String = getString(
        when (language) {
            VoiceLanguage.AUTO -> R.string.voice_language_auto
            VoiceLanguage.THAI -> R.string.voice_language_th
            VoiceLanguage.ENGLISH -> R.string.voice_language_en
        }
    )

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun hint(value: String) = text(value, 13f, AutoBridgeDesign.DANGER).apply {
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun <T> choose(title: String, options: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.map(label).toTypedArray(), options.indexOf(current)) { dialog, index ->
                onPick(options[index])
                dialog.dismiss()
                render()
            }
            .show()
    }
}
