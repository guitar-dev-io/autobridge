package dev.autobridge.ui.appearance

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.design.AccentColor
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.CardStyle
import dev.autobridge.design.Corners
import dev.autobridge.design.FontChoice
import dev.autobridge.design.HomeLayout
import dev.autobridge.design.IconStyle
import dev.autobridge.design.TextStep
import dev.autobridge.design.ThaiStyle
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemePreset
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperTarget
import dev.autobridge.ui.AppFonts
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.appearance.AppearanceModel.Category
import dev.autobridge.ui.appearance.AppearanceModel.Page
import dev.autobridge.ui.appearance.AppearanceUi.p

/** What the pages ask of the screen that hosts them. */
internal interface AppearanceActions {
    val spec: AppearanceSpec
    /** Android's own night mode and font scale, for System mode and Follow Android. */
    val systemDark: Boolean
    val systemScale: Float
    fun change(transform: (AppearanceSpec) -> AppearanceSpec)
    fun open(page: Page)
    fun pickPhoto()
    fun askReset()
    fun pickTime(minuteOfDay: Int, onPicked: (Int) -> Unit)
}

/**
 * The bodies of Settings > Appearance and its four sub-pages, laid out as the redesign's phone
 * screens: Appearance, Theme & colors, Text, Wallpaper, Icons & cards. Each starts with the
 * [AppearancePreview].
 */
internal object AppearancePages {

