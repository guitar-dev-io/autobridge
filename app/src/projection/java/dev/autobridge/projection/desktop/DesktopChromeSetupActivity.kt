package dev.autobridge.projection.desktop

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.desktop.DesktopEngine
import dev.autobridge.desktop.browser.DesktopBrowserId
import dev.autobridge.desktop.install.ComponentState
import dev.autobridge.desktop.install.InstallState
import dev.autobridge.desktop.runtime.ChildProcessLimit
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AppFonts
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Guided Chrome setup: the three things that must happen before Netflix and the other
 * protected-video sites can open on the car, in the order they must happen.
 *
 *  1. Let Android keep the browser's background processes (the developer switch). Done first so a
 *     car that opens a site the moment Chrome finishes installing does not get its browser killed.
 *  2. Install Chrome (and the Linux runtime it needs), after Google's terms.
 *  3. Open it once to see that it works.
 *
 * Every step is read from the real engine/system state each time the page comes back, so a step the
 * user already finished is shown done, and nothing here duplicates the install itself: it calls the
 * same [DesktopEngine] operations as Settings › Desktop Browser Engine.
 */
class DesktopChromeSetupActivity : Activity() {
    private val scope = MainScope()
    private var collectJob: Job? = null
    private var body: LinearLayout? = null
    private var progress: Pair<ProgressBar, TextView>? = null
    private var structureKey: String? = null

