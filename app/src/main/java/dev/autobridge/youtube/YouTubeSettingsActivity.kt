package dev.autobridge.youtube

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * Settings for the YouTube add-ons.
 *
 * Every feature is off until switched on here: SponsorBlock changes what is played and talks to a
 * third-party service, forcing the highest quality overrides a choice the site made about the
 * connection, and skipping ads is something YouTube looks for and may answer with an interstitial of
 * its own. The category list stays visible while SponsorBlock is off — greyed rather than hidden —
 * so it is obvious what turning it on will do before it is turned on.
 */
class YouTubeSettingsActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, YouTubeSettingsActivity::class.java)
    }

    private val accent = AutoBridgeDesign.ACCENT_VIDEO

    /** Applies the Settings &gt; Language choice; see [AppLocale.rebase]. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.rebase(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val body = AutoBridgeDesign.body(this)
        val sponsorOn = YouTubeSettings.sponsorBlockEnabled(this)

        body.stack(AutoBridgeDesign.sectionLabel(this, "Playback"), gap = 2)
        body.stack(
            toggleRow(
                title = "Auto highest quality",
                on = YouTubeSettings.autoHighestQuality(this),
                onCaption = "Picks the best stream the player offers",
                offCaption = "YouTube chooses, as usual"
            ) {
                YouTubeSettings.setAutoHighestQuality(this, !YouTubeSettings.autoHighestQuality(this))
                render()
            }
        )

        body.stack(AutoBridgeDesign.sectionLabel(this, "Ads"), gap = 12)
        body.stack(
            toggleRow(
                title = "Skip video ads",
                on = YouTubeSettings.adSkipEnabled(this),
                onCaption = "Presses Skip, or seeks past an unskippable ad",
                offCaption = "Ads play as YouTube sends them"
            ) {
                YouTubeSettings.setAdSkipEnabled(this, !YouTubeSettings.adSkipEnabled(this))
                render()
            }
        )

        body.stack(AutoBridgeDesign.sectionLabel(this, "SponsorBlock"), gap = 12)
        body.stack(
            toggleRow(
                title = "Skip crowd-sourced segments",
                on = sponsorOn,
                onCaption = "Looks up segments at sponsor.ajay.app",
                offCaption = "No lookups, nothing skipped"
            ) {
                YouTubeSettings.setSponsorBlockEnabled(this, !sponsorOn)
                render()
            }
        )

        body.stack(
            AutoBridgeDesign.sectionLabel(
                this,
                if (sponsorOn) "Categories" else "Categories (SponsorBlock is off)"
            ),
            gap = 12
        )
        SponsorCategory.entries.forEach { category ->
            val enabled = YouTubeSettings.isCategoryEnabled(this, category)
            body.stack(
                toggleRow(
                    title = category.label,
                    on = enabled && sponsorOn,
                    onCaption = category.caption,
                    offCaption = category.caption,
                    dimmed = !sponsorOn
                ) {
                    YouTubeSettings.setCategoryEnabled(this, category, !enabled)
                    render()
                }
            )
        }

        body.stack(
            AutoBridgeDesign.emptyState(
                context = this,
                title = "What skipping ads can and cannot do",
                message = "A pre-roll is served from the same address as the video itself, so it " +
                    "cannot be blocked without blocking playback. It is ended from inside the " +
                    "page instead, which means it may flash up for a moment first. YouTube " +
                    "detects ad skipping and may ask you to turn it off. Banner ads elsewhere on " +
                    "the web are handled by Block ads and trackers in Browser settings.",
                accent = accent
            ),
            gap = 16
        )

        body.stack(
            AutoBridgeDesign.emptyState(
                context = this,
                title = "Where the data comes from",
                message = "Segments are submitted by SponsorBlock users. A lookup sends only a " +
                    "four-character hash of the video id, so the service is told a bucket of " +
                    "videos rather than which one is playing.",
                accent = accent
            ),
            gap = 16
        )

        setContentView(
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = "YouTube",
                    subtitle = addOnSummary(),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /** Names what is actually on, so the header is not "Add-ons off" while ad skipping is on. */
    private fun addOnSummary(): String {
        val on = buildList {
            if (YouTubeSettings.adSkipEnabled(this@YouTubeSettingsActivity)) add("Ad skip")
            if (YouTubeSettings.sponsorBlockEnabled(this@YouTubeSettingsActivity)) add("SponsorBlock")
            if (YouTubeSettings.autoHighestQuality(this@YouTubeSettingsActivity)) add("Auto quality")
        }
        return if (on.isEmpty()) "Add-ons off" else on.joinToString(" · ") + " on"
    }

    /**
     * A row that reads as a switch. The design language has no switch widget, and adding one for
     * this screen alone would make it the only page in the app that looks like a system dialog.
     */
    private fun toggleRow(
        title: String,
        on: Boolean,
        onCaption: String,
        offCaption: String,
        dimmed: Boolean = false,
        onClick: () -> Unit
    ): View = AutoBridgeDesign.contentRow(
        context = this,
        title = title,
        subtitle = if (on) onCaption else offCaption,
        // The badge takes the row's accent, so an "OFF" drawn in the section colour reads as a
        // warning rather than as a switch that is simply not on.
        accent = if (on && !dimmed) accent else AutoBridgeDesign.TEXT_MUTED,
        badgeText = if (on) "ON" else "OFF",
        trailing = null,
        onClick = onClick
    )
}
