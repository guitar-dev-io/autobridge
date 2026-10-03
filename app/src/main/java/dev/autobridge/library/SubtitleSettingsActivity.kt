package dev.autobridge.library

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.display.StructuredLog
import dev.autobridge.i18n.AppLocale
import dev.autobridge.subtitles.SubtitleLanguage
import dev.autobridge.subtitles.SubtitleLanguages
import dev.autobridge.subtitles.SubtitleSettings
import dev.autobridge.subtitles.TranslationEngine
import dev.autobridge.subtitles.opusmt.OpusMtAvailability
import dev.autobridge.subtitles.opusmt.OpusMtCatalog
import dev.autobridge.subtitles.opusmt.OpusMtModelLayout
import dev.autobridge.subtitles.opusmt.OpusMtModelStore
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import java.io.FileNotFoundException
import kotlin.concurrent.thread

/**
 * The Subtitles section of settings: whether a track is translated, by which engine, between
 * which languages, and - for Opus-MT - which models are on disk.
 *
 * Everything here writes through [SubtitleSettings], which the playback service's
 * [dev.autobridge.subtitles.SubtitleController] listens to, so a change made on this screen rebinds
 * the engine mid-playback without a round trip through the player. Nothing here needs a running
 * player.
 *
 * The model rows are the one part that touches disk and network, so a download or a delete runs on
 * a background thread and the screen is re-rendered on the main thread when it returns; the rest is
 * SharedPreferences writes that are cheap enough to do inline.
 */
class SubtitleSettingsActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, SubtitleSettingsActivity::class.java)
    }

    private val accent = AutoBridgeDesign.ACCENT_VIDEO

    /** Set while a download is in flight, so a second tap does not start a parallel fetch. */
    @Volatile
    private var downloading = false

    /** Applies the Settings &gt; Language choice; see [AppLocale.rebase]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val body = AutoBridgeDesign.body(this)

        body.stack(AutoBridgeDesign.sectionLabel(this, "Translation"), gap = 2)

        val enabled = SubtitleSettings.enabled(this)
        body.stack(
            toggleRow(
                title = "Translate subtitles",
                on = enabled,
                onCaption = "On-device translation of the subtitle track",
                offCaption = "Show the subtitle track as it is"
            ) {
                SubtitleSettings.setEnabled(this, !enabled)
            }
        )

        if (!enabled) {
            setContentView(page(body))
            return
        }

        val engine = SubtitleSettings.engine(this)
        body.stack(
            choiceRow("Engine", engine.label, engine.caption) {
                choose("Engine", TranslationEngine.entries, engine, { it.label }) {
                    SubtitleSettings.setEngine(this, it)
                }
            }
        )

        val source = SubtitleLanguages.find(SubtitleSettings.sourceLanguage(this))
        body.stack(
            choiceRow("Translate from", source?.label ?: "English", "The language the track is in") {
                chooseLanguage("Translate from", SubtitleSettings.sourceLanguage(this)) {
                    SubtitleSettings.setSourceLanguage(this, it)
                }
            }
        )

        val target = SubtitleLanguages.find(SubtitleSettings.targetLanguage(this))
        body.stack(
            choiceRow("Translate to", target?.label ?: "English", "The language to read in") {
                chooseLanguage("Translate to", SubtitleSettings.targetLanguage(this)) {
                    SubtitleSettings.setTargetLanguage(this, it)
                }
            }
        )

        val showOriginal = SubtitleSettings.showOriginal(this)
        body.stack(
            toggleRow(
                title = "Show original too",
                on = showOriginal,
                onCaption = "Keep the original line under the translation",
                offCaption = "Only the translation"
            ) {
                SubtitleSettings.setShowOriginal(this, !showOriginal)
            }
        )

        if (SubtitleSettings.sourceLanguage(this)
                .equals(SubtitleSettings.targetLanguage(this), ignoreCase = true)
        ) {
            body.stack(
                hintRow("Source and target are the same language, so nothing is translated.")
            )
        }

        // The model section is Opus-MT's alone: ML Kit manages its own compact models through Play
        // Services and has nothing for this screen to download or free.
        if (engine == TranslationEngine.OPUS_MT) renderOpusMtModels(body)

        setContentView(page(body))
    }

    private fun renderOpusMtModels(body: LinearLayout) {
        body.stack(AutoBridgeDesign.sectionLabel(this, "Opus-MT models"), gap = 2)

        val wifiOnly = SubtitleSettings.downloadOnWifiOnly(this)
        body.stack(
            toggleRow(
                title = "Download on Wi-Fi only",
                on = wifiOnly,
                onCaption = "Models download only on an unmetered network",
                offCaption = "Models download on any connection"
            ) {
                SubtitleSettings.setDownloadOnWifiOnly(this, !wifiOnly)
            }
        )

        val model = OpusMtCatalog.model(
            SubtitleSettings.sourceLanguage(this),
            SubtitleSettings.targetLanguage(this)
        )
        if (model != null) {
            val installed = OpusMtModelStore.isInstalled(this, model)
            val availabilityNote = if (model.availability == OpusMtAvailability.UNVERIFIED) {
                "This pair is not one of the verified ones - the download may not exist."
            } else {
                "About ${OpusMtModelLayout.formatSize(OpusMtCatalog.APPROXIMATE_BYTES)} to download"
            }
            body.stack(
                choiceRow(
                    title = model.label,
                    value = when {
                        downloading -> "Downloading…"
                        installed -> "Installed"
                        else -> "Download"
                    },
                    caption = if (installed) "On this device" else availabilityNote
                ) {
                    if (!downloading && !installed) startDownload(model.source, model.target)
                }
            )
        }

        val onDisk = OpusMtModelStore.installed(this)
        if (onDisk.isNotEmpty()) {
            val total = onDisk.sumOf { it.bytes }
            onDisk.forEach { entry ->
                body.stack(
                    choiceRow(
                        title = entry.label,
                        value = OpusMtModelLayout.formatSize(entry.bytes),
                        caption = "Tap to remove"
                    ) {
                        confirmDelete(entry.id, entry.label)
                    }
                )
            }
            if (onDisk.size > 1) {
                body.stack(
                    hintRow(
                        "Free up space keeps the pair in use and removes the rest " +
                            "(${OpusMtModelLayout.formatSize(total)} total)."
                    )
                )
                body.stack(
                    toggleRow(
                        title = "Free up space",
                        on = false,
                        onCaption = "",
                        offCaption = "Delete every model except the current pair"
                    ) {
                        freeUpSpace()
                    }
                )
            }
        }
    }

    private fun startDownload(sourceCode: String, targetCode: String) {
        val model = OpusMtCatalog.model(sourceCode, targetCode) ?: return
        if (!OpusMtModelStore.isDownloadAllowed(this, SubtitleSettings.downloadOnWifiOnly(this))) {
            toast("Not downloading: the network policy forbids it right now")
            return
        }
        downloading = true
        render()
        thread(name = "opus-mt-download") {
            val result = runCatching { OpusMtModelStore.download(applicationContext, model) }
            downloading = false
            runOnUiThread {
                result.onFailure { error ->
                    val message = if (error is FileNotFoundException) {
                        "No published model for ${model.label}"
                    } else {
                        "Download failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                    StructuredLog.w("SUBTITLE", "opus-mt download from settings failed: ${error.message}")
                    toast(message)
                }
                render()
            }
        }
    }

    private fun confirmDelete(id: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove $label?")
            .setMessage("The model is deleted from this device and has to be downloaded again to use.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                thread(name = "opus-mt-delete") {
                    OpusMtModelStore.delete(applicationContext, id)
                    runOnUiThread { render() }
                }
            }
            .show()
    }

    private fun freeUpSpace() {
        val keep = OpusMtCatalog.model(
            SubtitleSettings.sourceLanguage(this),
            SubtitleSettings.targetLanguage(this)
        )?.id
        thread(name = "opus-mt-cleanup") {
            val freed = OpusMtModelStore.deleteOthers(applicationContext, setOfNotNull(keep))
            runOnUiThread {
                if (freed > 0) toast("Freed ${OpusMtModelLayout.formatSize(freed)}")
                render()
            }
        }
    }

    private fun chooseLanguage(title: String, current: String, onPick: (String) -> Unit) {
        choose(title, SubtitleLanguages.all, SubtitleLanguages.find(current), { it?.label ?: "" }) { picked ->
            picked?.let { onPick(it.code) }
        }
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()

    private fun page(body: LinearLayout): View = AutoBridgeDesign.page(
        context = this,
        header = AutoBridgeDesign.header(
            context = this,
            title = "Subtitles",
            subtitle = "On-device translation",
            onBack = { finish() }
        ),
        body = body
    )

    private fun toggleRow(
        title: String,
        on: Boolean,
        onCaption: String,
        offCaption: String,
        onToggle: () -> Unit
    ): View = AutoBridgeDesign.contentRow(
        context = this,
        title = title,
        subtitle = if (on) onCaption else offCaption,
        accent = if (on) accent else AutoBridgeDesign.TEXT_MUTED,
        badgeText = if (on) "ON" else "OFF",
        onClick = {
            onToggle()
            render()
        }
    )

    private fun hintRow(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(AutoBridgeDesign.TEXT_MUTED)
        setPadding(dp(14), dp(6), dp(14), dp(6))
    }

    /** Mirror of [VideoSettingsActivity.choiceRow]; kept local for the same reasons noted there. */
    private fun choiceRow(
        title: String,
        value: String,
        caption: String,
        onClick: () -> Unit
    ): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = AutoBridgeDesign.tappable(this@SubtitleSettingsActivity, AutoBridgeDesign.SURFACE, 16, accent)
        isClickable = true
        isFocusable = true
        contentDescription = "$title, $value"
        setPadding(dp(14), dp(12), dp(10), dp(12))

        val labels = LinearLayout(this@SubtitleSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        labels.addView(TextView(this@SubtitleSettingsActivity).apply {
            this.text = title
            textSize = 15f
            setTextColor(AutoBridgeDesign.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        labels.addView(TextView(this@SubtitleSettingsActivity).apply {
            this.text = caption
            textSize = 12f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))

        addView(TextView(this@SubtitleSettingsActivity).apply {
            this.text = value
            textSize = 14f
            setTextColor(accent)
            gravity = Gravity.END
            maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(-2, -2))
        addView(TextView(this@SubtitleSettingsActivity).apply {
            this.text = "›"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), -2))

        setOnClickListener { onClick() }
    }

    private fun <T> choose(
        title: String,
        options: List<T>,
        current: T,
        label: (T) -> String,
        onPick: (T) -> Unit
    ) {
        val labels = options.map { option ->
            if (option == current) "● " + label(option) else "   " + label(option)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, index ->
                onPick(options[index])
                render()
            }
            .show()
    }
}
