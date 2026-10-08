package dev.autobridge.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.bridge.AutoBridgeSessionManager
import dev.autobridge.bridge.BridgeSource
import dev.autobridge.core.consent.PermissionDisclosure
import dev.autobridge.logging.StructuredLog
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Home microphone with offline Whisper: listen, show what was heard, then run it.
 *
 * Runs through [VoiceRecognizer] (record + transcribe), [VoiceCommandParser] and
 * [CommandValidator], and only then reaches the app's own entry points, which the host Activity
 * supplies as [Actions] - the same browser, Send to Car and navigation the rest of the phone UI
 * uses. A transcript the parser does not recognise goes to [Actions.runAgentCommand], the Agent
 * parser this microphone used before, so nothing it understood is lost.
 */
class PhoneVoiceSession(private val activity: Activity, private val actions: Actions) {

    /** What a recognised command may do on the phone. Implemented by MainActivity. */
    interface Actions {
        fun openUrl(url: String)
        fun openBrowser()
        fun goHome()
        fun goBack()
        /** Opens a home section the way tapping its tile does. */
        fun openSection(section: dev.autobridge.library.HomeSection)
        /** The pre-existing Agent path, for anything the voice grammar does not cover. */
        fun runAgentCommand(text: String)
    }

    private val scope = MainScope()
    private val recognizer = VoiceRecognizer(activity, scope)
    private var dialog: AlertDialog? = null
    private var status: TextView? = null
    private var heard: TextView? = null
    private var watcher: Job? = null
    private var pending: (() -> Unit)? = null
    private var editing: (() -> Unit)? = null