    fun body(context: Context, page: Page, actions: AppearanceActions): LinearLayout {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(24))
        }
        body.addView(AppearancePreview.build(context, actions.spec, previewSummary(context, actions.spec)))
        when (page) {
            Page.MAIN -> main(context, body, actions)
            Page.THEME -> theme(context, body, actions)
            Page.TEXT -> text(context, body, actions)
            Page.WALLPAPER -> wallpaper(context, body, actions)
            Page.ICONS -> icons(context, body, actions)
        }
        return body
    }

    fun title(context: Context, page: Page): String = context.getString(
        when (page) {
            Page.MAIN -> R.string.appearance_title
            Page.THEME -> R.string.appearance_row_theme
            Page.TEXT -> R.string.appearance_row_text
            Page.WALLPAPER -> R.string.appearance_row_wallpaper
            Page.ICONS -> R.string.appearance_row_icons
        }
    )

    private fun previewSummary(context: Context, spec: AppearanceSpec): String = listOf(
        AppearanceLabels.preset(context, spec.preset),
        AppearanceLabels.accent(context, spec.accent),
        AppearanceLabels.fontShort(spec.font)
    ).joinToString(" · ")

    private fun selectedDescription(context: Context, label: String, selected: Boolean) =
        if (selected) context.getString(R.string.appearance_selected, label) else label

    private fun LinearLayout.add(view: View, topDp: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(topDp) })
    }

    // =================================================================================== main

    private fun main(context: Context, body: LinearLayout, actions: AppearanceActions) {
        val spec = actions.spec
        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_group_theme), first = true))
        body.add(modeButtons(context, spec.mode) { mode -> actions.change { it.copy(mode = mode) } })
        if (spec.mode == ThemeMode.AUTO) body.add(scheduleLine(context, actions), topDp = 8)

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_accent)))
        body.add(accentSwatches(context, spec.accent, named = false) { accent -> actions.change { it.copy(accent = accent) } })

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_home_layout)))
        body.add(layoutThumbnails(context, spec.homeLayout) { layout -> actions.change { it.copy(homeLayout = layout) } })

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_group_personalize)))
        val small = AppearanceModel.smallRadii(spec.corners)
        fun row(glyph: String, title: String, caption: String, page: Page) = AppearanceUi.listRow(
            context,
            AppearanceUi.glyphChip(context, glyph, p.accentSoft, p.accent, radiusDp = small.chipSmall),
            title, caption, AppearanceUi.chevron(context)
        ) { actions.open(page) }
        val textCaption = AppearanceLabels.font(context, spec.font) + " · " + if (spec.followSystemText) {
            context.getString(R.string.appearance_text_follow_summary, AppearanceModel.percent(actions.systemScale))
        } else {
            AppearanceLabels.step(context, spec.textStep) + " " + AppearanceModel.percent(spec.textStep.scale) + "%"
        }
        body.add(AppearanceUi.card(context, listOf(
            row(Glyphs.PALETTE, context.getString(R.string.appearance_row_theme), listOf(
                AppearanceLabels.preset(context, spec.preset), AppearanceLabels.mode(context, spec.mode), AppearanceLabels.accent(context, spec.accent)
            ).joinToString(" · "), Page.THEME),
            row(Glyphs.FONT, context.getString(R.string.appearance_row_text), textCaption, Page.TEXT),
            row(Glyphs.IMAGE, context.getString(R.string.appearance_row_wallpaper),
                context.getString(R.string.appearance_wallpaper_summary, AppearanceLabels.wallpaper(context, spec.wallpaper), spec.wallpaperDim), Page.WALLPAPER),
            row(Glyphs.SHAPES, context.getString(R.string.appearance_row_icons), context.getString(
                R.string.appearance_icons_summary,
                AppearanceLabels.iconStyle(context, spec.iconStyle),
                AppearanceLabels.cardStyle(context, spec.cardStyle),
                AppearanceLabels.corners(context, spec.corners).lowercase()
            ), Page.ICONS)
        )))

        body.add(AppearanceUi.card(context, listOf(
            AppearanceUi.toggleRow(
                context,
                context.getString(R.string.appearance_car_same_look),
                context.getString(R.string.appearance_car_same_look_caption),
                spec.carSameLook
            ) { actions.change { it.copy(carSameLook = !it.carSameLook) } }
        )), topDp = 12)

        body.add(AppearanceUi.text(context, context.getString(R.string.appearance_reset), 15f, p.danger, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(48)
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            background = AppearanceUi.ripple(AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.button, p.hairline))
            AppearanceUi.makeTappable(this, text.toString()) { actions.askReset() }
        }, topDp = 16)
    }

    /** The five mode buttons in one row, icon over a short name. */
    private fun modeButtons(context: Context, selected: ThemeMode, onPick: (ThemeMode) -> Unit): View {
        val cells = ThemeMode.entries.map { mode ->
            val on = mode == selected
            val label = AppearanceLabels.modeShort(context, mode)
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = context.dp(64)
                setPadding(context.dp(2), context.dp(8), context.dp(2), context.dp(8))
                background = AppearanceUi.optionBackground(context, on)
                addView(AppearanceUi.glyph(context, AppearanceLabels.modeGlyph(mode), if (on) p.accent else p.textSecondary, 20))
                addView(AppearanceUi.text(context, label, 11f, if (on) p.accent else p.textSecondary, bold = on, lines = 2).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, context.dp(4), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
                AppearanceUi.makeTappable(this, selectedDescription(context, AppearanceLabels.mode(context, mode), on), on) {
                    if (!on) onPick(mode)
                }
            }
        }
        return AppearanceUi.grid(context, cells, columns = 5, gapDp = 6)
    }

    /** Auto mode's one-line schedule: "Dark from [18:30] to [06:30] · now dark", times tappable. */
    private fun scheduleLine(context: Context, actions: AppearanceActions): View {
        val spec = actions.spec
        val small = AppearanceModel.smallRadii(spec.corners)
        fun time(minute: Int, onPicked: (Int) -> Unit): TextView =
            AppearanceUi.text(context, AppearanceModel.clock(minute), 15f, p.text, bold = true, lines = 1).apply {
                gravity = Gravity.CENTER
                minHeight = context.dp(36)
                setPadding(context.dp(8), 0, context.dp(8), 0)
                background = AppearanceUi.ripple(AppearanceUi.shape(context, p.surface, small.chipSmall))
                AppearanceUi.makeTappable(this, text.toString()) { actions.pickTime(minute, onPicked) }
            }
        val dark = ThemeResolver.isWithin(nowMinute(), spec.autoDarkFrom, spec.autoDarkTo)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AppearanceUi.shape(context, p.raised, p.corners.button)
            setPadding(context.dp(14), context.dp(6), context.dp(10), context.dp(6))
            addView(AppearanceUi.glyph(context, Glyphs.CLOCK, p.textSecondary, 18))
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_schedule_dark_from), 13f, p.textSecondary, lines = 1),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(10); marginEnd = context.dp(4) })
            addView(time(spec.autoDarkFrom) { m -> actions.change { it.copy(autoDarkFrom = m) } })
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_schedule_to), 13f, p.textSecondary, lines = 1),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(4); marginEnd = context.dp(4) })
            addView(time(spec.autoDarkTo) { m -> actions.change { it.copy(autoDarkTo = m) } })
            addView(AppearanceUi.text(context, " · " + context.getString(
                if (dark) R.string.appearance_schedule_now_dark else R.string.appearance_schedule_now_light
            ), 13f, p.textSecondary, lines = 1), LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun nowMinute(): Int {
        val now = java.util.Calendar.getInstance()
        return now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
    }

    /** Six accent discs. [named] adds the colour's name under each (Theme & colors). */
    private fun accentSwatches(context: Context, selected: AccentColor, named: Boolean, onPick: (AccentColor) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            AccentColor.entries.forEach { accent ->
                val on = accent == selected
                val color = if (p.isLight) accent.onLight else accent.onDark
                val name = AppearanceLabels.accent(context, accent)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, context.dp(2), 0, context.dp(2))
                    addView(if (named) AppearanceUi.swatch(context, color, on, 44, 34, check = false)
                    else AppearanceUi.swatch(context, color, on, 48, 36, check = true))
                    if (named) addView(AppearanceUi.text(context, name, 11f, if (on) p.text else p.textSecondary, lines = 1).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, context.dp(4), 0, 0)
                    }, LinearLayout.LayoutParams(-1, -2))
                    background = AppearanceUi.ripple(AppearanceUi.shape(context, Color.TRANSPARENT, 12))
                    AppearanceUi.makeTappable(this, selectedDescription(context, name, on), on) { if (!on) onPick(accent) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }

    /** The three Home layouts as small drawings of themselves. */
    private fun layoutThumbnails(context: Context, selected: HomeLayout, onPick: (HomeLayout) -> Unit): View {
        val cells = HomeLayout.entries.map { layout ->
            val on = layout == selected
            val strong = if (on) p.accent else p.hairline
            val soft = if (on) p.accentSoft else p.raised
            fun block(color: Int) = View(context).apply { background = AppearanceUi.shape(context, color, 3) }
            val drawing = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = AppearanceUi.shape(context, p.background, 8)
                setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
                when (layout) {
                    HomeLayout.CLASSIC -> repeat(2) { row ->
                        addView(LinearLayout(context).apply {
                            repeat(3) { col ->
                                addView(block(strong), LinearLayout.LayoutParams(0, -1, 1f).apply { if (col > 0) marginStart = context.dp(3) })
                            }
                        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (row > 0) topMargin = context.dp(3) })
                    }
                    HomeLayout.DASHBOARD -> {
                        addView(block(strong), LinearLayout.LayoutParams(-1, context.dp(20)))
                        addView(LinearLayout(context).apply {
                            repeat(2) { col ->
                                addView(block(soft), LinearLayout.LayoutParams(0, -1, 1f).apply { if (col > 0) marginStart = context.dp(3) })
                            }
                        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(3) })
                    }
                    HomeLayout.COMPACT -> repeat(4) { row ->
                        addView(block(if (row == 0) strong else soft), LinearLayout.LayoutParams(-1, 0, 1f).apply {
                            if (row > 0) topMargin = context.dp(3)
                        })
                    }
                }
            }
            val name = AppearanceLabels.layout(context, layout)
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                background = AppearanceUi.optionBackground(context, on)
                addView(drawing, LinearLayout.LayoutParams(-1, context.dp(64)))
                addView(AppearanceUi.text(context, name, 13f, if (on) p.accent else p.text, bold = on, lines = 2).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
                AppearanceUi.makeTappable(this, selectedDescription(context, name, on), on) { if (!on) onPick(layout) }
            }
        }
        return AppearanceUi.grid(context, cells, columns = 3, gapDp = 8)
    }

    // ================================================================================= theme

    private fun theme(context: Context, body: LinearLayout, actions: AppearanceActions) {
        val spec = actions.spec
        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_presets), first = true))
        val presets = ThemePreset.entries.map { preset ->
            val on = preset == spec.preset
            val name = AppearanceLabels.preset(context, preset)
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                background = AppearanceUi.ripple(AppearanceUi.shape(context, p.surface, p.corners.card,
                    if (on) p.accent else Color.TRANSPARENT, strokeDp = 2))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    background = AppearanceUi.shape(context, preset.background, 10)
                    setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                    addView(View(context).apply { background = AppearanceUi.shape(context, preset.surface, 8) },
                        LinearLayout.LayoutParams(0, context.dp(40), 1f))
                    addView(View(context).apply { background = AppearanceUi.oval(preset.suggested.onDark) },
                        LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply { marginStart = context.dp(6) })
                }, LinearLayout.LayoutParams(-1, context.dp(64)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(AppearanceUi.text(context, name, 13f, p.text, bold = true, lines = 1), LinearLayout.LayoutParams(0, -2, 1f))
                    if (on) addView(AppearanceUi.glyph(context, Glyphs.CHECK, p.accent, 16, 2.6f))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
                // A preset brings its own accent, as on the design canvas; the swatches below can
                // still change it afterwards.
                AppearanceUi.makeTappable(this, selectedDescription(context, name, on), on) {
                    if (!on) actions.change { it.copy(preset = preset, accent = preset.suggested) }
                }
            }
        }
        body.add(AppearanceUi.grid(context, presets, columns = 2, gapDp = 10))
        if (p.isLight) body.add(AppearanceUi.text(context, context.getString(R.string.appearance_presets_light_note), 12f, p.textTertiary).apply {
            setPadding(context.dp(4), 0, context.dp(4), 0)
        }, topDp = 8)

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_mode)))
        val small = AppearanceModel.smallRadii(spec.corners)
        body.add(AppearanceUi.card(context, ThemeMode.entries.map { mode ->
            val on = mode == spec.mode
            AppearanceUi.listRow(
                context,
                AppearanceUi.glyphChip(context, AppearanceLabels.modeGlyph(mode), p.raised, if (on) p.accent else p.textSecondary, radiusDp = small.chipSmall),
                AppearanceLabels.mode(context, mode),
                AppearanceLabels.modeDescription(context, mode, actions.systemDark),
                AppearanceUi.radioDot(context, on),
                minHeightDp = 60,
                selected = on
            ) { if (!on) actions.change { it.copy(mode = mode) } }
        }))
        if (spec.mode == ThemeMode.AUTO) body.add(scheduleCard(context, actions), topDp = 10)

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_accent)))
        body.add(accentSwatches(context, spec.accent, named = true) { accent -> actions.change { it.copy(accent = accent) } })
    }

    private fun scheduleCard(context: Context, actions: AppearanceActions): View {
        val spec = actions.spec
        val card = AppearanceUi.paddedCard(context)
        card.addView(AppearanceUi.text(context, context.getString(R.string.appearance_schedule), 15f, p.text, bold = true))
        fun button(label: Int, minute: Int, onPicked: (Int) -> Unit): View = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(56)
            setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
            background = AppearanceUi.ripple(AppearanceUi.shape(context, p.raised, p.corners.button))
            val caption = context.getString(label)
            addView(AppearanceUi.text(context, caption, 11f, p.textTertiary, lines = 1))
            addView(AppearanceUi.text(context, AppearanceModel.clock(minute), 17f, p.text, bold = true, lines = 1))
            AppearanceUi.makeTappable(this, caption + " " + AppearanceModel.clock(minute)) { actions.pickTime(minute, onPicked) }
        }
        card.addView(AppearanceUi.grid(context, listOf(
            button(R.string.appearance_schedule_dark_from, spec.autoDarkFrom) { m -> actions.change { it.copy(autoDarkFrom = m) } },
            button(R.string.appearance_schedule_light_from, spec.autoDarkTo) { m -> actions.change { it.copy(autoDarkTo = m) } }
        ), columns = 2, gapDp = 8), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
        return card
    }

    // ================================================================================== text

    private fun text(context: Context, body: LinearLayout, actions: AppearanceActions) {
        val spec = actions.spec
        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_font_family), first = true))
        body.add(AppearanceUi.card(context, FontChoice.entries.map { font ->
            val on = font == spec.font
            // Each row draws its sample in its own family.
            val sample = AppearanceUi.text(context, "Aa", 22f, if (on) p.accent else p.text, bold = true, lines = 1).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22f)
                typeface = AppFonts.sample(font, 600)
                layoutParams = LinearLayout.LayoutParams(context.dp(40), -2)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            AppearanceUi.listRow(
                context, sample, AppearanceLabels.font(context, font), AppearanceLabels.fontDescription(context, font),
                AppearanceUi.radioDot(context, on), selected = on
            ) { if (!on) actions.change { it.copy(font = font) } }
        }))

        // Inter, Manrope and Plex have no Thai; this picks the letterforms that fill in.
        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_thai_style)))
        body.add(AppearanceUi.card(context, ThaiStyle.entries.map { style ->
            val on = style == spec.thai
            val sample = AppearanceUi.text(context, context.getString(R.string.appearance_thai_letters), 20f, if (on) p.accent else p.text, bold = true, lines = 1).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
                typeface = AppFonts.thaiSample(style, 600)
                layoutParams = LinearLayout.LayoutParams(context.dp(76), -2)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            AppearanceUi.listRow(
                context, sample, AppearanceLabels.thai(context, style), AppearanceLabels.thaiDescription(context, style),
                AppearanceUi.radioDot(context, on), selected = on
            ) { if (!on) actions.change { it.copy(thai = style) } }
        }))

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_text_size)))
        body.add(textSizeCard(context, actions))

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_sample)))
        body.add(AppearanceUi.paddedCard(context).apply {
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_sample_title), 17f, p.text, bold = true).apply {
                setLineSpacing(0f, 1.35f)
            })
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_sample_thai), 15f, p.text).apply {
                setLineSpacing(0f, 1.6f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_sample_numbers), 13f, p.textSecondary).apply {
                fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
        })

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_sizes)))
        body.add(sizesTable(context, actions))
        body.add(AppearanceUi.text(context, context.getString(R.string.appearance_sizes_note), 11f, p.textTertiary).apply {
            setLineSpacing(0f, 1.5f)
            setPadding(context.dp(4), context.dp(8), context.dp(4), 0)
        })
    }

    private fun textSizeCard(context: Context, actions: AppearanceActions): View {
        val spec = actions.spec
        val following = spec.followSystemText
        val shownScale = if (following) actions.systemScale else spec.textStep.scale
        val shownStep = if (following) TextStep.entries.minBy { kotlin.math.abs(it.scale - actions.systemScale) } else spec.textStep
        val card = AppearanceUi.paddedCard(context)
        card.addView(AppearanceUi.toggleRow(
            context,
            context.getString(R.string.appearance_follow_system_text),
            context.getString(R.string.appearance_follow_system_text_now, AppearanceModel.percent(actions.systemScale)),
            following
        ) { actions.change { it.copy(followSystemText = !it.followSystemText) } }.apply { setPadding(0, 0, 0, context.dp(6)) })
        card.addView(AppearanceUi.hairline(context))

        val percent = AppearanceUi.text(context, "${AppearanceModel.percent(shownScale)}%", 13f, p.accent, bold = true, lines = 1).apply {
            gravity = Gravity.END
            fontFeatureSettings = "tnum"
        }
        val slider = AppearanceUi.seekBar(
            context, TextStep.entries.size - 1, shownStep.ordinal, context.getString(R.string.appearance_text_size),
            onMove = { at -> percent.text = "${AppearanceModel.percent(TextStep.entries[at].scale)}%" },
            onRelease = { at -> actions.change { it.copy(textStep = TextStep.entries[at], followSystemText = false) } }
        ).apply { isEnabled = !following }
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = if (following) 0.5f else 1f
            addView(AppearanceUi.text(context, "A", 13f, p.textSecondary).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(slider, LinearLayout.LayoutParams(0, -2, 1f))
            addView(AppearanceUi.text(context, "A", 24f, p.textSecondary).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(percent, LinearLayout.LayoutParams(context.dp(56), -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })

        val steps = TextStep.entries.map { step ->
            val on = step == shownStep
            val name = AppearanceLabels.step(context, step)
            val pct = "${AppearanceModel.percent(step.scale)}%"
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = context.dp(44)
                setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                background = AppearanceUi.optionBackground(context, on)
                addView(AppearanceUi.text(context, name, 11f, if (on) p.accent else p.textSecondary, bold = on, lines = 1).apply { gravity = Gravity.CENTER })
                addView(AppearanceUi.text(context, pct, 10f, if (on) p.accent else p.textSecondary, lines = 1).apply {
                    gravity = Gravity.CENTER
                    alpha = 0.75f
                })
                alpha = if (following) 0.5f else 1f
                if (!following) AppearanceUi.makeTappable(this, selectedDescription(context, "$name $pct", on), on) {
                    if (!on) actions.change { it.copy(textStep = step, followSystemText = false) }
                }
            }
        }
        card.addView(AppearanceUi.grid(context, steps, columns = 3, gapDp = 6), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })

        if (AppearanceModel.showsLargeTextNote(spec, actions.systemScale)) {
            card.addView(AppearanceUi.text(context, context.getString(R.string.appearance_text_note), 13f, p.textSecondary).apply {
                setLineSpacing(0f, 1.45f)
                setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
                background = AppearanceUi.shape(context, p.raised, AppearanceModel.smallRadii(spec.corners).chipSmall)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
        }
        return card
    }

    private fun sizesTable(context: Context, actions: AppearanceActions): View {
        fun line(cells: List<CharSequence>, header: Boolean): View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14), context.dp(if (header) 10 else 8), context.dp(14), context.dp(if (header) 10 else 8))
            cells.forEachIndexed { index, value ->
                addView(AppearanceUi.text(
                    context, value, if (header) 11f else 13f,
                    when {
                        header -> p.textTertiary
                        index == 0 -> p.text
                        else -> p.textSecondary
                    },
                    bold = header
                ).apply {
                    gravity = if (index == 0) Gravity.START else Gravity.END
                    fontFeatureSettings = "tnum"
                }, LinearLayout.LayoutParams(0, -2, if (index == 0) 1.4f else 1f).apply { if (index > 0) marginStart = context.dp(8) })
            }
        }
        fun arrow(from: Int, to: Int): CharSequence = SpannableStringBuilder("$from → ").apply {
            val start = length
            append(to.toString())
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(p.text), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val rows = mutableListOf(line(listOf(
            context.getString(R.string.appearance_sizes_style),
            context.getString(R.string.appearance_sizes_phone),
            context.getString(R.string.appearance_sizes_car)
        ), header = true))
        AppearanceModel.sizeRows(actions.spec, actions.systemScale).forEach { row ->
            rows += line(listOf(AppearanceLabels.textStyle(context, row.style), arrow(row.phoneBase, row.phone), arrow(row.carBase, row.car)), header = false)
        }
        return AppearanceUi.card(context, rows)
    }

    // ============================================================================= wallpaper

    private fun wallpaper(context: Context, body: LinearLayout, actions: AppearanceActions) {
        val spec = actions.spec
        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_background), first = true))
        val photo = WallpaperPhoto.preview(context)
        val density = context.resources.displayMetrics.density
        val tiles = Wallpaper.entries.map { wallpaper ->
            val on = wallpaper == spec.wallpaper
            val name = AppearanceLabels.wallpaper(context, wallpaper)
            val art = FrameLayout(context).apply {
                background = WallpaperDrawable(wallpaper, p, density, context.dp(p.corners.tile).toFloat(), photo = photo)
                foreground = AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.tile, if (on) p.accent else p.hairline, strokeDp = 2)
                if (wallpaper == Wallpaper.CUSTOM && photo == null) addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(AppearanceUi.glyph(context, Glyphs.UPLOAD, CHOOSE_PHOTO_INK, 22))
                    addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_choose), 11f, CHOOSE_PHOTO_INK, lines = 2).apply {
                        gravity = Gravity.CENTER
                        setPadding(context.dp(4), context.dp(4), context.dp(4), 0)
                    })
                }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(art, LinearLayout.LayoutParams(-1, context.dp(120)))
                addView(AppearanceUi.text(context, name, 13f, if (on) p.accent else p.textSecondary, bold = on, lines = 1).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
                background = AppearanceUi.ripple(AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.tile))
                AppearanceUi.makeTappable(this, selectedDescription(context, name, on), on) {
                    when {
                        // Your photo with nothing saved yet, or tapped again: pick one.
                        wallpaper == Wallpaper.CUSTOM && (photo == null || on) -> actions.pickPhoto()
                        !on -> actions.change { it.copy(wallpaper = wallpaper) }
                    }
                }
            }
        }
        body.add(AppearanceUi.grid(context, tiles, columns = 3, gapDp = 10))
        if (spec.wallpaper == Wallpaper.CUSTOM && photo != null) {
            body.add(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_change), 14f, p.accent, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = context.dp(44)
                background = AppearanceUi.ripple(AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.button))
                AppearanceUi.makeTappable(this, text.toString()) { actions.pickPhoto() }
            }, topDp = 6)
        }

        val dimValue = AppearanceUi.text(context, "${spec.wallpaperDim}%", 13f, p.textSecondary, lines = 1).apply { fontFeatureSettings = "tnum" }
        body.add(AppearanceUi.paddedCard(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(AppearanceUi.text(context, context.getString(R.string.appearance_dim), 15f, p.text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(dimValue)
            })
            addView(AppearanceUi.seekBar(
                context, AppearanceModel.DIM_STEPS, AppearanceModel.dimToProgress(spec.wallpaperDim), context.getString(R.string.appearance_dim),
                onMove = { at -> dimValue.text = "${AppearanceModel.progressToDim(at)}%" },
                onRelease = { at -> actions.change { it.copy(wallpaperDim = AppearanceModel.progressToDim(at)) } }
            ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
            addView(AppearanceUi.hairline(context), LinearLayout.LayoutParams(-1, maxOf(1, context.dp(1))).apply { topMargin = context.dp(6) })
            addView(AppearanceUi.toggleRow(
                context,
                context.getString(R.string.appearance_blur),
                context.getString(R.string.appearance_blur_caption),
                spec.blurBehindCards
            ) { actions.change { it.copy(blurBehindCards = !it.blurBehindCards) } }.apply { setPadding(0, context.dp(6), 0, 0) })
        }, topDp = 14)

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_show_on)))
        body.add(AppearanceUi.segmented(
            context, WallpaperTarget.entries.map { it to AppearanceLabels.target(context, it) }, spec.wallpaperTarget
        ) { target -> actions.change { it.copy(wallpaperTarget = target) } })
    }

    /** "Choose photo" on the placeholder gradient, which is dark in both modes. */
    private const val CHOOSE_PHOTO_INK = 0xFFDCE3EC.toInt()

    // ================================================================================= icons

    private fun icons(context: Context, body: LinearLayout, actions: AppearanceActions) {
        val spec = actions.spec
        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_icon_style), first = true))
        val examples = listOf(Category.RADIO to Glyphs.RADIO, Category.TV to Glyphs.TV, Category.MUSIC to Glyphs.MUSIC)
        body.add(AppearanceUi.grid(context, IconStyle.entries.map { style ->
            val on = style == spec.iconStyle
            val name = AppearanceLabels.iconStyle(context, style)
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(context.dp(8), context.dp(12), context.dp(8), context.dp(12))
                background = AppearanceUi.optionBackground(context, on)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    examples.forEachIndexed { index, (category, glyph) ->
                        val chip = AppearanceModel.chip(style, category, p)
                        addView(FrameLayout(context).apply {
                            background = AppearanceUi.shape(context, chip.fill, 8)
                            addView(GlyphView(context, glyph, chip.glyph, 2f), FrameLayout.LayoutParams(context.dp(15), context.dp(15), Gravity.CENTER))
                        }, LinearLayout.LayoutParams(context.dp(28), context.dp(28)).apply { if (index > 0) marginStart = context.dp(4) })
                    }
                })
                addView(AppearanceUi.text(context, name, 13f, if (on) p.accent else p.text, bold = on, lines = 1).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
                AppearanceUi.makeTappable(this, selectedDescription(context, name, on), on) {
                    if (!on) actions.change { it.copy(iconStyle = style) }
                }
            }
        }, columns = 3, gapDp = 8))

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_card_style)))
        val density = context.resources.displayMetrics.density
        val photo = if (spec.wallpaper == Wallpaper.CUSTOM) WallpaperPhoto.preview(context) else null
        body.add(AppearanceUi.grid(context, CardStyle.entries.map { style ->
            val on = style == spec.cardStyle
            val name = AppearanceLabels.cardStyle(context, style)
            val sample = cardSample(spec, style, actions.systemDark)
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                background = AppearanceUi.optionBackground(context, on)
                addView(FrameLayout(context).apply {
                    background = WallpaperDrawable(spec.wallpaper, p, density, context.dp(10).toFloat(), photo = photo)
                    setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                    addView(View(context).apply { background = AppearanceUi.shape(context, sample.first, 8, sample.second) },
                        FrameLayout.LayoutParams(-1, -1))
                }, LinearLayout.LayoutParams(-1, context.dp(56)))
                addView(AppearanceUi.text(context, name, 13f, if (on) p.accent else p.text, bold = on, lines = 1).apply {
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
                AppearanceUi.makeTappable(this, selectedDescription(context, name, on), on) {
                    if (!on) actions.change { it.copy(cardStyle = style) }
                }
            }
        }, columns = 3, gapDp = 8))

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_corners)))
        body.add(AppearanceUi.segmented(
            context, Corners.entries.map { it to AppearanceLabels.corners(context, it) }, spec.corners
        ) { corners -> actions.change { it.copy(corners = corners) } })

        body.add(AppearanceUi.card(context, listOf(
            AppearanceUi.toggleRow(
                context,
                context.getString(R.string.appearance_tile_labels),
                context.getString(R.string.appearance_tile_labels_caption),
                spec.showTileLabels
            ) { actions.change { it.copy(showTileLabels = !it.showTileLabels) } }
        )), topDp = 14)
    }

    /**
     * Fill and edge a card would have in [style] on the screen as it is now: resolved by the
     * foundation's resolver, with the mode pinned to what is showing so System and Auto agree.
     */
    private fun cardSample(spec: AppearanceSpec, style: CardStyle, systemDark: Boolean): Pair<Int, Int> {
        val shownMode = when {
            p.isLight -> ThemeMode.LIGHT
            spec.mode == ThemeMode.AMOLED -> ThemeMode.AMOLED
            else -> ThemeMode.DARK
        }
        val palette = ThemeResolver.resolve(spec.copy(cardStyle = style, mode = shownMode), systemDark)
        return palette.cardFill to palette.cardEdge
    }
}
