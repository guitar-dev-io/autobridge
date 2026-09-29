package dev.autobridge.youtube

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * Settings for the YouTube add-ons.
 *
 * Both features are off until switched on here: SponsorBlock changes what is played and talks to a
 * third-party service, and forcing the highest quality overrides a choice the site made about the
 * connection. The category list stays visible while SponsorBlock is off — greyed rather than
 * hidden — so it is obvious what turning it on will do before it is turned on.
 */
class YouTubeSettingsActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, YouTubeSettingsActivity::class.java)
    }

    private val accent = AutoBridgeDesign.ACCENT_VIDEO

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
                    subtitle = if (sponsorOn) "SponsorBlock on" else "Add-ons off",
                    onBack = { finish() }
                ),
                body = body
            )
        )
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
