package dev.autobridge.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import dev.autobridge.design.HomeLayout
import dev.autobridge.library.HomeSection

/** The glyph drawn inside a card's icon tile. One entry per home menu feature. */
internal enum class HomeMenuGlyph { TV, RADIO, GLOBE, YOUTUBE, YOUTUBE_MUSIC, STREAMING, SPLIT, DESKTOP }

/**
 * One home menu entry: what it says, how it looks, and what it opens - an existing [section], or,
 * for a card the build flavor adds ([CarExtensions]: Multi-app, Desktop browser), its [extension].
 */
internal data class HomeMenuItem(
    val section: HomeSection?,
    val glyph: HomeMenuGlyph,
    val accent: Int,
    val extension: CarExtensionEntry? = null
) {
    /** The tile's label, from the shared [HomeSection] so the two launchers cannot drift apart. */
    fun title(context: Context): String = section?.title(context) ?: extension?.title.orEmpty()

    companion object {
        /** The six primary destinations, in reading order (3 columns × 2 rows). */
        val primary: List<HomeMenuItem> = listOf(
            HomeMenuItem(HomeSection.TV, HomeMenuGlyph.TV, HomeDashboardTheme.ACCENT_TV),
            HomeMenuItem(HomeSection.RADIO, HomeMenuGlyph.RADIO, HomeDashboardTheme.ACCENT_RADIO),
            HomeMenuItem(HomeSection.WEB, HomeMenuGlyph.GLOBE, HomeDashboardTheme.ACCENT_WEB),
            HomeMenuItem(HomeSection.YOUTUBE, HomeMenuGlyph.YOUTUBE, HomeDashboardTheme.ACCENT_YOUTUBE),
            HomeMenuItem(
                HomeSection.YOUTUBE_MUSIC, HomeMenuGlyph.YOUTUBE_MUSIC,
                HomeDashboardTheme.ACCENT_YOUTUBE_MUSIC
            ),
            HomeMenuItem(HomeSection.STREAMING, HomeMenuGlyph.STREAMING, HomeDashboardTheme.ACCENT_STREAMING)
        )

        /** The primary cards, then the cards the flavor adds; the Play build adds none. */
        fun forHome(extensions: List<CarExtensionEntry>): List<HomeMenuItem> =
            primary + extensions.mapNotNull { entry ->
                entry.homeGlyph?.let { HomeMenuItem(null, it, entry.homeAccent, extension = entry) }
            }
    }
}

/**
 * The single home menu card component: a card holding a feature's icon chip and its label.
 *
 * Every feature card goes through [draw]; only [HomeMenuItem.glyph] and [HomeMenuItem.accent]
 * differ, so chip size, padding, radius and stroke weight are identical by construction. The shape
 * follows the layout's home ([HomeDashboardLayout.kind]): a Dashboard card (chip above the label,
 * aligned to the start), a Classic tile (chip over the label, centred; the label can be hidden)
 * or a Compact row (chip, label, chevron). Colours, card style and corners come from the look.
 */
internal class HomeMenuCard {
    enum class State { NORMAL, FOCUSED, PRESSED }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val rect = RectF()
    private val path = Path()