    private val prefs get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        dev.autobridge.settings.ScreenAppearance.applyWindow(this)
        DesktopEngine.init(this)
        val content = AutoBridgeDesign.body(this)
        body = content
        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.chrome_setup_title),
                    subtitle = getString(R.string.chrome_setup_subtitle),
                    onBack = { finish() }
                ),
                body = content
            )
        )
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Developer options: re-read the switch rather than trust the old page.
        structureKey = null
        collectJob = scope.launch {
            combine(DesktopEngine.runtimeState, DesktopEngine.browserStates) { r, b -> r to b }
                .collect { (r, b) -> render(r, b[DesktopBrowserId.CHROME] ?: ComponentState(InstallState.NOT_INSTALLED)) }
        }
    }

    override fun onPause() {
        collectJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private enum class Step { LIMIT, INSTALL, TEST }

    private fun limitNeedsAction(): Boolean {
        val status = ChildProcessLimit.status(this)
        if (!DesktopEngine.supported) return false
        if (status == ChildProcessLimit.Status.NOT_APPLICABLE || status == ChildProcessLimit.Status.LIFTED) return false
        return !prefs.getBoolean(KEY_LIMIT_CONFIRMED, false)
    }

    private fun currentStep(chrome: ComponentState): Step = when {
        chrome.state.installed -> Step.TEST
        limitNeedsAction() -> Step.LIMIT
        else -> Step.INSTALL
    }

    private fun render(runtime: ComponentState, chrome: ComponentState) {
        val step = currentStep(chrome)
        val key = "$step/${runtime.state}/${chrome.state}/${chrome.lastError}/${runtime.lastError}"
        if (key == structureKey) {
            updateProgress(runtime, chrome)
            return
        }
        structureKey = key
        build(step, runtime, chrome)
    }

    private fun build(step: Step, runtime: ComponentState, chrome: ComponentState) {
        val content = body ?: return
        content.removeAllViews()
        progress = null

        if (!DesktopEngine.supported) {
            content.stack(notice(getString(R.string.desktop_settings_unsupported), dev.autobridge.design.ThemeHolder.palette.warn))
            return
        }
        content.stack(
            notice(
                getString(R.string.chrome_setup_why),
                AutoBridgeDesign.ACCENT
            )
        )

        // 1 — background processes
        val limitDone = step != Step.LIMIT
        content.stack(stepCard(1, getString(R.string.chrome_setup_step_limit), limitDone) { card ->
            if (limitDone) {
                card.addView(caption(getString(R.string.chrome_setup_step_limit_done)))
            } else {
                val status = ChildProcessLimit.status(this)
                card.addView(caption(getString(
                    if (status == ChildProcessLimit.Status.ACTIVE) R.string.desktop_settings_child_limit_14
                    else R.string.desktop_settings_child_limit_12
                )))
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                if (status == ChildProcessLimit.Status.ACTIVE) {
                    actions += getString(R.string.desktop_settings_open_developer_options) to {
                        runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                        Unit
                    }
                } else {
                    actions += getString(R.string.desktop_settings_copy_adb) to {
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("adb", ChildProcessLimit.ADB_COMMAND))
                        Toast.makeText(this, R.string.desktop_settings_copied, Toast.LENGTH_SHORT).show()
                    }
                }
                // The switch cannot always be read back (Android 12 and 13 never; some vendors'
                // builds not either), so the user can say it is done instead of being stuck here.
                actions += getString(R.string.chrome_setup_limit_done) to {
                    prefs.edit().putBoolean(KEY_LIMIT_CONFIRMED, true).apply()
                    structureKey = null
                    render(runtime, chrome)
                }
                card.addView(buttons(actions))
            }
        }, gap = 10)

        // 2 — install
        val installDone = step == Step.TEST
        content.stack(stepCard(2, getString(R.string.chrome_setup_step_install), installDone) { card ->
            when {
                installDone -> card.addView(caption(getString(R.string.chrome_setup_step_install_done, chrome.version ?: "")))
                step == Step.LIMIT -> card.addView(caption(getString(R.string.chrome_setup_step_install_waiting)))
                chrome.state.busy || runtime.state.busy -> {
                    card.addView(caption(getString(R.string.chrome_setup_installing)))
                    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
                    val label = caption("")
                    card.addView(bar, LinearLayout.LayoutParams(-1, dp(18)).apply { topMargin = dp(8) })
                    card.addView(label)
                    progress = bar to label
                    updateProgress(runtime, chrome)
                    card.addView(buttons(listOf(getString(R.string.desktop_settings_cancel) to { DesktopEngine.cancel() })))
                }
                else -> {
                    card.addView(caption(getString(R.string.chrome_setup_step_install_caption)))
                    networkWarning()?.let { card.addView(notice(it, dev.autobridge.design.ThemeHolder.palette.warn).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
                    (chrome.lastError ?: runtime.lastError)?.let { card.addView(error(it)) }
                    card.addView(buttons(listOf(getString(R.string.chrome_setup_install) to { install() })))
                }
            }
        }, gap = 10)

        // 3 — test
        content.stack(stepCard(3, getString(R.string.chrome_setup_step_test), false) { card ->
            if (step == Step.TEST) {
                card.addView(caption(getString(R.string.chrome_setup_step_test_caption)))
                card.addView(buttons(listOf(
                    getString(R.string.chrome_setup_open_chrome) to {
                        startActivity(DesktopBrowserActivity.intent(this, DesktopBrowserId.CHROME))
                    },
                    getString(R.string.chrome_setup_finish) to { finish() }
                )))
                card.addView(notice(getString(R.string.chrome_setup_car_hint), dev.autobridge.design.ThemeHolder.palette.good).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            } else {
                card.addView(caption(getString(R.string.chrome_setup_step_test_waiting)))
            }
        }, gap = 10)
    }

    private fun install() {
        val engine = DesktopEngine
        if (engine.store.chromeTermsAcceptedAt != 0L) {
            engine.installBrowser(DesktopBrowserId.CHROME)
            return
        }
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(R.string.desktop_settings_chrome_terms_title)
            .setMessage(R.string.desktop_settings_chrome_terms_message)
            .setPositiveButton(R.string.desktop_settings_chrome_terms_accept) { _, _ ->
                engine.store.chromeTermsAcceptedAt = System.currentTimeMillis()
                engine.installBrowser(DesktopBrowserId.CHROME)
            }
            .setNeutralButton(R.string.desktop_settings_chrome_terms_read) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/chrome/terms/")))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Wi-Fi and free space, said plainly; neither blocks the install, the user decides. */
    private fun networkWarning(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val free = filesDir.usableSpace
        return when {
            free < MIN_FREE_BYTES -> getString(R.string.chrome_setup_low_space, Formatter.formatShortFileSize(this, free))
            !onWifi -> getString(R.string.chrome_setup_not_wifi)
            else -> null
        }
    }

    private fun updateProgress(runtime: ComponentState, chrome: ComponentState) {
        val (bar, label) = progress ?: return
        // The runtime goes first, then Chrome; show whichever is working.
        val s = if (runtime.state.busy) runtime else chrome
        val p = s.progress
        bar.isIndeterminate = p == null
        if (p != null) bar.progress = (p * 1000).toInt()
        label.text = buildString {
            append(getString(if (runtime.state.busy) R.string.chrome_setup_phase_runtime else R.string.chrome_setup_phase_chrome))
            if (s.bytesTotal > 0) append(" · ")
                .append(Formatter.formatShortFileSize(this@DesktopChromeSetupActivity, s.bytesDone))
                .append(" / ")
                .append(Formatter.formatShortFileSize(this@DesktopChromeSetupActivity, s.bytesTotal))
            else if (p != null) append(" · ").append((p * 100).toInt()).append('%')
        }
    }

    // ── Views ───────────────────────────────────────────────────────────────

    private fun stepCard(number: Int, titleText: String, done: Boolean, fill: (LinearLayout) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoBridgeDesign.surface(this@DesktopChromeSetupActivity, AutoBridgeDesign.SURFACE, 20)
            setPadding(dp(16), dp(14), dp(16), dp(12))
            addView(TextView(context).apply {
                text = (if (done) "✓  " else "$number.  ") + titleText
                textSize = 17f
                setTextColor(if (done) dev.autobridge.design.ThemeHolder.palette.good else AutoBridgeDesign.TEXT)
                typeface = AppFonts.medium()
            })
            fill(this)
        }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(AutoBridgeDesign.TEXT_MUTED)
        setPadding(0, dp(4), 0, 0)
    }

    private fun error(text: String) = TextView(this).apply {
        this.text = text.take(400)
        textSize = 12f
        setTextColor(AutoBridgeDesign.DANGER)
        setPadding(0, dp(6), 0, 0)
    }

    private fun notice(text: String, accent: Int) = TextView(this).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(AutoBridgeDesign.TEXT)
        background = AutoBridgeDesign.surface(this@DesktopChromeSetupActivity, AutoBridgeDesign.tint(accent, 0.1f), 16, AutoBridgeDesign.tint(accent, 0.3f))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun buttons(actions: List<Pair<String, () -> Unit>>): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            actions.forEachIndexed { i, (label, action) ->
                addView(
                    AutoBridgeDesign.pill(this@DesktopChromeSetupActivity, label, primary = i == 0) { action() },
                    LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) topMargin = dp(8) }
                )
            }
        }

    companion object {
        private const val PREFS = "desktop_chrome_setup"
        private const val KEY_LIMIT_CONFIRMED = "limit_confirmed"
        private const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

        fun intent(context: Context): Intent = Intent(context, DesktopChromeSetupActivity::class.java)
    }
}
