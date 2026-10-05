package dev.autobridge.display

import dev.autobridge.settings.AutoDimDelay

/**
 * When a non-mirror car session (Duo Screen) should darken the phone, given what the user chose.
 *
 * Its own object, and pure, because it is the one place the user's choice is *completed* rather
 * than obeyed, and that deserves a test rather than a comment: privileged panel-off switched on
 * with the delay left [AutoDimDelay.OFF] has nothing to trigger it, so the session would hold the
 * phone bright for the whole drive — the opposite of what switching it on asked for.
 *
 * The mirror route does not use this. There, a phone that sleeps means a black car screen, which
 * is a worse session but still a session, so "off" can be taken at face value.
 */
internal object CarSessionDimPolicy {

    /**
     * Long enough to arrange the panes before the glass goes dark, short enough that the phone is
     * not left bright for the rest of the drive.
     */
    val FALLBACK: AutoDimDelay = AutoDimDelay.SECONDS_30

    fun delay(configured: AutoDimDelay, panelOffEnabled: Boolean): AutoDimDelay =
        if (configured == AutoDimDelay.OFF && panelOffEnabled) FALLBACK else configured
}