    /** [title] is resolved by the caller, which is the half of this pair that holds a Context. */
    fun draw(
        canvas: Canvas,
        bounds: MenuBox,
        item: HomeMenuItem,
        title: String,
        layout: HomeDashboardLayout,
        state: State
    ) {
        val look = layout.look
        val d = layout.density
        val radius = layout.dp(
            when (layout.kind) {
                HomeLayout.DASHBOARD -> look.cardRadius
                HomeLayout.CLASSIC -> look.tileRadius
                HomeLayout.COMPACT -> look.buttonRadius
            }
        )
        drawBody(canvas, bounds, radius, look, state, d)

        label.color = look.textPrimary
        label.textSize = layout.labelSize
        val metrics = label.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val icon = layout.chipSize
        val chip = look.chip(item.accent)
        val chipRadius = layout.dp(look.chipRadius(icon / layout.unit))

        when (layout.kind) {
            HomeLayout.DASHBOARD -> {
                // Icon chip on top, label under it, the pair centred vertically and aligned to the start.
                val gap = layout.dp(layout.profile.iconLabelGap)
                val padding = layout.dp(layout.profile.cardPadding)
                val blockTop = bounds.centerY - (icon + gap + lineHeight) / 2f
                val tile = MenuBox(bounds.left + padding, blockTop, bounds.left + padding + icon, blockTop + icon)
                drawIconTile(canvas, tile, item.glyph, chip, chipRadius, d)
                drawLabel(canvas, title, tile.left, tile.bottom + gap - metrics.ascent, bounds.width - padding * 2)
            }
            HomeLayout.CLASSIC -> {
                val labelled = look.showTileLabels
                val gap = if (labelled) layout.dp(HomeDashboardTheme.Dp.CLASSIC_LABEL_GAP) else 0f
                val block = icon + if (labelled) gap + lineHeight else 0f
                val top = bounds.centerY - block / 2f
                val tile = MenuBox(bounds.centerX - icon / 2f, top, bounds.centerX + icon / 2f, top + icon)
                drawIconTile(canvas, tile, item.glyph, chip, chipRadius, d)
                if (labelled) {
                    val maxWidth = bounds.width - layout.dp(HomeDashboardTheme.Dp.CLASSIC_PADDING) * 2
                    val text = TextUtils.ellipsize(title, label, maxWidth, TextUtils.TruncateAt.END)
                    val width = label.measureText(text, 0, text.length)
                    canvas.drawText(text, 0, text.length, bounds.centerX - width / 2f, tile.bottom + gap - metrics.ascent, label)
                }
            }
            HomeLayout.COMPACT -> {
                val padding = layout.dp(HomeDashboardTheme.Dp.COMPACT_ROW_PADDING)
                val inner = layout.dp(HomeDashboardTheme.Dp.COMPACT_ROW_GAP_INNER)
                val tile = MenuBox(bounds.left + padding, bounds.centerY - icon / 2f, bounds.left + padding + icon, bounds.centerY + icon / 2f)
                drawIconTile(canvas, tile, item.glyph, chip, chipRadius, d)
                val chevron = layout.dp(HomeDashboardTheme.Dp.COMPACT_CHEVRON)
                val chevronLeft = bounds.right - padding - chevron
                val textLeft = tile.right + inner
                val baseline = bounds.centerY - (metrics.ascent + metrics.descent) / 2f
                drawLabel(canvas, title, textLeft, baseline, chevronLeft - inner - textLeft)
                stroke.color = look.textTertiary
                stroke.strokeWidth = 2f * chevron / 24f
                stroke.strokeCap = Paint.Cap.ROUND
                stroke.strokeJoin = Paint.Join.ROUND
                val k = chevron / 24f
                val top = bounds.centerY - chevron / 2f
                path.reset()
                path.moveTo(chevronLeft + 9f * k, top + 6f * k)
                path.lineTo(chevronLeft + 15f * k, top + 12f * k)
                path.lineTo(chevronLeft + 9f * k, top + 18f * k)
                canvas.drawPath(path, stroke)
                stroke.strokeCap = Paint.Cap.BUTT
            }
        }
    }

    private fun drawLabel(canvas: Canvas, title: String, left: Float, baseline: Float, maxWidth: Float) {
        if (maxWidth <= 0f) return
        val text = TextUtils.ellipsize(title, label, maxWidth, TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, left, baseline, label)
    }

