package dev.autobridge.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.core.consent.PermissionDisclosure
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.SettingsUi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Settings &gt; Voice Recognition &gt; Test Voice Recognition.
 *
 * Speak, read back what Whisper heard, what the command parser made of it, and what it cost:
 * audio length, processing time, real-time factor, threads, model load time and memory. Every
 * result of this visit stays in a table at the bottom, so switching model here (tiny-q8_0,
 * base-q8_0, small-q5_1...) and saying the same sentence again compares them on this phone.
 *
 * Nothing is executed from this screen: it is for measuring.
 */
class VoiceTestActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, VoiceTestActivity::class.java)
        private const val REQUEST_MIC = 4201
    }

    private val accent = AutoBridgeDesign.ACCENT_SYSTEM
    private val scope = MainScope()
    private lateinit var recognizer: VoiceRecognizer
    private val history = mutableListOf<Transcription>()

    private lateinit var status: TextView
    private lateinit var level: View
    private lateinit var transcript: TextView
    private lateinit var parsed: TextView
    private lateinit var benchmark: LinearLayout
    private lateinit var historyTable: LinearLayout
    private lateinit var button: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recognizer = VoiceRecognizer(this, scope)
        build()
        scope.launch { recognizer.state.collect(::show) }
    }

    override fun onStop() {
        // Leaving the screen (or the app) mid-sentence releases the microphone.
        recognizer.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build() {
        val body = AutoBridgeDesign.body(this)
        val store = VoiceRuntime.modelStore(this)
        val installed = store.getInstalledModels()
        val selected = store.getSelectedModel()

        body.stack(
            SettingsUi.card(this, listOf(
                SettingsUi.valueRow(this, getString(R.string.voice_bench_model), selected?.displayName ?: getString(R.string.voice_model_none)) {
                    if (installed.size > 1 && !recognizer.isActive) chooseModel(installed, selected)
                    else if (installed.isEmpty()) startActivity(VoiceSettingsActivity.intent(this))
                }
            )),
            gap = 12
        )

        button = AutoBridgeDesign.pill(this, getString(R.string.voice_test_start), primary = true, accent = accent) {
            onButton()
        } as TextView
        button.textSize = 16f
        body.stack(button, gap = 10)

        status = text(getString(R.string.voice_test_idle), 14f, AutoBridgeDesign.TEXT_MUTED)
        body.stack(status, gap = 4)
        level = View(this).apply {
            background = AutoBridgeDesign.surface(this@VoiceTestActivity, accent, 3, accent)
            pivotX = 0f
            scaleX = 0f
        }
        body.addView(level, LinearLayout.LayoutParams(-1, dp(6)).apply { bottomMargin = dp(8) })
        body.stack(text(getString(R.string.voice_test_example), 12.5f, AutoBridgeDesign.TEXT_MUTED), gap = 14)

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.voice_test_transcript)), gap = 2)
        transcript = text("—", 20f, AutoBridgeDesign.TEXT, bold = true).apply {
            background = AutoBridgeDesign.surface(this@VoiceTestActivity, AutoBridgeDesign.SURFACE, 16)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
        }
        body.stack(transcript, gap = 12)

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.voice_test_command)), gap = 2)
        parsed = text("—", 13f, AutoBridgeDesign.TEXT_MUTED).apply {
            typeface = Typeface.MONOSPACE
            background = AutoBridgeDesign.surface(this@VoiceTestActivity, AutoBridgeDesign.SURFACE, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        body.stack(parsed, gap = 12)

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.voice_test_benchmark)), gap = 2)
        benchmark = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.stack(benchmark, gap = 12)

        historyTable = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.stack(historyTable, gap = 12)
        renderHistory()

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.voice_test_title),
                    subtitle = getString(R.string.voice_settings_title),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun onButton() {
        when (recognizer.state.value) {
            is VoiceRecognizer.State.Listening -> recognizer.finish()
            is VoiceRecognizer.State.Transcribing -> Unit
            else -> startListening()
        }
    }

    private fun startListening() {
        if (VoiceRuntime.modelStore(this).getSelectedModel() == null) {
            startActivity(VoiceSettingsActivity.intent(this))
            return
        }
        if (recognizer.hasMicPermission()) {
            recognizer.start()
            return
        }
        PermissionDisclosure.show(this, R.string.mic_disclosure_title, R.string.voice_mic_disclosure_body) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recognizer.start()
        else status.text = VoiceMessages.error(this, VoiceError.PERMISSION_DENIED)
    }

    private fun show(state: VoiceRecognizer.State) {
        when (state) {
            VoiceRecognizer.State.Idle -> {
                button.text = getString(R.string.voice_test_start)
                status.text = getString(R.string.voice_test_idle)
                level.scaleX = 0f
            }
            is VoiceRecognizer.State.Listening -> {
                button.text = getString(R.string.voice_test_stop)
                status.text = getString(R.string.voice_test_listening) + "  " +
                    VoiceFormat.seconds(state.elapsedMs) + " s"
                // -60 dBFS (quiet room) .. -10 dBFS (loud speech) across the bar.
                level.scaleX = ((state.levelDb + 60.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
            }
            is VoiceRecognizer.State.Transcribing -> {
                button.text = getString(R.string.voice_test_transcribing)
                status.text = getString(R.string.voice_test_transcribing) + "  " +
                    VoiceFormat.seconds(state.audioMs) + " s"
                level.scaleX = 0f
            }
            is VoiceRecognizer.State.Done -> {
                button.text = getString(R.string.voice_test_start)
                status.text = ""
                transcript.text = state.text
                parsed.text = describe(VoiceCommandParser.parse(state.text))
                showBenchmark(state.transcription)
                history.add(0, state.transcription)
                renderHistory()
            }
            is VoiceRecognizer.State.Failed -> {
                button.text = getString(R.string.voice_test_start)
                status.text = VoiceMessages.error(this, state.error)
                level.scaleX = 0f
            }
        }
    }

    private fun showBenchmark(result: Transcription) {
        benchmark.removeAllViews()
        val engineState = VoiceRuntime.engine(this).state.value as? WhisperEngine.State.Ready
        val rows = buildList {
            add(getString(R.string.voice_bench_model) to result.modelId)
            add(getString(R.string.voice_bench_audio) to VoiceFormat.seconds(result.audioMs) + " s")
            add(getString(R.string.voice_bench_processing) to VoiceFormat.seconds(result.processingMs) + " s")
            add(getString(R.string.voice_bench_rtf) to VoiceFormat.ratio(result.realTimeFactor))
            add(getString(R.string.voice_bench_stages) to "${result.encodeMs.toLong()} / ${result.decodeMs.toLong()} ms")
            add(getString(R.string.voice_bench_threads) to result.threads.toString())
            add(
                getString(R.string.voice_bench_load) to
                    if (result.modelLoadMs > 0) VoiceFormat.seconds(result.modelLoadMs) + " s"
                    else getString(R.string.voice_bench_load_cached)
            )
            engineState?.memoryBytes?.takeIf { it > 0 }?.let { add(getString(R.string.voice_bench_memory) to VoiceFormat.size(it)) }
            if (result.language.isNotBlank()) add(getString(R.string.voice_bench_language) to result.language)
            NativeWhisperBackend(this@VoiceTestActivity).systemInfo().takeIf { it.isNotBlank() }?.let {
                add(getString(R.string.voice_bench_cpu) to it.trim())
            }
        }
        benchmark.addView(SettingsUi.card(this, rows.map { (label, value) -> keyValue(label, value) }))
    }

    /** One line per result this visit: model, audio, processing time, RTF - newest first. */
    private fun renderHistory() {
        historyTable.removeAllViews()
        if (history.size < 2) return
        historyTable.addView(
            SettingsUi.card(this, history.map { result ->
                keyValue(
                    result.modelId,
                    "${VoiceFormat.seconds(result.audioMs)} s → ${VoiceFormat.seconds(result.processingMs)} s · " +
                        "RTF ${VoiceFormat.ratio(result.realTimeFactor)}"
                )
            })
        )
    }

    private fun chooseModel(installed: List<WhisperModelInfo>, selected: WhisperModelInfo?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_model_title)
            .setSingleChoiceItems(
                installed.map { it.displayName }.toTypedArray(),
                installed.indexOfFirst { it.id == selected?.id }
            ) { dialog, index ->
                VoiceRuntime.modelStore(this).selectModel(installed[index].id)
                dialog.dismiss()
                build()
                scope.launch { show(recognizer.state.value) }
            }
            .show()
    }

    private fun describe(command: VoiceCommand): String = buildString {
        append("action: ").append(command.action)
        command.target?.let { append("\ntarget: ").append(it) }
        command.query?.let { append("\nquery: ").append(it) }
        command.url?.let { append("\nurl: ").append(it) }
        append("\nconfidence: ").append(VoiceFormat.ratio(command.confidence.toDouble()))
        when (val validation = CommandValidator.validate(command)) {
            is Validation.Valid -> validation.command.url?.let { append("\nopens: ").append(it) }
            is Validation.Rejected -> append("\nrejected: ").append(validation.reason)
        }
    }

    private fun keyValue(label: String, value: String): View = LinearLayout(this).apply {
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(text(label, 13.5f, AutoBridgeDesign.TEXT_MUTED), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, 13.5f, AutoBridgeDesign.TEXT, bold = true).apply {
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }, LinearLayout.LayoutParams(0, -2, 1.4f))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

/** The user-facing sentence for each [VoiceError], shared by every voice surface. */
object VoiceMessages {
    fun error(context: Context, error: VoiceError): String = context.getString(
        when (error) {
            VoiceError.PERMISSION_DENIED -> R.string.voice_error_needs_mic_permission
            VoiceError.NO_MODEL -> R.string.voice_err_no_model
            VoiceError.UNSUPPORTED_DEVICE -> R.string.voice_unsupported
            VoiceError.MODEL_LOAD_FAILED -> R.string.voice_err_load
            VoiceError.RECORDER_FAILED -> R.string.voice_error_start_failed
            VoiceError.MIC_BUSY -> R.string.voice_error_mic_busy
            VoiceError.INTERRUPTED -> R.string.voice_error_interrupted
            VoiceError.NOTHING_HEARD -> R.string.voice_error_nothing_heard
            VoiceError.TRANSCRIPTION_FAILED -> R.string.voice_err_transcribe
        }
    )
}
