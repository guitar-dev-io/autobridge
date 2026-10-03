package dev.autobridge.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import dev.autobridge.library.HomeSection

/** The glyph drawn inside a card's icon tile. One entry per home menu feature. */
internal enum class HomeMenuGlyph { TV, RADIO, GLOBE, YOUTUBE, YOUTUBE_MUSIC, STREAMING }

/** One home menu entry: what it says, how it looks, and which existing section it opens. */
internal data class HomeMenuItem(
    val section: HomeSection,
    val glyph: HomeMenuGlyph,
    val accent: Int
) {
    /** The tile's label, from the shared [HomeSection] so the two launchers cannot drift apart. */
    fun title(context: Context): String = section.title(context)

    companion object {
        /** The six primary destinations, in reading order (3 columns × 2 rows). */
        val primary: List<HomeMenuItem> = listOf(
            HomeMenuItem(HomeSection.TV, HomeMenuGlyph.TV, HomeMenuTheme.ACCENT_TV),
            HomeMenuItem(HomeSection.RADIO, HomeMenuGlyph.RADIO, HomeMenuTheme.ACCENT_RADIO),
            HomeMenuItem(HomeSection.WEB, HomeMenuGlyph.GLOBE, HomeMenuTheme.ACCENT_WEB),
            HomeMenuItem(HomeSection.YOUTUBE, HomeMenuGlyph.YOUTUBE, HomeMenuTheme.ACCENT_YOUTUBE),
            HomeMenuItem(
                HomeSection.YOUTUBE_MUSIC, HomeMenuGlyph.YOUTUBE_MUSIC,
                HomeMenuTheme.ACCENT_YOUTUBE_MUSIC
            ),
            HomeMenuItem(HomeSection.STREAMING, HomeMenuGlyph.STREAMING, HomeMenuTheme.ACCENT_STREAMING)
        )
    }
}

/**
 * The single home menu card component: rounded card, tinted icon tile, one-line label.
 *
 * All six cards go through [draw]; only [HomeMenuItem.glyph] and [HomeMenuItem.accent] differ,
 * so tile size, padding, radius and stroke weight are identical by construction.
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
        color = HomeMenuTheme.TEXT_PRIMARY
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()

    /** [title] is resolved by the caller, which is the half of this pair that holds a Context. */
    fun draw(
        canvas: Canvas,
        bounds: MenuBox,
        item: HomeMenuItem,
        title: String,
        layout: HomeMenuLayout,
        state: State
    ) {
        val d = layout.density
        val radius = layout.cornerRadius
        rect.set(bounds.left, bounds.top, bounds.right, bounds.bottom)

        // Card body.
        fill.color = when (state) {
            State.NORMAL -> HomeMenuTheme.CARD
            State.FOCUSED -> HomeMenuTheme.CARD_FOCUSED
            State.PRESSED -> HomeMenuTheme.CARD_PRESSED
        }
        canvas.drawRoundRect(rect, radius, radius, fill)

        // Border: hairline normally, AutoBridge blue with a soft halo when focused or pressed.
        if (state == State.NORMAL) {
            stroke.color = HomeMenuTheme.BORDER
            stroke.strokeWidth = HomeMenuTheme.Dp.CARD_BORDER * d
            inset(rect, stroke.strokeWidth / 2f)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        } else {
            stroke.color = HomeMenuTheme.withAlpha(HomeMenuTheme.ACCENT, if (state == State.PRESSED) 0.30f else 0.20f)
            stroke.strokeWidth = HomeMenuTheme.Dp.FOCUS_GLOW * d
            canvas.drawRoundRect(rect, radius, radius, stroke)
            stroke.color = HomeMenuTheme.ACCENT
            stroke.strokeWidth = HomeMenuTheme.Dp.FOCUS_BORDER * d
            inset(rect, stroke.strokeWidth / 2f)
            canvas.drawRoundRect(rect, radius, radius, stroke)
        }

        // Icon tile + label, centred as one block so short and long titles sit identically.
        label.textSize = layout.labelSize
        val metrics = label.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val icon = layout.iconSize
        val blockTop = bounds.centerY - (icon + layout.iconLabelGap + lineHeight) / 2f
        val iconLeft = bounds.centerX - icon / 2f
        drawIconTile(canvas, iconLeft, blockTop, icon, item, d)

        val text =
            TextUtils.ellipsize(title, label, layout.labelMaxWidth, TextUtils.TruncateAt.END)
        val baseline = blockTop + icon + layout.iconLabelGap - metrics.ascent
        canvas.drawText(text, 0, text.length, bounds.centerX, baseline, label)
    }

    private fun drawIconTile(canvas: Canvas, left: Float, top: Float, size: Float, item: HomeMenuItem, d: Float) {
        val radius = size * HomeMenuTheme.ICON_RADIUS_RATIO
        rect.set(left, top, left + size, top + size)
        fill.color = HomeMenuTheme.mix(HomeMenuTheme.CARD, item.accent, HomeMenuTheme.ICON_TILE_TINT)
        canvas.drawRoundRect(rect, radius, radius, fill)
        stroke.color = HomeMenuTheme.mix(HomeMenuTheme.CARD, item.accent, HomeMenuTheme.ICON_TILE_BORDER_TINT)
        stroke.strokeWidth = d
        inset(rect, d / 2f)
        canvas.drawRoundRect(rect, radius, radius, stroke)

        // Glyphs are authored on a 100-unit tile so every one shares the same padding and weight.
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(size / 100f, size / 100f)
        drawGlyph(canvas, item.glyph, item.accent)
        canvas.restore()
    }

    private fun drawGlyph(canvas: Canvas, kind: HomeMenuGlyph, accent: Int) {
        val strokeWeight = 5.5f
        glyph.color = accent
        glyph.strokeWidth = strokeWeight
        when (kind) {
            HomeMenuGlyph.TV -> {
                rect.set(24f, 28f, 76f, 63f)
                glyph.style = Paint.Style.FILL
                glyph.color = HomeMenuTheme.withAlpha(accent, 0.22f)
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
                glyph.color = HomeMenuTheme.TEXT_PRIMARY
                triangle(canvas, 44f, 41f, 60f)
            }
            HomeMenuGlyph.YOUTUBE_MUSIC -> {
                glyph.style = Paint.Style.FILL
                canvas.drawCircle(50f, 50f, 26f, glyph)
                glyph.color = HomeMenuTheme.TEXT_PRIMARY
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
                glyph.color = HomeMenuTheme.TEXT_PRIMARY
                path.reset()
                path.moveTo(45f, 51f)
                path.lineTo(45f, 67f)
                path.lineTo(58f, 59f)
                path.close()
                canvas.drawPath(path, glyph)
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