    /**
     * The card body for [state]: the look's card fill (none for an outlined card at rest), its
     * edge for outlined and glass cards, and the accent focus ring when focused or pressed.
     */
    fun drawBody(canvas: Canvas, bounds: MenuBox, radius: Float, look: HomeDashboardLook, state: State, d: Float) {
        rect.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
        fill.color = when (state) {
            State.NORMAL -> look.card
            State.FOCUSED -> look.cardFocused
            State.PRESSED -> look.cardPressed
        }
        if ((fill.color ushr 24) != 0) canvas.drawRoundRect(rect, radius, radius, fill)
        if (state == State.NORMAL) {
            if (look.hasCardEdge) {
                stroke.color = look.cardEdge
                stroke.strokeWidth = d
                inset(rect, d / 2f)
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
            return
        }
        stroke.color = HomeDashboardTheme.withAlpha(look.accent, if (state == State.PRESSED) 0.30f else 0.20f)
        stroke.strokeWidth = HomeDashboardTheme.Dp.FOCUS_GLOW * d
        canvas.drawRoundRect(rect, radius, radius, stroke)
        stroke.color = look.accent
        stroke.strokeWidth = HomeDashboardTheme.Dp.FOCUS_BORDER * d
        inset(rect, stroke.strokeWidth / 2f)
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    /**
     * A rounded chip in [chip]'s fill, a hairline when its style has one, and the glyph. The cards
     * and the Recently Sent rows both use it, so the two read as one family.
     */
    fun drawIconTile(canvas: Canvas, box: MenuBox, kind: HomeMenuGlyph, chip: HomeDashboardLook.Chip, radius: Float, d: Float) {
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = chip.fill
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (chip.hasEdge) {
            stroke.color = chip.edge
            stroke.strokeWidth = d
            inset(rect, d / 2f)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }

        // Glyphs are authored on a 100-unit tile with their own padding, so they fill the tile box.
        drawGlyph(canvas, box, kind, chip.glyph, chip.cutout)
    }

    /**
     * The same glyph, scaled into [box] instead of into a card's icon tile.
     *
     * Fallback artwork on the dashboard - the thumbnail stand-in for a row whose source publishes
     * no still - is this glyph over a tinted panel, so a card tile and a missing thumbnail are
     * drawn by one piece of code and cannot drift apart.
     */
    fun drawGlyph(canvas: Canvas, box: MenuBox, kind: HomeMenuGlyph, accent: Int, cutout: Int) {
        canvas.save()
        canvas.translate(box.left, box.top)
        canvas.scale(box.width / 100f, box.height / 100f)
        drawGlyph(canvas, kind, accent, cutout)
        canvas.restore()
    }

    /** [cutout] is the colour of what shows through a filled glyph (YouTube's play mark). */
    private fun drawGlyph(canvas: Canvas, kind: HomeMenuGlyph, accent: Int, cutout: Int) {
        val strokeWeight = 5.5f
        glyph.color = accent
        glyph.strokeWidth = strokeWeight
        when (kind) {
            HomeMenuGlyph.TV -> {
                rect.set(24f, 28f, 76f, 63f)
                glyph.style = Paint.Style.FILL
                glyph.color = HomeDashboardTheme.withAlpha(accent, 0.22f)
                canvas.drawRoundRect(rect, 5f, 5f, glyph)
                glyph.color = accent
                glyph.style = Paint.Style.STROKE
                canvas.drawRoundRect(rect, 5f, 5f, glyph)
                canvas.drawLine(50f, 64f, 50f, 72f, glyph)
                canvas.drawLine(38f, 73f, 62f, 73f, glyph)
            }
            HomeMenuGlyph.RADIO -> {
                glyph.style = Paint.Style.STROKE
                // Broadcast mast with two pairs of emission arcs.
                canvas.drawLine(50f, 50f, 50f, 76f, glyph)
                canvas.drawLine(42f, 76f, 58f, 76f, glyph)
                for (r in listOf(12f, 22f)) {
                    rect.set(50f - r, 44f - r, 50f + r, 44f + r)
                    canvas.drawArc(rect, 140f, 80f, false, glyph)
                    canvas.drawArc(rect, -40f, 80f, false, glyph)
                }
                glyph.style = Paint.Style.FILL
                canvas.drawCircle(50f, 44f, 5.5f, glyph)
            }
            HomeMenuGlyph.GLOBE -> {
                glyph.style = Paint.Style.STROKE
                canvas.drawCircle(50f, 50f, 25f, glyph)
                rect.set(38f, 25f, 62f, 75f)
                canvas.drawOval(rect, glyph)
                canvas.drawLine(25f, 50f, 75f, 50f, glyph)
                canvas.drawLine(29f, 37f, 71f, 37f, glyph)
                canvas.drawLine(29f, 63f, 71f, 63f, glyph)
            }
            HomeMenuGlyph.YOUTUBE -> {
                glyph.style = Paint.Style.FILL
                rect.set(22f, 32f, 78f, 68f)
                canvas.drawRoundRect(rect, 11f, 11f, glyph)
                glyph.color = cutout
                triangle(canvas, 44f, 41f, 60f)
            }
            HomeMenuGlyph.YOUTUBE_MUSIC -> {
                glyph.style = Paint.Style.FILL
                canvas.drawCircle(50f, 50f, 26f, glyph)
                glyph.color = cutout
                glyph.style = Paint.Style.STROKE
                glyph.strokeWidth = 3.5f
                canvas.drawCircle(50f, 50f, 15.5f, glyph)
                glyph.style = Paint.Style.FILL
                triangle(canvas, 45f, 43f, 57f)
            }
            HomeMenuGlyph.STREAMING -> {
                // A stack of video tiles: two edges behind a filled screen with a play mark.
                glyph.style = Paint.Style.STROKE
                canvas.drawLine(34f, 25f, 66f, 25f, glyph)
                canvas.drawLine(28f, 34f, 72f, 34f, glyph)
                glyph.style = Paint.Style.FILL
                rect.set(22f, 42f, 78f, 76f)
                canvas.drawRoundRect(rect, 9f, 9f, glyph)
                glyph.color = cutout
                path.reset()
                path.moveTo(45f, 51f)
                path.lineTo(45f, 67f)
                path.lineTo(58f, 59f)
                path.close()
                canvas.drawPath(path, glyph)
            }
            HomeMenuGlyph.SPLIT -> {
                // Two panes side by side: the left one filled, the right one outlined.
                glyph.style = Paint.Style.FILL
                glyph.color = HomeDashboardTheme.withAlpha(accent, 0.22f)
                rect.set(22f, 30f, 47f, 70f)
                canvas.drawRoundRect(rect, 5f, 5f, glyph)
                glyph.color = accent
                glyph.style = Paint.Style.STROKE
                canvas.drawRoundRect(rect, 5f, 5f, glyph)
                rect.set(53f, 30f, 78f, 70f)
                canvas.drawRoundRect(rect, 5f, 5f, glyph)
            }
            HomeMenuGlyph.DESKTOP -> {
                // A monitor on a stand, with a browser window's title bar inside.
                glyph.style = Paint.Style.STROKE
                rect.set(22f, 26f, 78f, 64f)
                canvas.drawRoundRect(rect, 5f, 5f, glyph)
                canvas.drawLine(22f, 37f, 78f, 37f, glyph)
                glyph.style = Paint.Style.FILL
                canvas.drawCircle(29f, 31.5f, 2.2f, glyph)
                canvas.drawCircle(36f, 31.5f, 2.2f, glyph)
                glyph.style = Paint.Style.STROKE
                canvas.drawLine(50f, 65f, 50f, 73f, glyph)
                canvas.drawLine(38f, 74f, 62f, 74f, glyph)
            }
        }
    }

    /** Right-pointing play triangle from ([left], [top]) to tip x [tip], centred on y = 50. */
    private fun triangle(canvas: Canvas, left: Float, top: Float, tip: Float) {
        path.reset()
        path.moveTo(left, top)
        path.lineTo(left, 100f - top)
        path.lineTo(tip, 50f)
        path.close()
        canvas.drawPath(path, glyph)
    }

    private fun inset(target: RectF, by: Float) = target.inset(by, by)
}
