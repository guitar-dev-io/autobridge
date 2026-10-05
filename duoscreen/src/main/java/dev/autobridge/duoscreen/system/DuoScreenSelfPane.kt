package dev.autobridge.duoscreen.system

/**
 * AutoBridge itself as a pane target.
 *
 * This used to be refused outright. The refusal came out of the `-91`/`aInfo is null` hunt, where
 * pane 0 happened to hold our own package *and* the launch was still going out as an implicit
 * MAIN/LAUNCHER intent; the implicit intent was the actual cause, and the self-target was written
 * off with it. Launching our own app onto a pane display is ordinary — it is what
 * `am start --display N` does — so what is left here is the two things it genuinely needs:
 *
 * - **A specific activity.** Resolving our launcher activity yields `MainActivity`, the phone's
 *   home UI: portrait-shaped, not resizeable, nothing a car pane wants. [ACTIVITY] names the one
 *   screen of ours built to be a real window on a car-sized display — exported, resizeable,
 *   `gravity="fill"`, hardware-accelerated WebView (see its comment in the manifest). The rest of
 *   our activities are `exported="false"`, so the shell-UID launch could not start them anyway.
 * - **Its own task.** Our app is normally already running on the phone, and a plain
 *   `FLAG_ACTIVITY_NEW_TASK` launch would *move* that task onto the pane display rather than make
 *   a second instance — the pane would fill and the phone would go blank. See
 *   [DuoScreenPrivilegedOps.launchOnDisplay]'s `allowSecondInstance`.
 */
object DuoScreenSelfPane {
    /** Fully-qualified name of the one activity of ours that belongs in a pane. */
    const val ACTIVITY = "dev.autobridge.browser.BrowserActivity"

    fun isSelf(packageName: String, ownPackageName: String): Boolean = packageName == ownPackageName

    /**
     * The activity to launch for [packageName], or null when it is not ours and the caller should
     * resolve its launcher activity as usual.
     */
    fun activityOrNull(packageName: String, ownPackageName: String): String? =
        ACTIVITY.takeIf { isSelf(packageName, ownPackageName) }
}
