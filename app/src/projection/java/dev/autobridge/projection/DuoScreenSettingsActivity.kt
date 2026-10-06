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
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

        // ── Shizuku status banner ────────────────────────────────────────────
        // The design replaces the old Connection section (AA + Shizuku rows)
        // with a single banner card. AA status is shown on Home already; only
        // the Shizuku state matters on this screen.
        body.stack(shizukuBanner(shizukuReady))

        // ── PANES ────────────────────────────────────────────────────────────
        val paneCount = DuoScreenStore.paneCount(this)
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.duo_screen_panes_section)), gap = 2)

        // Number of panes: 2/3 segmented control on a card
        val paneCountRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, AutoBridgeDesign.SURFACE, 20)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(
                TextView(this@DuoScreenSettingsActivity).apply {
                    text = getString(R.string.duo_screen_pane_count)
                    textSize = 15f
                    setTextColor(AutoBridgeDesign.TEXT)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            addView(paneCountSegments(paneCount), LinearLayout.LayoutParams(-2, -2))
        }
        body.stack(paneCountRow)

        // Pane app picker rows
        val packages = DuoScreenStore.packages(this)
        repeat(paneCount) { index ->
            val chosen = packages.getOrNull(index)
            val paneNumber = index + 1
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

        // ── LAYOUT ───────────────────────────────────────────────────────────
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.duo_screen_layout_section)), gap = 2)

        // Layout preset visual chips + content scale chips inside one card
        val preset = DuoScreenStore.preset(this)
        val scale = DuoScreenStore.contentScale(this)
        body.stack(layoutCard(preset, scale))

        // Reset arrangement row
        body.stack(
            AutoBridgeDesign.contentRow(
                context = this,
                title = getString(R.string.duo_screen_reset_layout),
                subtitle = getString(R.string.duo_screen_reset_layout_hint),
                accent = accent,
                badgeText = "↺",
                trailing = "›"
            ) {
                DuoScreenStore.resetLayout(this)
                DuoScreenHost.onLayoutReset()
                Toast.makeText(this, R.string.duo_screen_reset_done, Toast.LENGTH_SHORT).show()
                render()
            }
        )

        // ── SESSION ──────────────────────────────────────────────────────────
        body.stack(AutoBridgeDesign.sectionLabel(this, getString(R.string.duo_screen_session_section)), gap = 2)
        body.stack(dangerCard(
            getString(R.string.duo_screen_end_session),
            getString(R.string.duo_screen_end_session_hint)
        ) { confirmEndSession() })

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

    // ── Shizuku status banner ────────────────────────────────────────────────

    /**
     * A single card with an amber (or green) circle icon, the status title + hint text, and an
     * accent-filled "Grant" pill when permission is not yet available.
     */
    private fun shizukuBanner(ready: Boolean): View {
        val bannerAccent = if (ready) AutoBridgeDesign.ACCENT_ONLINE else AutoBridgeDesign.SIGNAL_SLOW
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AutoBridgeDesign.surface(
                this@DuoScreenSettingsActivity,
                AutoBridgeDesign.tint(bannerAccent, 0.08f), 20,
                AutoBridgeDesign.tint(bannerAccent, 0.25f)
            )
            setPadding(dp(12), dp(12), dp(14), dp(12))

            // Circle icon: "!" or "✓"
            addView(FrameLayout(this@DuoScreenSettingsActivity).apply {
                background = AutoBridgeDesign.surface(
                    this@DuoScreenSettingsActivity,
                    AutoBridgeDesign.tint(bannerAccent, 0.22f), 20,
                    bannerAccent
                )
                addView(TextView(this@DuoScreenSettingsActivity).apply {
                    text = if (ready) "✓" else "!"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(bannerAccent)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                }, FrameLayout.LayoutParams(-1, -1))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))

            // Title + subtitle
            val text = LinearLayout(this@DuoScreenSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            text.addView(TextView(this@DuoScreenSettingsActivity).apply {
                this.text = getString(
                    if (ready) R.string.duo_screen_shizuku_ready
                    else R.string.duo_screen_shizuku_missing
                )
                textSize = 14f
                setTextColor(AutoBridgeDesign.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            text.addView(TextView(this@DuoScreenSettingsActivity).apply {
                this.text = getString(R.string.duo_screen_shizuku_hint)
                textSize = 12f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            addView(text, LinearLayout.LayoutParams(0, -2, 1f))

            // Grant button — only when Shizuku is not ready
            if (!ready) {
                addView(TextView(this@DuoScreenSettingsActivity).apply {
                    this.text = getString(R.string.duo_screen_shizuku_grant)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    background = AutoBridgeDesign.tappable(
                        this@DuoScreenSettingsActivity,
                        AutoBridgeDesign.ACCENT, 14,
                        AutoBridgeDesign.ACCENT,
                        stroke = AutoBridgeDesign.ACCENT
                    )
                    setOnClickListener {
                        ShizukuInputBackend.requestPermission()
                        render()
                    }
                }, LinearLayout.LayoutParams(-2, -2))
            }
        }
    }

    // ── Pane count segmented control ─────────────────────────────────────────

    private fun paneCountSegments(current: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = AutoBridgeDesign.surface(
            this@DuoScreenSettingsActivity, AutoBridgeDesign.INK, 14
        )
        val inset = dp(3)
        setPadding(inset, inset, inset, inset)
        for (count in DuoScreenStore.MIN_PANES..DuoScreenStore.MAX_PANES) {
            val active = count == current
            addView(
                TextView(this@DuoScreenSettingsActivity).apply {
                    text = count.toString()
                    textSize = 14f
                    gravity = Gravity.CENTER
                    contentDescription = if (active) "$count, selected" else "$count"
                    setTextColor(if (active) android.graphics.Color.WHITE else AutoBridgeDesign.TEXT_MUTED)
                    if (active) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background = if (active) AutoBridgeDesign.surface(
                        this@DuoScreenSettingsActivity, AutoBridgeDesign.ACCENT, 11, AutoBridgeDesign.ACCENT
                    ) else null
                    minWidth = dp(52)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        if (count != current) {
                            DuoScreenStore.setPaneCount(this@DuoScreenSettingsActivity, count)
                            applied()
                            render()
                        }
                    }
                },
                LinearLayout.LayoutParams(-2, -2)
            )
        }
    }

    // ── Layout card (preset chips + content-size chips) ──────────────────────

    /**
     * A single card containing: "Layout preset" label + 5 mini-diagram chips, then a hairline,
     * then "Content size in a pane" label + hint + 4 percentage chips.
     */
    private fun layoutCard(selectedPreset: DuoScreenPreset, selectedScale: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, AutoBridgeDesign.SURFACE, 20)
            clipToOutline = true
            setPadding(dp(14), dp(14), dp(14), dp(14))

            // Preset label
            addView(TextView(this@DuoScreenSettingsActivity).apply {
                text = getString(R.string.duo_screen_preset)
                textSize = 15f
                setTextColor(AutoBridgeDesign.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-1, -2))

            // 5 preset mini-diagram chips in a row
            addView(presetChipRow(selectedPreset), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            })

            // Hairline divider
            addView(View(this@DuoScreenSettingsActivity).apply {
                setBackgroundColor(AutoBridgeDesign.HAIRLINE)
            }, LinearLayout.LayoutParams(-1, maxOf(1, dp(1) / 2)).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            })

            // Content size label + hint
            addView(TextView(this@DuoScreenSettingsActivity).apply {
                text = getString(R.string.duo_screen_content_scale)
                textSize = 15f
                setTextColor(AutoBridgeDesign.TEXT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(-1, -2))

            addView(TextView(this@DuoScreenSettingsActivity).apply {
                text = getString(R.string.duo_screen_content_scale_hint)
                textSize = 12f
                setTextColor(AutoBridgeDesign.TEXT_MUTED)
                maxLines = 2
                setPadding(0, dp(2), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))

            // Content scale chip row
            addView(contentScaleChipRow(selectedScale), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            })
        }
    }

    /** A row of 5 preset mini-diagram chips. */
    private fun presetChipRow(selected: DuoScreenPreset): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        DuoScreenPreset.entries.forEachIndexed { index, preset ->
            val active = preset == selected
            addView(
                presetChip(preset, active),
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                }
            )
        }
    }

    /** One preset chip: a mini-diagram + label. Active uses accent border, inactive uses hairline. */
    private fun presetChip(preset: DuoScreenPreset, active: Boolean): View {
        val chipAccent = if (active) AutoBridgeDesign.ACCENT else AutoBridgeDesign.HAIRLINE
        val bgFill = if (active) AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT, 0.12f) else AutoBridgeDesign.SURFACE_RAISED
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, bgFill, 14, chipAccent)
            isClickable = true
            isFocusable = true
            contentDescription = preset.label(this@DuoScreenSettingsActivity)
            setPadding(dp(6), dp(8), dp(6), dp(6))
            setOnClickListener {
                DuoScreenStore.setPreset(this@DuoScreenSettingsActivity, preset)
                applied()
                render()
            }

            // Mini diagram
            addView(miniPresetDiagram(preset, active), LinearLayout.LayoutParams(dp(40), dp(28)))

            // Label
            addView(TextView(this@DuoScreenSettingsActivity).apply {
                text = preset.label(this@DuoScreenSettingsActivity)
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(if (active) AutoBridgeDesign.ACCENT else AutoBridgeDesign.TEXT_MUTED)
                setPadding(0, dp(4), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * A small programmatic diagram of the preset's pane arrangement, drawn with nested views.
     * Active diagrams use ACCENT fill; inactive use SURFACE_RAISED with HAIRLINE stroke.
     */
    private fun miniPresetDiagram(preset: DuoScreenPreset, active: Boolean): View {
        val rectFill = if (active) AutoBridgeDesign.ACCENT else AutoBridgeDesign.SURFACE_RAISED
        val rectStroke = if (active) AutoBridgeDesign.ACCENT else AutoBridgeDesign.HAIRLINE
        val gap = dp(2)
        return when (preset) {
            DuoScreenPreset.EVEN_COLUMNS -> {
                // Two equal vertical rectangles side by side
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, rectFill, 4, rectStroke)
                    }, LinearLayout.LayoutParams(0, -1, 1f))
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, rectFill, 4, rectStroke)
                    }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = gap })
                }
            }
            DuoScreenPreset.WIDE_LEFT -> {
                // Left 60%, right 40%
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, rectFill, 4, rectStroke)
                    }, LinearLayout.LayoutParams(0, -1, 3f))
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, rectFill, 4, rectStroke)
                    }, LinearLayout.LayoutParams(0, -1, 2f).apply { marginStart = gap })
                }
            }
            DuoScreenPreset.WIDE_RIGHT -> {
                // Left 40%, right 60%
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, rectFill, 4, rectStroke)
                    }, LinearLayout.LayoutParams(0, -1, 2f))
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, rectFill, 4, rectStroke)
                    }, LinearLayout.LayoutParams(0, -1, 3f).apply { marginStart = gap })
                }
            }
            DuoScreenPreset.EVEN_ROWS -> {
                // Two equal horizontal rectangles stacked
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, rectFill, 4, rectStroke)
                    }, LinearLayout.LayoutParams(-1, 0, 1f))
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, rectFill, 4, rectStroke)
                    }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
                }
            }
            DuoScreenPreset.PICTURE_IN_PICTURE -> {
                // Full rect with a small rect in the bottom-right corner
                FrameLayout(this).apply {
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, rectFill, 4, rectStroke)
                    }, FrameLayout.LayoutParams(-1, -1))
                    // Small PIP tile in the bottom-right
                    val pipFill = if (active) AutoBridgeDesign.tint(AutoBridgeDesign.ACCENT, 0.6f) else AutoBridgeDesign.HAIRLINE
                    addView(View(this@DuoScreenSettingsActivity).apply {
                        background = AutoBridgeDesign.surface(this@DuoScreenSettingsActivity, pipFill, 3, rectStroke)
                    }, FrameLayout.LayoutParams(dp(14), dp(10), Gravity.BOTTOM or Gravity.END).apply {
                        marginEnd = dp(2)
                        bottomMargin = dp(2)
                    })
                }
            }
        }
    }

    /** A row of 4 content-scale percentage chips. */
    private fun contentScaleChipRow(selected: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        DuoScreenStore.CONTENT_SCALES.forEachIndexed { index, scale ->
            val active = scale == selected
            addView(
                TextView(this@DuoScreenSettingsActivity).apply {
                    text = getString(R.string.duo_screen_content_scale_value, scale)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    contentDescription = if (active) "${getString(R.string.duo_screen_content_scale_value, scale)}, selected" else getString(R.string.duo_screen_content_scale_value, scale)
                    setTextColor(if (active) android.graphics.Color.WHITE else AutoBridgeDesign.TEXT_MUTED)
                    if (active) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background = if (active) AutoBridgeDesign.surface(
                        this@DuoScreenSettingsActivity, AutoBridgeDesign.ACCENT, 14, AutoBridgeDesign.ACCENT
                    ) else AutoBridgeDesign.surface(
                        this@DuoScreenSettingsActivity, AutoBridgeDesign.SURFACE_RAISED, 14
                    )
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        DuoScreenStore.setContentScale(this@DuoScreenSettingsActivity, scale)
                        applied()
                        render()
                    }
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                }
            )
        }
    }

    // ── Danger card for ending the session ───────────────────────────────────

    /** A danger-tinted card with red title and subtitle for the End session action. */
    private fun dangerCard(title: String, caption: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AutoBridgeDesign.tappable(
                this@DuoScreenSettingsActivity,
                AutoBridgeDesign.tint(AutoBridgeDesign.DANGER, 0.08f), 20,
                AutoBridgeDesign.DANGER,
                stroke = AutoBridgeDesign.tint(AutoBridgeDesign.DANGER, 0.35f)
            )
            isClickable = true
            isFocusable = true
            contentDescription = title
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { onClick() }
            addView(TextView(this@DuoScreenSettingsActivity).apply {
                text = title
                textSize = 16f
                setTextColor(AutoBridgeDesign.DANGER)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            addView(TextView(this@DuoScreenSettingsActivity).apply {
                text = caption
                textSize = 13f
                setTextColor(AutoBridgeDesign.tint(AutoBridgeDesign.DANGER, 0.75f))
                setPadding(0, dp(3), 0, 0)
            })
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
