package dev.autobridge.safety

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.autobridge.logging.StructuredLog

/**
 * Single control surface for the runtime safety bypass, driven by broadcast intents.
 *
 * Two callers share it:
 *  - the phone Settings toggle and the notification action buttons fire these intents in-process;
 *  - a developer can fire the same intents from a terminal:
 *
 * ```
 * adb shell am broadcast -a dev.autobridge.BYPASS_ON  -n dev.autobridge/.safety.BypassControlReceiver
 * adb shell am broadcast -a dev.autobridge.BYPASS_OFF -n dev.autobridge/.safety.BypassControlReceiver
 * adb shell am broadcast -a dev.autobridge.BYPASS_TOGGLE -n dev.autobridge/.safety.BypassControlReceiver
 * # optional scope: PARKED_ONLY | PARKED_AND_MODE
 * adb shell am broadcast -a dev.autobridge.BYPASS_ON -e scope PARKED_ONLY -n dev.autobridge/.safety.BypassControlReceiver
 * ```
 *
 * The receiver is the only writer the UI and the shell both go through, so a change made from
 * either path persists and raises the same notification. [BypassPolicyStore.init] must have run
 * (it does, from the Application) before a broadcast arrives, or the write cannot be persisted.
 */
class BypassControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The store normally comes up in Application.onCreate; a manifest receiver can still be the
        // first thing to run in a cold-started process, so make sure it is loaded.
        BypassPolicyStore.init(context)

        intent.getStringExtra(EXTRA_SCOPE)?.let { raw ->
            BypassPolicyStore.Scope.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?.let { BypassPolicyStore.setScope(it) }
        }

        when (intent.action) {
            ACTION_ON -> BypassPolicyStore.setEnabled(true)
            ACTION_OFF -> BypassPolicyStore.setEnabled(false)
            ACTION_TOGGLE -> BypassPolicyStore.toggle()
            else -> {
                StructuredLog.w(TAG, "ignored unknown action: ${intent.action}")
                return
            }
        }
        StructuredLog.i(TAG, "action=${intent.action} -> enabled=${BypassPolicyStore.enabled}")
    }

    companion object {
        private const val TAG = "BypassControl"

        const val ACTION_ON = "dev.autobridge.BYPASS_ON"
        const val ACTION_OFF = "dev.autobridge.BYPASS_OFF"
        const val ACTION_TOGGLE = "dev.autobridge.BYPASS_TOGGLE"
        const val EXTRA_SCOPE = "scope"
    }
}
