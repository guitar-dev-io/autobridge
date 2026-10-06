package dev.autobridge.projection

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.autobridge.R as AppR
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.duoscreen.DuoScreenHost
import dev.autobridge.duoscreen.R
import dev.autobridge.duoscreen.layout.DuoScreenPreset
import dev.autobridge.duoscreen.layout.DuoScreenStore
import dev.autobridge.duoscreen.system.DuoScreenSelfPane
import dev.autobridge.i18n.AppLocale
import dev.autobridge.input.ShizukuInputBackend
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack
import dev.autobridge.ui.ConnectionStatusText

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

        /** Platform glyph for the "leave this pane empty" cell, so the grid has no gap in it. */
        private const val CLEAR_ICON = android.R.drawable.ic_menu_close_clear_cancel
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

        // Android Auto and Shizuku are two unrelated connections — the car display one and the
        // privileged-touch one — so they get their own section and their own rows rather than
        // being folded into one "connected" line. Android Auto's wording comes from the same
        // ConnectionStatusText Home, Control and Car & Connection use, so "connected" never means
        // something different here than it does there.
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.duo_screen_connection_section)), gap = 2)
        val runtimeStatus = ConnectionStatusText.of(
            RuntimeContextStore.context.value,
            ConnectionStatusText.Labels(
                notConnected = getString(AppR.string.conn_not_connected),
                parked = getString(AppR.string.conn_parked),
                driving = getString(AppR.string.conn_driving),
                checking = getString(AppR.string.conn_checking),
                connectedFormat = { getString(AppR.string.conn_connected_format, it) }
            )
        )
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(AppR.string.control_android_auto),
                subtitle = "${runtimeStatus.summary} — ${getString(R.string.duo_screen_android_auto_hint)}",
                // Same green-when-connected language as the AndroidAutoStatusCard on Home,
                // Control and Car & Connection (AutoBridgeDesign.ACCENT_ONLINE == ComposeTokens.Ok),
                // not this screen's own accent — so "connected" reads the same color everywhere.
                accent = if (runtimeStatus.connected) AutoBridgeDesign.ACCENT_ONLINE else AutoBridgeDesign.TEXT_MUTED,
                badgeText = if (runtimeStatus.connected) "✓" else "○"
            ) {}
        )
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(
                    if (shizukuReady) R.string.duo_screen_shizuku_ready
                    else R.string.duo_screen_shizuku_missing
                ),
                subtitle = getString(R.string.duo_screen_shizuku_hint),
                accent = if (shizukuReady) accent else AutoBridgeDesign.DANGER,
                badgeText = if (shizukuReady) "✓" else "!",
                trailing = "›"
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
                badgeText = paneCount.toString(),
                trailing = "›"
            ) { cyclePaneCount(paneCount) }
        )

        val packages = DuoScreenStore.packages(this)
        repeat(paneCount) { index ->
            val chosen = packages.getOrNull(index)
            val paneNumber = index + 1
            // Lint's StringFormatMatches misreads this exact call as passing a String (it does
            // not: paneNumber is Int, matched by the %1$d in both locales) — a known false
            // positive with this lambda shape, not a real type mismatch.
            @Suppress("StringFormatMatches")
            val paneTitle = getString(R.string.duo_screen_pane_label, paneNumber)
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = paneTitle,
                    subtitle = chosen?.let(::paneLabel) ?: getString(R.string.duo_screen_pane_empty),
                    accent = accent,
                    badgeText = "$paneNumber",
                    trailing = "›"
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
                badgeText = preset.glyph,
                trailing = "›"
            ) { pickPreset() }
        )
        val scale = DuoScreenStore.contentScale(this)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.duo_screen_content_scale),
                subtitle = getString(R.string.duo_screen_content_scale_hint),
                accent = accent,
                badgeText = getString(R.string.duo_screen_content_scale_value, scale),
                trailing = "›"
            ) { pickContentScale() }
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
        // the driver to be looking at Duo Screen; this one does not. Confirmed first, same as any
        // other action on this screen that shuts something down, naming exactly what closes.
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.duo_screen_session_section)), gap = 2)
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.duo_screen_end_session),
                subtitle = getString(R.string.duo_screen_end_session_hint),
                accent = AutoBridgeDesign.DANGER,
                badgeText = "✕"
            ) { confirmEndSession() }
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

    /**
     * Confirms before tearing the session down: the hint text already says what closes (every
     * pane and the apps running in them), so the dialog reuses it rather than writing it twice.
     */
    private fun confirmEndSession() {
        AlertDialog.Builder(this)
            .setTitle(R.string.duo_screen_end_session_confirm_title)
            .setMessage(R.string.duo_screen_end_session_hint)
            .setNegativeButton(R.string.duo_screen_cancel, null)
            .setPositiveButton(R.string.duo_screen_end_session_confirm_action) { _, _ ->
                val ended = DuoScreenHost.release()
                Toast.makeText(
                    this,
                    if (ended) R.string.duo_screen_end_done else R.string.duo_screen_end_none,
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
            .show()
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
     * Density, not zoom — see [DuoScreenStore.CONTENT_SCALES]. A pane's display keeps its pixels
     * and its rect; only how many dp the app inside gets to lay itself out in changes, so a live
     * session takes it as a resize and the driver sees each step on the car display as they pick.
     */
    private fun pickContentScale() {
        val scales = DuoScreenStore.CONTENT_SCALES
        val labels = scales.map { getString(R.string.duo_screen_content_scale_value, it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.duo_screen_content_scale)
            .setItems(labels.toTypedArray()) { _, which ->
                DuoScreenStore.setContentScale(this, scales[which])
                applied()
                render()
            }
            .show()
    }

    /**
     * A grid of icon + name, not a single-column list: a phone has a hundred launchable apps, and
     * finding one of them in a dialog list means scrolling past ninety others reading text. The
     * icon is what the user recognises, and four to a row puts most of the grid on one screen.
     *
     * Launchable apps only, by label. Needs QUERY_ALL_PACKAGES (declared in this flavor's manifest):
     * picking an arbitrary app to run in a pane is the whole feature, and this build never goes to
     * Play, where that permission would have to be justified.
     */
    private fun pickAppFor(paneId: Int) {
        val items = pickerItems()
        val builder = AlertDialog.Builder(this)
        val grid = GridView(builder.context).apply {
            // AUTO_FIT with a cell width rather than a fixed column count: the same dialog opens on
            // a phone in portrait and on a 11" tablet, and the cell is the size that has to stay
            // tappable.
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(100)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            // Padding, and clipped to it: the dialog cuts the grid off at its own height, and
            // without the clip the last row scrolls flush into that edge with its second label
            // line sliced in half.
            setPadding(dp(12), 0, dp(12), dp(12))
            adapter = PaneAppAdapter(builder.context, items)
        }
        val dialog = builder
            // Title AND reason in one custom view, not setTitle + setMessage: AlertController
            // attaches a message to the same content panel the grid goes in and drops the view, so
            // a message would leave the picker with nothing to pick. Carrying the sentence in the
            // title area is what lets the grid survive it.
            .setCustomTitle(pickerHeader(builder.context))
            .setView(grid)
            .create()
        grid.setOnItemClickListener { _, _, position, _ ->
            DuoScreenStore.setPackage(this, paneId, items[position].packageName)
            dialog.dismiss()
            applied()
            render()
        }
        dialog.show()
    }

    /** One cell of the picker. A null [packageName] is the "leave this pane empty" cell. */
    private data class PaneApp(val label: String, val packageName: String?)

    private fun pickerItems(): List<PaneApp> {
        val launchable = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0
        )
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .sortedBy { paneLabel(it).lowercase() }
        // "Leave empty" stays first, where the list had it: it is the one cell that is not an app,
        // and a driver looking for it should not have to hunt through the icons.
        return listOf(PaneApp(getString(R.string.duo_screen_clear_app), null)) +
            launchable.map { PaneApp(paneLabel(it), it) }
    }

    /**
     * Cells for the picker grid.
     *
     * Icons load as a cell scrolls into view and are kept after that: a hundred
     * [android.content.pm.PackageManager.getApplicationIcon] calls before the dialog can appear is
     * a pause the user sees, while the grid itself only ever shows a dozen at a time.
     */
    private inner class PaneAppAdapter(
        private val themed: Context,
        private val items: List<PaneApp>,
    ) : BaseAdapter() {
        private val icons = HashMap<String, Drawable>()

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? LinearLayout ?: newCell()
            val item = items[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(
                item.packageName?.let(::iconFor) ?: themed.getDrawable(CLEAR_ICON)
            )
            (cell.getChildAt(1) as TextView).text = item.label
            return cell
        }

        private fun newCell(): LinearLayout = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            addView(ImageView(themed), LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(
                TextView(themed).apply {
                    textSize = 12f
                    gravity = Gravity.CENTER
                    // Two lines, because a lot of app names do not fit in one at this width and a
                    // name cut to "Google Pl..." is no better than no name.
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(themeColor(themed, android.R.attr.textColorPrimary))
                    setPadding(0, dp(6), 0, 0)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        /** An app uninstalled between opening this dialog and scrolling to it has no icon. */
        private fun iconFor(packageName: String): Drawable? = icons[packageName]
            ?: runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
                ?.also { icons[packageName] = it }
    }

    /**
     * The picker's heading: what to do, then why the app list was read at all.
     *
     * Reading the installed-app list is the one thing on this screen the user cannot see the reason
     * for, so the picker says it. Play would require that justification in the console; the
     * permission never reaches a Play build, but the user still deserves the sentence.
     *
     * [themed] is the builder's own context, so the two labels take the dialog theme's colours
     * rather than the activity's - those differ, and views built from the activity can come out
     * invisible against the dialog's background.
     */
    private fun pickerHeader(themed: Context): View =
        LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
            addView(
                TextView(themed).apply {
                    text = getString(R.string.duo_screen_pick_app)
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(themeColor(themed, android.R.attr.textColorPrimary))
                }
            )
            addView(
                TextView(themed).apply {
                    text = getString(R.string.duo_screen_pick_app_why)
                    textSize = 13f
                    setTextColor(themeColor(themed, android.R.attr.textColorSecondary))
                    setPadding(0, dp(8), 0, 0)
                }
            )
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(themed: Context, attr: Int): Int {
        val value = TypedValue()
        if (!themed.theme.resolveAttribute(attr, value, true)) return AutoBridgeDesign.TEXT
        return if (value.resourceId != 0) themed.getColor(value.resourceId) else value.data
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
