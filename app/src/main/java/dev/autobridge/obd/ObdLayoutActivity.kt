package dev.autobridge.obd

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.autobridge.R
import dev.autobridge.i18n.AppLocale
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AutoBridgeDesign.stack

/**
 * Where the car's gauge pages are arranged: how many there are, the shape of each and which gauge
 * sits in which place. The car screen reads the saved pages every frame, so a change here shows on
 * the head unit straight away, with no reconnect.
 *
 * Every edit goes through [GaugePages], which keeps a page's gauges matched to its layout, so an
 * odd sequence of taps cannot leave the car with a page it cannot draw.
 */
class ObdLayoutActivity : Activity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, ObdLayoutActivity::class.java)
    }

    private val accent get() = AutoBridgeDesign.ACCENT

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.autobridge.ui.AppearanceContext.wrap(AppLocale.rebase(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        dev.autobridge.settings.AppearanceStore.refreshPalette(this)
        super.onCreate(savedInstanceState)
        dev.autobridge.settings.ScreenAppearance.applyWindow(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val pages = ObdSettings.pages(this)
        val body = AutoBridgeDesign.body(this)

        pages.forEachIndexed { index, page ->
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = getString(R.string.obd_layout_page, (index + 1).toString()),
                    subtitle = getString(layoutName(page.layout)) + " • " +
                        page.gauges.joinToString(", ") { getString(GaugeLabels.of(it)) },
                    accent = accent,
                    badgeText = (index + 1).toString(),
                    subtitleMaxLines = 2,
                    onClick = { editPage(index) }
                )
            )
        }
        if (pages.size < GaugePages.MAX_PAGES) {
            body.stack(
                AutoBridgeDesign.contentRow(
                    context = this,
                    title = getString(R.string.obd_layout_add),
                    subtitle = getString(R.string.obd_layout_add_caption, GaugePages.MAX_PAGES.toString()),
                    accent = AutoBridgeDesign.TEXT_MUTED,
                    badgeText = "+",
                    onClick = {
                        save(pages + GaugePages.fit(PageLayout.TILES4, unusedGauges(pages)))
                    }
                )
            )
        }
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.obd_layout_reset),
                subtitle = getString(R.string.obd_layout_reset_caption),
                accent = AutoBridgeDesign.TEXT_MUTED,
                badgeText = "↺",
                onClick = { save(GaugePages.DEFAULT) }
            )
        )

        AutoBridgeDesign.showPage(
            this,
            AutoBridgeDesign.page(
                context = this,
                header = AutoBridgeDesign.header(
                    context = this,
                    title = getString(R.string.obd_layout_title),
                    subtitle = getString(R.string.obd_layout_subtitle),
                    onBack = { finish() }
                ),
                body = body
            )
        )
    }

    /** Gauges no page shows yet, so a new page starts with something new. */
    private fun unusedGauges(pages: List<GaugePage>): List<Gauge> {
        val shown = pages.flatMap { it.gauges }.toSet()
        return Gauge.entries.filter { it !in shown }
    }

    private fun save(pages: List<GaugePage>) {
        ObdSettings.setPages(this, pages)
        render()
    }

    private fun editPage(index: Int) {
        val pages = ObdSettings.pages(this)
        val page = pages.getOrNull(index) ?: return
        val actions = ArrayList<Pair<String, () -> Unit>>()

        actions += getString(R.string.obd_layout_change, getString(layoutName(page.layout))) to {
            chooseLayout(index, page)
        }
        page.gauges.forEachIndexed { slot, gauge ->
            val name = getString(GaugeLabels.of(gauge))
            val label = when (page.layout.slots[slot]) {
                CellKind.DIAL -> getString(R.string.obd_layout_slot_dial, (slot + 1).toString(), name)
                CellKind.TILE -> getString(R.string.obd_layout_slot_tile, (slot + 1).toString(), name)
            }
            actions += label to { chooseGauge(index, page, slot) }
        }
        if (index > 0) actions += getString(R.string.obd_layout_up) to { save(move(pages, index, index - 1)) }
        if (index < pages.lastIndex) actions += getString(R.string.obd_layout_down) to { save(move(pages, index, index + 1)) }
        actions += getString(R.string.obd_layout_delete) to {
            if (pages.size <= 1) {
                Toast.makeText(this, R.string.obd_layout_last, Toast.LENGTH_SHORT).show()
            } else {
                save(pages.filterIndexed { i, _ -> i != index })
            }
        }

        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(getString(R.string.obd_layout_page, (index + 1).toString()))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun move(pages: List<GaugePage>, from: Int, to: Int): List<GaugePage> =
        pages.toMutableList().also { it.add(to, it.removeAt(from)) }

    private fun chooseLayout(index: Int, page: GaugePage) {
        val options = PageLayout.entries
        val labels = options.map { layout ->
            (if (layout == page.layout) "● " else "   ") + getString(layoutName(layout))
        }
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(R.string.obd_layout_pick_layout)
            .setItems(labels.toTypedArray()) { _, which ->
                replace(index, GaugePages.withLayout(page, options[which]))
                editPage(index)
            }
            .show()
    }

    private fun chooseGauge(index: Int, page: GaugePage, slot: Int) {
        val options = Gauge.entries
        val labels = options.map { gauge ->
            (if (gauge == page.gauges[slot]) "● " else "   ") + getString(GaugeLabels.of(gauge))
        }
        AlertDialog.Builder(this, dev.autobridge.settings.ScreenAppearance.dialogTheme())
            .setTitle(R.string.obd_layout_pick_gauge)
            .setItems(labels.toTypedArray()) { _, which ->
                replace(index, GaugePages.withGauge(page, slot, options[which]))
                editPage(index)
            }
            .show()
    }

    private fun replace(index: Int, page: GaugePage) {
        val pages = ObdSettings.pages(this).toMutableList()
        if (index in pages.indices) pages[index] = page
        ObdSettings.setPages(this, pages)
        render()
    }

    private fun layoutName(layout: PageLayout): Int = when (layout) {
        PageLayout.DIAL1 -> R.string.obd_pl_dial1
        PageLayout.DIALS2 -> R.string.obd_pl_dials2
        PageLayout.DIALS3 -> R.string.obd_pl_dials3
        PageLayout.DIALS2_TILES3 -> R.string.obd_pl_dials2_tiles3
        PageLayout.DIAL1_TILES4 -> R.string.obd_pl_dial1_tiles4
        PageLayout.TILES4 -> R.string.obd_pl_tiles4
        PageLayout.TILES6 -> R.string.obd_pl_tiles6
        PageLayout.TILES8 -> R.string.obd_pl_tiles8
    }
}
