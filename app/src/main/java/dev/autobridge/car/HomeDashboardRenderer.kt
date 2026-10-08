package dev.autobridge.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import dev.autobridge.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Paints the whole AutoBridge home onto the car surface: background, header, the Now Playing card
 * with its controls, the quick-access card grid, the Recently Sent row with its queue link and,
 * only when the column cannot fit, the scroll buttons.
 *
 * Android Auto's own chrome — the side rail, the bottom bar, the clock and the status icons —
 * lives outside this surface and is never drawn or imitated here. The one host control this screen
 * uses is the action strip, which the host itself draws.
 *
 * Every Paint, Path and Typeface is built once and reused; the only per-frame allocations are the
 * ellipsized CharSequences, and the two-line hero title and fallback-artwork gradients are cached.
 * The dashboard re-renders on every scroll pixel, so nothing here may decode a drawable or build a
 * shader inside a frame.
 */
internal class HomeDashboardRenderer(private val context: Context) {

    /**
     * @param focused index of the quick-access card to show as focused, or -1.
     * @param pressed the region currently flashing under a finger, or null.
     * @param playing the Now Playing item is the live session and is playing: the centre button
     *   shows pause and the caption says so.
     * @param nextEnabled there is something for the next button to play.
     */
    data class State(
        val focused: Int = -1,
        val pressed: HomeHit? = null,
        val scroll: Float = 0f,
        val playing: Boolean = false,
        val nextEnabled: Boolean = false
    )

    /** Resolves a thumbnail URL to a decoded bitmap, or null while there is none to draw. */
    fun interface Thumbnails {
        fun bitmap(url: String?): Bitmap?
    }

