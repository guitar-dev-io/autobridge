package dev.autobridge.core.consent

import android.app.Activity
import android.app.AlertDialog
import androidx.annotation.StringRes
import dev.autobridge.R

/**
 * The in-app explanation that precedes a sensitive permission.
 *
 * Play requires an app to say what it will do with a sensitive permission *before* the system
 * prompt - for the accessibility service, the microphone, and location, the prompt alone carries no
 * purpose and a user who reads it carefully should decline. This is that explanation, as one dialog
 * with "Continue" wired to the request and a dismissal that does nothing at all.
 *
 * Deliberately not remembered. Showing it again on the next attempt costs one tap and keeps the
 * promise accurate if the permission was revoked in between; a "don't show again" flag is the kind
 * of thing that later makes a grant happen with no disclosure in sight.
 *
 * The car surface cannot show an Activity dialog - see `CarDisclosureScreen` for that side. The
 * copy lives in the `res/values` string files under the `_disclosure_` names, and the Play Console
 * declarations they correspond to are listed in PLAY_DECLARATIONS.md.
 */
object PermissionDisclosure {

    /**
     * Shows the explanation, calling [onContinue] only if the user chooses to go on.
     *
     * Does nothing when [activity] is finishing, in which case [onContinue] is not called either:
     * the grant must never happen without the text having been on screen.
     */
    fun show(
        activity: Activity,
        @StringRes title: Int,
        @StringRes body: Int,
        onContinue: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.disclosure_continue) { _, _ -> onContinue() }
            .setNegativeButton(R.string.disclosure_not_now, null)
            .show()
    }
}
