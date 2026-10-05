package dev.autobridge.projection

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.autobridge.duoscreen.DuoScreenHost
import dev.autobridge.duoscreen.R
import dev.autobridge.duoscreen.layout.DuoScreenPreset
import dev.autobridge.duoscreen.layout.DuoScreenStore
import dev.autobridge.duoscreen.system.DuoScreenSelfPane
import dev.autobridge.i18n.AppLocale
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * Phone-side setup for Duo Screen: which app each pane runs, how many panes there are, and whether
 * the privileged backend it needs is actually reachable.
 *
 * Every change is written to [DuoScreenStore] and then pushed at a live session through
 * [DuoScreenHost], so picking a different app for a pane swaps that pane on the car display while
 * the driver is looking at it. With no session running the store is all there is, and the choice
 * takes effect at the next connection; [applied] is what tells the two cases apart out loud.
 */
class DuoScreenSettingsActivity : Activity() {
    companion object {
        fun intent(context: Context): Intent = Intent(context, DuoScreenSettingsActivity::class.java)
    }

    private val accent = AutoBridgeDesign.ACCENT_SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val body = AutoBridgeDesign.body(this)
        val shizukuReady = ShizukuInputBackend.isPermissionGranted

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.duo_screen_title)), gap = 2)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(
                    if (shizukuReady) R.string.duo_screen_shizuku_ready
                    else R.string.duo_screen_shizuku_missing
                ),
                subtitle = getString(R.string.duo_screen_shizuku_hint),
                accent = if (shizukuReady) accent else AutoBridgeDesign.DANGER,
                badgeText = if (shizukuReady) "✓" else "!"
            ) {
                if (!shizukuReady) ShizukuInputBackend.requestPermission()
                render()
            }
        )

        val paneCount = DuoScreenStore.paneCount(this)
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.duo_screen_panes_section)), gap = 2)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.duo_screen_pane_count),
                subtitle = paneCount.toString(),
                accent = accent,
                badgeText = paneCount.toString()
            ) { cyclePaneCount(paneCount) }
        )

        val packages = DuoScreenStore.packages(this)
        repeat(paneCount) { index ->
            val chosen = packages.getOrNull(index)
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = getString(R.string.duo_screen_pane_label, index + 1),
                    subtitle = chosen?.let(::paneLabel) ?: getString(R.string.duo_screen_pane_empty),
                    accent = accent,
                    badgeText = "${index + 1}"
                ) { pickAppFor(index) }
            )
        }

        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.duo_screen_layout_section)), gap = 2)
        val preset = DuoScreenStore.preset(this)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.duo_screen_preset),
                subtitle = preset.label(this),
                accent = accent,
                badgeText = preset.glyph
            ) { pickPreset() }
        )
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.duo_screen_reset_layout),
                subtitle = getString(R.string.duo_screen_reset_layout_hint),
                accent = accent,
                badgeText = "↺"
            ) {
                DuoScreenStore.resetLayout(this)
                // Not applied(): the preset has not changed, so only an explicit re-apply puts the
                // live panes back onto it — that is what "reset the arrangement" means here.
                DuoScreenHost.onLayoutReset()
                Toast.makeText(this, R.string.duo_screen_reset_done, Toast.LENGTH_SHORT).show()
                render()
            }
        )

        // The phone-side way out, for the same reason the car screen has one: a session outlives the
        // car Screen that showed it (DuoScreenHost.KEEP_ALIVE_MS), so the panes and the apps in them
        // can still be holding their displays after the driver has moved on. The car button needs
        // the driver to be looking at Duo Screen; this one does not.
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.duo_screen_session_section)), gap = 2)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.duo_screen_end_session),
                subtitle = getString(R.string.duo_screen_end_session_hint),
                accent = AutoBridgeDesign.DANGER,
                badgeText = "✕"
            ) {
                val ended = DuoScreenHost.release()
                Toast.makeText(
                    this,
                    if (ended) R.string.duo_screen_end_done else R.string.duo_screen_end_none,
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        )

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.duo_screen_title),
                    subtitle = getString(R.string.duo_screen_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    private fun cyclePaneCount(current: Int) {
        val next = if (current >= DuoScreenStore.MAX_PANES) DuoScreenStore.MIN_PANES else current + 1
        // setPaneCount also drops the arrangement, which was built for the old count and cannot
        // describe the new one; the panes are laid out from the preset instead.
        DuoScreenStore.setPaneCount(this, next)
        applied()
        render()
    }

    /**
     * Hands the change to a running session and says which way it went. Called after every write,
     * because "I changed it and nothing happened" is the same screen either way otherwise.
     */
    private fun applied() {
        val live = DuoScreenHost.onSettingsChanged()
        Toast.makeText(
            this,
            if (live) R.string.duo_screen_applied_now else R.string.duo_screen_applied_next,
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Picking a preset here only records the choice — there is no surface on the phone to lay it
     * out on, so [dev.autobridge.duoscreen.DuoScreenController] builds the rects from it when the head unit connects. In a
     * live session the car screen's own layout button applies one straight away.
     */
    private fun pickPreset() {
        val presets = DuoScreenPreset.entries
        val labels = presets.map { "${it.glyph}  ${it.label(this)}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.duo_screen_preset)
            .setItems(labels.toTypedArray()) { _, which ->
                DuoScreenStore.setPreset(this, presets[which])
                applied()
                render()
            }
            .show()
    }

    /**
     * Launchable apps only, by label. Needs QUERY_ALL_PACKAGES (declared in this flavor's manifest):
     * picking an arbitrary app to run in a pane is the whole feature, and this build never goes to
     * Play, where that permission would have to be justified.
     */
    private fun pickAppFor(paneId: Int) {
        val launchable = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .sortedBy { paneLabel(it).lowercase() }

        val labels = listOf(getString(R.string.duo_screen_clear_app)) + launchable.map(::paneLabel)
        AlertDialog.Builder(this)
            .setTitle(R.string.duo_screen_pick_app)
            // Reading the installed-app list is the one thing here the user cannot see the reason
            // for, so the picker says it. Play would require this justification in the console; the
            // permission never reaches a Play build, but the user still deserves the sentence.
            .setMessage(R.string.duo_screen_pick_app_why)
            .setItems(labels.toTypedArray()) { _, which ->
                val packageName = if (which == 0) null else launchable[which - 1]
                DuoScreenStore.setPackage(this, paneId, packageName)
                applied()
                render()
            }
            .show()
    }

    /**
     * What a pane running [packageName] is called. Our own entry says which screen it opens: a
     * pane runs AutoBridge's car browser, not the phone home the app's icon stands for (see
     * [DuoScreenSelfPane]).
     */
    private fun paneLabel(packageName: String): String =
        if (DuoScreenSelfPane.isSelf(packageName, this.packageName)) {
            getString(R.string.duo_screen_self_pane)
        } else {
            appLabel(packageName)
        }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