    private val logo: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_autobridge_launcher)
    private val card = HomeMenuCard()

    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private val wordmark = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
    private val sectionTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bold
        color = HomeDashboardTheme.TEXT_SECTION
    }
    private val link = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = regular
        color = HomeDashboardTheme.ACCENT
        textAlign = Paint.Align.RIGHT
    }
    private val heroTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bold
        color = HomeDashboardTheme.TEXT_PRIMARY
    }
    private val caption = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = medium
        color = HomeDashboardTheme.TEXT_PRIMARY
    }
    private val meta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = regular
        color = HomeDashboardTheme.TEXT_SECONDARY
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val source = Rect()
    private val path = Path()
    private val chevron = Path()

    /** Fallback-artwork ramps, keyed by accent and rounded height; see [artworkShader]. */
    private val shaders = HashMap<Long, LinearGradient>()

    /** The hero title wrapped to two lines, kept until its text, width or size changes. */
    private var heroTitleLayout: StaticLayout? = null
    private var heroTitleKey: String? = null

    /** Card label width at a text size, measured with the exact paint the cards draw with. */
    fun measureLabel(text: String, size: Float): Float {
        card.label.textSize = size
        return card.label.measureText(text)
    }

    fun draw(
        canvas: Canvas,
        layout: HomeDashboardLayout,
        items: List<HomeMenuItem>,
        content: HomeDashboardContent,
        state: State,
        thumbnails: Thumbnails
    ) {
        canvas.drawColor(HomeDashboardTheme.BACKGROUND)
        drawHeader(canvas, layout)

        canvas.save()
        val viewport = layout.viewport
        // Room for the focus halo, which straddles the card edge.
        val halo = HomeDashboardTheme.Dp.FOCUS_GLOW * layout.density
        canvas.clipRect(viewport.left - halo, viewport.top, viewport.right + halo, viewport.bottom + halo)
        canvas.translate(0f, -state.scroll)

        val hero = layout.hero
        val item = content.continueWatching
        if (hero != null && item != null) drawHero(canvas, hero, layout, item, state, thumbnails)

        items.forEachIndexed { index, menuItem ->
            val bounds = layout.cards.getOrNull(index) ?: return@forEachIndexed
            val cardState = when {
                state.pressed?.region == HomeRegion.QUICK_ACCESS && state.pressed.index == index ->
                    HomeMenuCard.State.PRESSED
                index == state.focused -> HomeMenuCard.State.FOCUSED
                else -> HomeMenuCard.State.NORMAL
            }
            card.draw(canvas, bounds, menuItem, menuItem.title(context), layout, cardState)
        }

        drawRecent(canvas, layout, content, state, thumbnails)

        canvas.restore()

        layout.scrollUp?.let { drawScrollButton(canvas, it, layout, up = true, enabled = state.scroll > 0f) }
        layout.scrollDown?.let {
            drawScrollButton(canvas, it, layout, up = false, enabled = state.scroll < layout.maxScroll)
        }
    }

    // ------------------------------------------------------------------------------- header

    private fun drawHeader(canvas: Canvas, layout: HomeDashboardLayout) {
        val box = layout.logo
        logo?.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        logo?.draw(canvas)

        wordmark.textSize = layout.titleSize
        val metrics = wordmark.fontMetrics
        // Centre the cap-height block on the logo, not the full ascent/descent box.
        val baseline = box.centerY - (metrics.ascent + metrics.descent) / 2f
        wordmark.color = HomeDashboardTheme.TEXT_PRIMARY
        canvas.drawText(WORD_AUTO, layout.titleX, baseline, wordmark)
        wordmark.color = HomeDashboardTheme.ACCENT_WORDMARK
        canvas.drawText(WORD_BRIDGE, layout.titleX + wordmark.measureText(WORD_AUTO), baseline, wordmark)
    }

    // ------------------------------------------------------------------------- Now Playing

    private fun drawHero(
        canvas: Canvas,
        hero: HeroBoxes,
        layout: HomeDashboardLayout,
        item: ContinueItem,
        state: State,
        thumbnails: Thumbnails
    ) {
        val cardPressed = state.pressed?.region == HomeRegion.CONTINUE
        rect.set(hero.card.left, hero.card.top, hero.card.right, hero.card.bottom)
        fill.color = if (cardPressed) HomeDashboardTheme.CARD_PRESSED else HomeDashboardTheme.CARD
        val radius = layout.dp(HomeDashboardTheme.Dp.CARD_RADIUS + 2f)
        canvas.drawRoundRect(rect, radius, radius, fill)

        drawThumbnail(canvas, hero.art, layout.dp(HomeDashboardTheme.Dp.HERO_ART_RADIUS), item.artwork, thumbnails)

        // Caption ("YouTube · Now playing") in the source's accent, then the title on up to two lines.
        caption.textSize = layout.dp(HomeDashboardTheme.Dp.HERO_CAPTION)
        caption.color = item.artwork.accent
        heroTitle.textSize = layout.dp(HomeDashboardTheme.Dp.HERO_TITLE)
        val status = context.getString(
            if (state.playing) R.string.car_home_now_playing else R.string.car_home_continue_watching
        )
        val captionText = listOf(item.sourceLabel, status).filter { it.isNotBlank() }.joinToString(" · ")
        val captionLine = lineHeight(caption)
        val wrapped = heroTitleLayout(item.title, hero.text.width.toInt())
        val block = captionLine + wrapped.height
        var y = hero.text.centerY - block / 2f
        drawEllipsized(canvas, captionText, caption, hero.text.left, hero.text.right, y, captionLine)
        y += captionLine
        canvas.save()
        canvas.translate(hero.text.left, y)
        wrapped.draw(canvas)
        canvas.restore()

        // Progress bar and the two times under it. No bar when nobody reported a duration.
        val bar = hero.progress
        val barRadius = bar.height / 2f
        fill.color = HomeDashboardTheme.PROGRESS_TRACK
        rect.set(bar.left, bar.top, bar.right, bar.bottom)
        canvas.drawRoundRect(rect, barRadius, barRadius, fill)
        item.progress?.let { progress ->
            fill.color = HomeDashboardTheme.ACCENT
            rect.set(bar.left, bar.top, bar.left + bar.width * progress, bar.bottom)
            canvas.drawRoundRect(rect, barRadius, barRadius, fill)
        }
        meta.textSize = layout.dp(HomeDashboardTheme.Dp.HERO_TIME)
        val timesBaseline = baselineIn(meta, hero.times)
        canvas.drawText(HomeDashboardClock.clock(item.positionMs), hero.times.left, timesBaseline, meta)
        HomeDashboardClock.duration(item.durationMs)?.let {
            canvas.drawText(it, hero.times.right - meta.measureText(it), timesBaseline, meta)
        }

        val pressed = state.pressed?.region
        drawControl(canvas, hero.previous, layout, Glyph.PREVIOUS, primary = false, enabled = true,
            pressed = pressed == HomeRegion.CONTROL_PREVIOUS)
        drawControl(canvas, hero.play, layout, if (state.playing) Glyph.PAUSE else Glyph.PLAY, primary = true,
            enabled = true, pressed = pressed == HomeRegion.CONTROL_PLAY)
        drawControl(canvas, hero.next, layout, Glyph.NEXT, primary = false, enabled = state.nextEnabled,
            pressed = pressed == HomeRegion.CONTROL_NEXT)
    }

    private enum class Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

    private fun drawControl(
        canvas: Canvas,
        box: MenuBox,
        layout: HomeDashboardLayout,
        glyph: Glyph,
        primary: Boolean,
        enabled: Boolean,
        pressed: Boolean
    ) {
        val radius = box.width / 2f
        fill.color = when {
            primary && pressed -> HomeDashboardTheme.mix(HomeDashboardTheme.ACCENT, HomeDashboardTheme.TEXT_PRIMARY, 0.35f)
            primary -> HomeDashboardTheme.ACCENT
            pressed -> HomeDashboardTheme.CONTROL_PRESSED
            else -> HomeDashboardTheme.CONTROL
        }
        canvas.drawCircle(box.centerX, box.centerY, radius, fill)

        val color = when {
            primary -> HomeDashboardTheme.ON_ACCENT
            enabled -> HomeDashboardTheme.TEXT_CONTROL
            else -> HomeDashboardTheme.withAlpha(HomeDashboardTheme.TEXT_SECONDARY, 0.45f)
        }
        fill.color = color
        // Glyphs on a 24-unit grid, the same shapes the design uses.
        val size = layout.dp(if (primary) HomeDashboardTheme.Dp.CONTROL_PRIMARY_GLYPH else HomeDashboardTheme.Dp.CONTROL_GLYPH)
        val unit = size / 24f
        val ox = box.centerX - size / 2f
        val oy = box.centerY - size / 2f
        fun x(v: Float) = ox + v * unit
        fun y(v: Float) = oy + v * unit
        when (glyph) {
            Glyph.PREVIOUS -> {
                rect.set(x(6f), y(5f), x(8f), y(19f))
                canvas.drawRect(rect, fill)
                path.reset(); path.moveTo(x(20f), y(5f)); path.lineTo(x(20f), y(19f)); path.lineTo(x(9f), y(12f)); path.close()
                canvas.drawPath(path, fill)
            }
            Glyph.NEXT -> {
                rect.set(x(16f), y(5f), x(18f), y(19f))
                canvas.drawRect(rect, fill)
                path.reset(); path.moveTo(x(4f), y(5f)); path.lineTo(x(4f), y(19f)); path.lineTo(x(15f), y(12f)); path.close()
                canvas.drawPath(path, fill)
            }
            Glyph.PAUSE -> {
                rect.set(x(6f), y(5f), x(10f), y(19f))
                canvas.drawRect(rect, fill)
                rect.set(x(14f), y(5f), x(18f), y(19f))
                canvas.drawRect(rect, fill)
            }
            Glyph.PLAY -> {
                path.reset(); path.moveTo(x(8f), y(5f)); path.lineTo(x(8f), y(19f)); path.lineTo(x(19f), y(12f)); path.close()
                canvas.drawPath(path, fill)
            }
        }
    }

    private fun heroTitleLayout(text: String, width: Int): StaticLayout {
        val key = "$text|$width|${heroTitle.textSize}"
        heroTitleLayout?.takeIf { heroTitleKey == key }?.let { return it }
        return StaticLayout.Builder.obtain(text, 0, text.length, heroTitle, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .build()
            .also {
                heroTitleLayout = it
                heroTitleKey = key
            }
    }

    // ---------------------------------------------------------------------- Recently Sent

    private fun drawRecent(
        canvas: Canvas,
        layout: HomeDashboardLayout,
        content: HomeDashboardContent,
        state: State,
        thumbnails: Thumbnails
    ) {
        val pressed = state.pressed
        layout.recentHeader?.let { box ->
            sectionTitle.textSize = layout.dp(HomeDashboardTheme.Dp.SECTION_TITLE)
            sectionTitle.color =
                if (pressed?.region == HomeRegion.RECENT_HEADER) HomeDashboardTheme.ACCENT else HomeDashboardTheme.TEXT_SECTION
            canvas.drawText(context.getString(R.string.car_home_recent_from_phone), box.left, baselineIn(sectionTitle, box), sectionTitle)
        }
        layout.queueHeader?.let { box ->
            link.textSize = layout.dp(HomeDashboardTheme.Dp.SECTION_LINK)
            link.color = if (pressed?.region == HomeRegion.QUEUE_HEADER) HomeDashboardTheme.TEXT_PRIMARY else HomeDashboardTheme.ACCENT
            canvas.drawText(queueLink(content.queueTotal), box.right, baselineIn(link, box), link)
        }
        content.recentlySent.forEachIndexed { index, item ->
            val box = layout.recentRows.getOrNull(index) ?: return@forEachIndexed
            val rowPressed = pressed?.region == HomeRegion.RECENT_ITEM && pressed.index == index
            drawRow(canvas, box, layout, item, rowPressed, thumbnails)
        }
    }

    /** "Queue · 2 items": the link's text, shared with the layout so its tap target fits it. */
    fun queueLink(count: Int): String =
        context.resources.getQuantityString(R.plurals.car_home_queue_items, count, count)

    private fun drawRow(
        canvas: Canvas,
        box: MenuBox,
        layout: HomeDashboardLayout,
        item: SentItem,
        pressed: Boolean,
        thumbnails: Thumbnails
    ) {
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = if (pressed) HomeDashboardTheme.CARD_PRESSED else HomeDashboardTheme.CARD
        val radius = layout.dp(HomeDashboardTheme.Dp.ROW_RADIUS)
        canvas.drawRoundRect(rect, radius, radius, fill)

        val icon = layout.dp(HomeDashboardTheme.Dp.ROW_ICON)
        val start = box.left + layout.dp(HomeDashboardTheme.Dp.ROW_PADDING_START)
        val tile = MenuBox(start, box.centerY - icon / 2f, start + icon, box.centerY + icon / 2f)
        val iconRadius = layout.dp(HomeDashboardTheme.Dp.ROW_ICON_RADIUS)
        // A real still when the source has one; the source's tinted glyph tile otherwise.
        val bitmap = thumbnails.bitmap(item.artwork.thumbnailUrl)
        if (bitmap != null) drawThumbnail(canvas, tile, iconRadius, item.artwork, thumbnails)
        else card.drawIconTile(canvas, tile, item.artwork.glyph, item.artwork.accent, iconRadius, layout.density)

        title.textSize = layout.dp(HomeDashboardTheme.Dp.ROW_TITLE)
        meta.textSize = layout.dp(HomeDashboardTheme.Dp.ROW_META)
        val textLeft = tile.right + layout.dp(HomeDashboardTheme.Dp.HERO_INNER_GAP)
        val textRight = box.right - layout.dp(HomeDashboardTheme.Dp.ROW_PADDING_END)
        if (textRight - textLeft < layout.dp(8f)) return
        val titleLine = lineHeight(title)
        val metaLine = lineHeight(meta)
        var y = box.centerY - (titleLine + metaLine) / 2f
        drawEllipsized(canvas, item.title, title, textLeft, textRight, y, titleLine)
        y += titleLine
        drawEllipsized(canvas, item.meta, meta, textLeft, textRight, y, metaLine)
    }

    // -------------------------------------------------------------------------- primitives

    /**
     * The thumbnail for [artwork], or the fallback panel when its source publishes no still and
     * when one is still downloading. A bitmap is centre-cropped so a 16:9 still never stretches.
     */
    private fun drawThumbnail(
        canvas: Canvas,
        box: MenuBox,
        radius: Float,
        artwork: Artwork,
        thumbnails: Thumbnails
    ) {
        rect.set(box.left, box.top, box.right, box.bottom)
        val bitmap = thumbnails.bitmap(artwork.thumbnailUrl)
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            artworkPaint.shader = artworkShader(artwork.accent, box)
            canvas.drawRoundRect(rect, radius, radius, artworkPaint)
            artworkPaint.shader = null
            val glyph = min(box.width, box.height) * 0.75f
            card.drawGlyph(
                canvas,
                MenuBox(box.centerX - glyph / 2f, box.centerY - glyph / 2f, box.centerX + glyph / 2f, box.centerY + glyph / 2f),
                artwork.glyph,
                artwork.accent
            )
            return
        }

        val scale = maxOf(box.width / bitmap.width, box.height / bitmap.height)
        val cropWidth = (box.width / scale).coerceAtMost(bitmap.width.toFloat())
        val cropHeight = (box.height / scale).coerceAtMost(bitmap.height.toFloat())
        source.set(
            ((bitmap.width - cropWidth) / 2f).roundToInt(),
            ((bitmap.height - cropHeight) / 2f).roundToInt(),
            ((bitmap.width + cropWidth) / 2f).roundToInt(),
            ((bitmap.height + cropHeight) / 2f).roundToInt()
        )
        canvas.save()
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, source, rect, image)
        canvas.restore()
    }

    private fun drawScrollButton(canvas: Canvas, box: MenuBox, layout: HomeDashboardLayout, up: Boolean, enabled: Boolean) {
        val d = layout.density
        val radius = box.width / 2f
        fill.color = HomeDashboardTheme.CONTROL
        canvas.drawCircle(box.centerX, box.centerY, radius, fill)

        val half = radius * 0.30f
        val rise = half * 0.55f * if (up) 1f else -1f
        chevron.reset()
        chevron.moveTo(box.centerX - half, box.centerY + rise)
        chevron.lineTo(box.centerX, box.centerY - rise)
        chevron.lineTo(box.centerX + half, box.centerY + rise)
        stroke.color = if (enabled) HomeDashboardTheme.TEXT_PRIMARY
        else HomeDashboardTheme.withAlpha(HomeDashboardTheme.TEXT_SECONDARY, 0.35f)
        stroke.strokeWidth = 3f * d
        canvas.drawPath(chevron, stroke)
    }

    // ------------------------------------------------------------------------------ helpers

    private fun lineHeight(paint: Paint): Float {
        val metrics = paint.fontMetrics
        return metrics.descent - metrics.ascent
    }

    private fun baselineIn(paint: Paint, box: MenuBox): Float {
        val metrics = paint.fontMetrics
        return box.top + (box.height - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
    }

    private fun drawEllipsized(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        left: Float,
        right: Float,
        top: Float,
        height: Float
    ) {
        if (text.isBlank() || right <= left) return
        val clipped = TextUtils.ellipsize(text, paint, right - left, TextUtils.TruncateAt.END)
        canvas.drawText(clipped, 0, clipped.length, left, baselineIn(paint, MenuBox(left, top, right, top + height)), paint)
    }

    /**
     * Fallback artwork ramp for [accent], cached by colour and box height.
     *
     * A [LinearGradient] cannot be resized, so one is kept per (accent, height) pair; the set is
     * bounded by the number of accents times the two or three thumbnail sizes a layout uses.
     */
    private fun artworkShader(accent: Int, box: MenuBox): LinearGradient {
        val key = (accent.toLong() shl 20) or box.height.roundToInt().toLong()
        return shaders.getOrPut(key) {
            LinearGradient(
                0f, 0f, 0f, box.height,
                HomeDashboardTheme.mix(HomeDashboardTheme.CARD, accent, HomeDashboardTheme.ARTWORK_TINT_TOP),
                HomeDashboardTheme.mix(HomeDashboardTheme.CARD, accent, HomeDashboardTheme.ARTWORK_TINT_BOTTOM),
                Shader.TileMode.CLAMP
            )
        }.also {
            // The cached ramp is authored at the origin; move it onto this box.
            shaderMatrix.setTranslate(0f, box.top)
            it.setLocalMatrix(shaderMatrix)
        }
    }

    private val shaderMatrix = android.graphics.Matrix()

    private companion object {
        const val WORD_AUTO = "Auto"
        const val WORD_BRIDGE = "Bridge"
    }
}