    /** Entry point for the microphone button. */
    fun start() {
        val store = VoiceRuntime.modelStore(activity)
        if (store.getSelectedModel() == null) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.voice_model_required_title)
                .setMessage(R.string.voice_model_required_body)
                .setNegativeButton(R.string.voice_action_cancel, null)
                .setPositiveButton(R.string.voice_home_open_settings) { _, _ ->
                    activity.startActivity(VoiceSettingsActivity.intent(activity))
                }
                .show()
            return
        }
        if (!recognizer.hasMicPermission()) {
            PermissionDisclosure.show(activity, R.string.mic_disclosure_title, R.string.voice_mic_disclosure_body) {
                activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            }
            return
        }
        listen()
    }

    /** Forwarded from the Activity's onRequestPermissionsResult. True when it was ours. */
    fun onPermissionResult(requestCode: Int, granted: Boolean): Boolean {
        if (requestCode != REQUEST_MIC) return false
        if (granted) listen() else toast(VoiceMessages.error(activity, VoiceError.PERMISSION_DENIED))
        return true
    }

    /** The Activity went to the background: release the microphone and close the dialog. */
    fun stop() {
        recognizer.cancel()
        dismiss()
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun listen() {
        showDialog()
        recognizer.start()
        watcher?.cancel()
        watcher = scope.launch { recognizer.state.collect(::render) }
    }

    private fun render(state: VoiceRecognizer.State) {
        val positive = dialog?.getButton(DialogInterface.BUTTON_POSITIVE)
        when (state) {
            VoiceRecognizer.State.Idle -> Unit
            is VoiceRecognizer.State.Listening -> {
                status?.text = activity.getString(R.string.voice_home_listening_body)
                positive?.text = activity.getString(R.string.voice_test_stop)
                positive?.visibility = View.VISIBLE
                pending = { recognizer.finish() }
            }
            is VoiceRecognizer.State.Transcribing -> {
                dialog?.setTitle(R.string.voice_test_transcribing)
                status?.text = activity.getString(R.string.voice_test_transcribing)
                positive?.visibility = View.GONE
                pending = null
            }
            is VoiceRecognizer.State.Done -> onTranscript(state.text)
            is VoiceRecognizer.State.Failed -> {
                dialog?.setTitle(R.string.voice_settings_title)
                status?.text = VoiceMessages.error(activity, state.error)
                positive?.text = activity.getString(R.string.voice_action_retry)
                positive?.visibility = View.VISIBLE
                pending = { recognizer.reset(); listen() }
            }
        }
    }

    private fun onTranscript(text: String) {
        dialog?.setTitle(R.string.voice_test_transcript)
        heard?.text = text
        handle(text, heardAs = text, allowAuto = true)
    }

    /**
     * Parses [text] and runs it, or offers to. [heardAs] is what Whisper wrote, kept so a fix the
     * driver types can be remembered against it.
     *
     * Runs at once only when the command was understood: a transcript nothing recognised used to
     * go straight to the Agent too, and a misheard word then did whatever the Agent made of it
     * before the driver could see the mistake. Now it waits, with Edit beside Run.
     */
    private fun handle(text: String, heardAs: String, allowAuto: Boolean) {
        val command = VoiceCommandParser.parse(text, VoiceShortcutStore.all(activity))
        StructuredLog.i(VoiceRuntime.TAG, "phone voice command ${command.action} ${command.target} conf=${command.confidence}")
        val validation = CommandValidator.validate(command)
        val run: (() -> Unit)? = when (validation) {
            is Validation.Valid -> ({ execute(validation.command) })
            is Validation.Rejected ->
                if (validation.reason == Validation.Reason.UNKNOWN_COMMAND) ({ actions.runAgentCommand(command.text.ifBlank { text }) })
                else null
        }
        val understood = when (validation) {
            is Validation.Valid -> validation.command.autoExecute
            is Validation.Rejected -> validation.reason == Validation.Reason.UNKNOWN_COMMAND &&
                dev.autobridge.agent.AgentCommandParser.parse(text) != null
        }
        val recognised = validation is Validation.Valid || understood
        val auto = allowAuto && VoiceSettings.autoExecute(activity) && understood
        val positive = dialog?.getButton(DialogInterface.BUTTON_POSITIVE)
        val neutral = dialog?.getButton(DialogInterface.BUTTON_NEUTRAL)
        when {
            run == null -> {
                status?.text = activity.getString(R.string.voice_home_unsafe)
                positive?.visibility = View.GONE
            }
            auto -> {
                dismiss()
                run()
                return
            }
            !allowAuto -> {
                // A corrected command: the driver already said Run by fixing it.
                dismiss()
                run()
                return
            }
            else -> {
                status?.text = if (recognised) "" else activity.getString(R.string.voice_home_check_words)
                positive?.text = activity.getString(R.string.voice_home_run)
                positive?.visibility = View.VISIBLE
                pending = { dismiss(); run() }
            }
        }
        neutral?.text = activity.getString(R.string.voice_home_edit)
        neutral?.visibility = View.VISIBLE
        editing = { startEditing(heardAs) }
    }

    /**
     * Swaps the transcript for an editable copy. Run executes the fixed words; with "remember"
     * ticked (the default) the fix is saved as one of the driver's commands, so the next time
     * Whisper writes the same thing it means what was typed here ([VoiceShortcut.Kind.COMMAND]).
     */
    private fun startEditing(heardAs: String) {
        val content = heard?.parent as? LinearLayout ?: return
        val field = android.widget.EditText(activity).apply {
            setText(heardAs)
            setSelection(heardAs.length)
            textSize = 19f
            setTextColor(AutoBridgeDesign.TEXT)
            setSingleLine()
        }
        val remember = android.widget.CheckBox(activity).apply {
            isChecked = true
            text = activity.getString(R.string.voice_home_remember_fix)
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
        }
        heard?.let { content.removeView(it) }
        content.addView(field)
        content.addView(remember)
        field.requestFocus()
        status?.text = activity.getString(R.string.voice_home_edit_hint)
        dialog?.getButton(DialogInterface.BUTTON_NEUTRAL)?.visibility = View.GONE
        val positive = dialog?.getButton(DialogInterface.BUTTON_POSITIVE)
        positive?.text = activity.getString(R.string.voice_home_run)
        positive?.visibility = View.VISIBLE
        pending = {
            val fixed = field.text.toString().trim()
            if (fixed.isNotEmpty()) {
                if (remember.isChecked && VoiceShortcutMatcher.key(fixed) != VoiceShortcutMatcher.key(heardAs) &&
                    VoiceShortcutMatcher.key(heardAs).length >= 2
                ) {
                    VoiceShortcutStore.add(activity, VoiceShortcut(heardAs, VoiceShortcut.Kind.COMMAND, fixed))
                    toast(activity.getString(R.string.voice_home_fix_saved, heardAs, fixed))
                }
                handle(fixed, heardAs = heardAs, allowAuto = false)
            }
        }
    }

    private fun execute(command: ValidatedCommand) {
        val url = command.url
        when (command.command.action) {
            VoiceAction.GO_HOME -> actions.goHome()
            VoiceAction.GO_BACK -> actions.goBack()
            VoiceAction.OPEN_SCREEN -> command.command.screen?.let { actions.openSection(it.section) }
            VoiceAction.SEND_TO_CAR -> if (url != null) sendToCar(url)
            VoiceAction.OPEN_APP, VoiceAction.SEARCH, VoiceAction.PLAY, VoiceAction.OPEN_URL ->
                if (url == null) actions.openBrowser() else actions.openUrl(url)
            VoiceAction.UNKNOWN -> Unit
        }
    }

    /** The shared Send to Car path, with the same messages the phone controller shows. */
    private fun sendToCar(url: String) {
        val result = AutoBridgeSessionManager.sendToCar(activity, BridgeSource(url = url, origin = BridgeSource.Origin.PHONE))
        toast(
            when (result) {
                is AutoBridgeSessionManager.SendResult.Opened -> activity.getString(R.string.bridge_controller_sent)
                AutoBridgeSessionManager.SendResult.Pending -> activity.getString(R.string.bridge_controller_queued_for_connect)
                is AutoBridgeSessionManager.SendResult.Refused -> activity.getString(result.error.messageRes)
            }
        )
    }

    private fun showDialog() {
        dismiss()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(22), activity.dp(8), activity.dp(22), 0)
        }
        status = TextView(activity).apply {
            textSize = 14f
            setTextColor(AutoBridgeDesign.TEXT_MUTED)
        }
        heard = TextView(activity).apply {
            textSize = 19f
            setTextColor(AutoBridgeDesign.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, activity.dp(10), 0, 0)
        }
        content.addView(status)
        content.addView(heard)
        val built = AlertDialog.Builder(activity)
            .setTitle(R.string.voice_test_listening)
            .setView(content)
            .setNegativeButton(R.string.voice_action_cancel) { _, _ -> recognizer.cancel() }
            .setPositiveButton(R.string.voice_test_stop, null)
            .setNeutralButton(R.string.voice_home_edit, null)
            .setOnCancelListener { recognizer.cancel() }
            .create()
        built.setOnShowListener {
            // Set here rather than in the builder so a tap does not also dismiss the dialog.
            built.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { pending?.invoke() }
            built.getButton(DialogInterface.BUTTON_NEUTRAL).apply {
                visibility = View.GONE
                setOnClickListener { editing?.invoke() }
            }
        }
        dialog = built
        built.show()
    }

    private fun dismiss() {
        dialog?.let { runCatching { it.dismiss() } }
        dialog = null
        status = null
        heard = null
        pending = null
        editing = null
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQUEST_MIC = 4301
    }
}
