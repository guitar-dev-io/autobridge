package dev.autobridge.duoscreen.system

/**
 * Where Duo Screen gets the privileges it cannot have as an app; see [DuoScreenOps].
 *
 * [SHIZUKU] lends the shell UID and is the only one that can create *trusted* pane displays, which
 * keep composing after the phone's power button. [ROOT] needs no Shizuku at all — a helper started
 * with `su` does the launches, touch and keyboard — but its panes are ordinary app displays, which
 * stop when the phone really sleeps (unless Shizuku also happens to be granted).
 */
enum class DuoScreenBackend { SHIZUKU, ROOT }
