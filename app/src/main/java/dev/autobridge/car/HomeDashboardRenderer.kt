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
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import dev.autobridge.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Paints the whole AutoBridge dashboard onto the car surface: background, header, Continue
 * Watching, the quick-access card grid, the Recently Sent and Queue blocks and, only when the
 * column cannot fit, the scroll buttons.
 *
 * Android Auto's own chrome — the side rail, the bottom bar, the clock and the status icons —
 * lives outside this surface and is never drawn or imitated here. The one host control this screen
 * uses is the action strip, which the host itself draws.
 *
 * Every Paint, Path and Typeface is built once and reused; the only per-frame allocations are the
 * ellipsized CharSequences, and gradients for fallback artwork are cached by colour and size. The
 * dashboard re-renders on every scroll pixel, so nothing here may decode a drawable or build a
 * shader inside a frame.
 */
internal class HomeDashboardRenderer(private val context: Context) {

    /**
     * @param focused index of the quick-access card to show as focused, or -1.
     * @param pressed the region currently flashing under a finger, or null.
     */
    data class State(val focused: Int = -1, val pressed: HomeHit? = null, val scroll: Float = 0f)

    /** Resolves a thumbnail URL to a decoded bitmap, or null while there is none to draw. */
    fun interface Thumbnails {
        fun bitmap(url: String?): Bitmap?
    }

    private val logo: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_autobridge_launcher)
    private val card = HomeMenuCard()

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private val wordmark = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium }
    private val sectionTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = medium
        color = HomeDashboardTheme.TEXT_PRIMARY
    }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = medium
        color = HomeDashboardTheme.TEXT_PRIMARY
    }
    private val meta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = regular
        color = HomeDashboardTheme.TEXT_SECONDARY
    }
    private val trailing = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = regular
        color = HomeDashboardTheme.TEXT_SECONDARY
        textAlign = Paint.Align.RIGHT
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

    /** Label width at a text size, measured with the exact paint the cards draw with. */
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
        canvas.clipRect(viewport.left - halo, viewport.top - halo, viewport.right + halo, viewport.bottom + halo)
        canvas.translate(0f, -state.scroll)

        content.continueWatching?.let { item ->
            layout.continueTitle?.let {
                drawSectionTitle(canvas, it, layout, context.getString(R.string.car_home_continue_watching))
            }
            layout.continueCard?.let {
                drawHero(canvas, it, layout, item, state.pressed?.region == HomeRegion.CONTINUE, thumbnails)
            }
        }

        drawSectionTitle(canvas, layout.quickTitle, layout, context.getString(R.string.car_home_quick_access))
        items.forEachIndexed { index, item ->
            val bounds = layout.cards.getOrNull(index) ?: return@forEachIndexed
            val cardState = when {
                state.pressed?.region == HomeRegion.QUICK_ACCESS && state.pressed.index == index ->
                    HomeMenuCard.State.PRESSED
                index == state.focused -> HomeMenuCard.State.FOCUSED
                else -> HomeMenuCard.State.NORMAL
            }
            card.draw(canvas, bounds, item, item.title(context), layout, cardState)
        }

        layout.recentBlock?.let { block ->
            drawBlock(
                canvas, layout, block, layout.recentHeader,
                context.getString(R.string.car_home_recently_sent), count = null,
                headerPressed = state.pressed?.region == HomeRegion.RECENT_HEADER
            )
            content.recentlySent.forEachIndexed { index, item ->
                val bounds = layout.recentRows.getOrNull(index) ?: return@forEachIndexed
                val pressed = state.pressed?.region == HomeRegion.RECENT_ITEM && state.pressed.index == index
                drawRow(
                    canvas, bounds, layout, item.title, item.meta, item.artwork,
                    trailingText = null, playBadge = true, pressed = pressed, thumbnails = thumbnails
                )
            }
        }

        layout.queueBlock?.let { block ->
            drawBlock(
                canvas, layout, block, layout.queueHeader,
                context.getString(R.string.car_home_queue), count = content.queueTotal,
                headerPressed = state.pressed?.region == HomeRegion.QUEUE_HEADER
            )
            content.queue.forEachIndexed { index, item ->
                val bounds = layout.queueRows.getOrNull(index) ?: return@forEachIndexed
                val pressed = state.pressed?.region == HomeRegion.QUEUE_ITEM && state.pressed.index == index
                drawRow(
                    canvas, bounds, layout, item.title, null, item.artwork,
                    trailingText = item.trailing, playBadge = false, pressed = pressed, thumbnails = thumbnails
                )
            }
        }

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
        wordmark.color = HomeDashboardTheme.ACCENT
        canvas.drawText(WORD_BRIDGE, layout.titleX + wordmark.measureText(WORD_AUTO), baseline, wordmark)
    }

    private fun drawSectionTitle(canvas: Canvas, box: MenuBox, layout: HomeDashboardLayout, text: String) {
        sectionTitle.textSize = layout.sectionTitleSize
        canvas.drawText(text, box.left, baselineIn(sectionTitle, box), sectionTitle)
    }

    // ------------------------------------------------------------- Continue Watching (hero)

    private fun drawHero(
        canvas: Canvas,
        box: MenuBox,
        layout: HomeDashboardLayout,
        item: ContinueItem,
        pressed: Boolean,
        thumbnails: Thumbnails
    ) {
        val d = layout.density
        panel(canvas, box, layout.cornerRadius, if (pressed) HomeDashboardTheme.CARD_PRESSED else HomeDashboardTheme.CARD, d, pressed)

        val padding = HomeDashboardTheme.Dp.HERO_PADDING * d
        val thumbHeight = box.height - padding * 2
        val thumbWidth = min(thumbHeight * HomeDashboardTheme.THUMB_ASPECT, box.width * 0.42f)
        val thumb = MenuBox(box.left + padding, box.top + padding, box.left + padding + thumbWidth, box.bottom - padding)
        drawThumbnail(canvas, thumb, layout, item.artwork, thumbnails)
        playBadge(canvas, thumb.centerX, thumb.centerY, (HomeDashboardTheme.Dp.PLAY_BADGE * d)
            .coerceIn(HomeDashboardTheme.Dp.PLAY_BADGE_MIN * d, thumb.height * 0.5f))

        val textLeft = thumb.right + padding * 1.2f
        val textRight = box.right - padding
        if (textRight - textLeft < padding) return

        title.textSize = layout.heroTitleSize
        meta.textSize = layout.heroMetaSize
        trailing.textSize = layout.heroMetaSize

        val titleLine = lineHeight(title)
        val metaLine = lineHeight(meta)
        val gap = HomeDashboardTheme.Dp.HERO_LINE_GAP * d
        val block = titleLine + gap + metaLine + gap + metaLine
        var y = box.centerY - block / 2f

        drawEllipsized(canvas, item.title, title, textLeft, textRight, y, titleLine)
        y += titleLine + gap

        // Source line: the same glyph the fallback artwork uses, then the source's own name.
        val badge = metaLine * 0.95f
        card.drawGlyph(canvas, MenuBox(textLeft, y + (metaLine - badge) / 2f, textLeft + badge, y + (metaLine + badge) / 2f),
            item.artwork.glyph, item.artwork.accent)
        drawEllipsized(canvas, item.sourceLabel, meta, textLeft + badge + gap * 0.7f, textRight, y, metaLine)
        y += metaLine + gap

        // Progress line: the bar takes whatever the elapsed/duration readout leaves.
        val readout = HomeDashboardClock.elapsed(item.positionMs, item.durationMs)
        canvas.drawText(readout, textRight, baselineIn(trailing, MenuBox(textLeft, y, textRight, y + metaLine)), trailing)
        val barRight = textRight - trailing.measureText(readout) - gap
        val progress = item.progress
        if (progress != null && barRight > textLeft) {
            val height = HomeDashboardTheme.Dp.HERO_PROGRESS * d
            val top = y + (metaLine - height) / 2f
            fill.color = HomeDashboardTheme.PROGRESS_TRACK
            rect.set(textLeft, top, barRight, top + height)
            canvas.drawRoundRect(rect, height / 2f, height / 2f, fill)
            fill.color = HomeDashboardTheme.ACCENT
            rect.set(textLeft, top, textLeft + (barRight - textLeft) * progress, top + height)
            canvas.drawRoundRect(rect, height / 2f, height / 2f, fill)
        }
    }

    // ------------------------------------------------------------- Recently Sent / Queue

    private fun drawBlock(
        canvas: Canvas,
        layout: HomeDashboardLayout,
        block: MenuBox,
        header: MenuBox?,
        text: String,
        count: Int?,
        headerPressed: Boolean
    ) {
        val d = layout.density
        panel(canvas, block, layout.blockRadius, HomeDashboardTheme.SURFACE_SUNKEN, d, highlighted = false)
        header ?: return

        sectionTitle.textSize = layout.blockHeaderSize
        sectionTitle.color =
            if (headerPressed) HomeDashboardTheme.ACCENT else HomeDashboardTheme.TEXT_PRIMARY
        val baseline = baselineIn(sectionTitle, header)
        canvas.drawText(text, header.left, baseline, sectionTitle)
        var cursor = header.left + sectionTitle.measureText(text)
        sectionTitle.color = HomeDashboardTheme.TEXT_PRIMARY

        if (count != null && count > 0) {
            val label = count.toString()
            meta.textSize = layout.blockHeaderSize * 0.85f
            val padding = HomeDashboardTheme.Dp.COUNT_PILL_PADDING * d
            val pillHeight = layout.blockHeaderSize * 1.5f
            val pillWidth = (meta.measureText(label) + padding * 2).coerceAtLeast(pillHeight)
            cursor += padding
            rect.set(cursor, header.centerY - pillHeight / 2f, cursor + pillWidth, header.centerY + pillHeight / 2f)
            fill.color = HomeDashboardTheme.withAlpha(HomeDashboardTheme.ACCENT, 0.18f)
            canvas.drawRoundRect(rect, pillHeight / 2f, pillHeight / 2f, fill)
            meta.color = HomeDashboardTheme.ACCENT
            canvas.drawText(label, cursor + (pillWidth - meta.measureText(label)) / 2f, baselineIn(meta, header), meta)
            meta.color = HomeDashboardTheme.TEXT_SECONDARY
        }

        // Chevron: the block's header is the way to the full list behind it.
        val size = HomeDashboardTheme.Dp.CHEVRON * d
        chevron.reset()
        chevron.moveTo(header.right - size * 0.55f, header.centerY - size / 2f)
        chevron.lineTo(header.right - size * 0.05f, header.centerY)
        chevron.lineTo(header.right - size * 0.55f, header.centerY + size / 2f)
        stroke.color =
            if (headerPressed) HomeDashboardTheme.ACCENT else HomeDashboardTheme.TEXT_SECONDARY
        stroke.strokeWidth = 2.2f * d
        canvas.drawPath(chevron, stroke)
    }

    private fun drawRow(
        canvas: Canvas,
        box: MenuBox,
        layout: HomeDashboardLayout,
        text: String,
        metaText: String?,
        artwork: Artwork,
        trailingText: String?,
        playBadge: Boolean,
        pressed: Boolean,
        thumbnails: Thumbnails
    ) {
        val d = layout.density
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = if (pressed) HomeDashboardTheme.ROW_PRESSED else HomeDashboardTheme.ROW
        canvas.drawRoundRect(rect, layout.rowRadius, layout.rowRadius, fill)

        val padding = HomeDashboardTheme.Dp.ROW_PADDING * d
        val thumbHeight = box.height - padding * 2
        val thumbWidth = min(thumbHeight * HomeDashboardTheme.THUMB_ASPECT, box.width * 0.3f)
        val thumb = MenuBox(box.left + padding, box.top + padding, box.left + padding + thumbWidth, box.bottom - padding)
        drawThumbnail(canvas, thumb, layout, artwork, thumbnails)

        title.textSize = layout.rowTitleSize
        meta.textSize = layout.rowMetaSize
        trailing.textSize = layout.rowMetaSize

        var textRight = box.right - padding
        if (playBadge) {
            val badge = min(HomeDashboardTheme.Dp.ROW_PLAY_BADGE * d, thumbHeight)
            val centerX = box.right - padding - badge / 2f
            fill.color = HomeDashboardTheme.withAlpha(HomeDashboardTheme.ACCENT, 0.16f)
            canvas.drawCircle(centerX, box.centerY, badge / 2f, fill)
            fill.color = HomeDashboardTheme.ACCENT
            trianglePath(centerX - badge * 0.12f, box.centerY, badge * 0.30f)
            canvas.drawPath(path, fill)
            textRight = centerX - badge / 2f - padding
        } else if (trailingText != null) {
            canvas.drawText(trailingText, textRight, baselineIn(trailing, box), trailing)
            textRight -= trailing.measureText(trailingText) + padding
        }

        val textLeft = thumb.right + padding * 1.2f
        if (textRight - textLeft < padding) return

        val titleLine = lineHeight(title)
        if (metaText.isNullOrBlank()) {
            drawEllipsized(canvas, text, title, textLeft, textRight, box.centerY - titleLine / 2f, titleLine)
        } else {
            val metaLine = lineHeight(meta)
            var y = box.centerY - (titleLine + metaLine) / 2f
            drawEllipsized(canvas, text, title, textLeft, textRight, y, titleLine)
            y += titleLine
            drawEllipsized(canvas, metaText, meta, textLeft, textRight, y, metaLine)
        }
    }

    // -------------------------------------------------------------------------- primitives

    /** Card/block body: fill, then a hairline border or the AutoBridge blue one when highlighted. */
    private fun panel(canvas: Canvas, box: MenuBox, radius: Float, color: Int, density: Float, highlighted: Boolean) {
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (highlighted) {
            stroke.color = HomeDashboardTheme.ACCENT
            stroke.strokeWidth = HomeDashboardTheme.Dp.FOCUS_BORDER * density
        } else {
            stroke.color = HomeDashboardTheme.BORDER
            stroke.strokeWidth = HomeDashboardTheme.Dp.CARD_BORDER * density
        }
        rect.inset(stroke.strokeWidth / 2f, stroke.strokeWidth / 2f)
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    /**
     * The thumbnail for [artwork], or the fallback panel when its source publishes no still and
     * when one is still downloading. A bitmap is centre-cropped so a 16:9 still never stretches.
     */
    private fun drawThumbnail(
        canvas: Canvas,
        box: MenuBox,
        layout: HomeDashboardLayout,
        artwork: Artwork,
        thumbnails: Thumbnails
    ) {
        val radius = layout.thumbRadius
        rect.set(box.left, box.top, box.right, box.bottom)
        val bitmap = thumbnails.bitmap(artwork.thumbnailUrl)
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            artworkPaint.shader = artworkShader(artwork.accent, box)
            canvas.drawRoundRect(rect, radius, radius, artworkPaint)
            artworkPaint.shader = null
            val glyph = min(box.width, box.height) * 0.55f
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

    /** Translucent disc with a play mark, drawn over a thumbnail. */
    private fun playBadge(canvas: Canvas, centerX: Float, centerY: Float, size: Float) {
        fill.color = HomeDashboardTheme.withAlpha(SCRIM, 0.55f)
        canvas.drawCircle(centerX, centerY, size / 2f, fill)
        stroke.color = HomeDashboardTheme.withAlpha(HomeDashboardTheme.TEXT_PRIMARY, 0.85f)
        stroke.strokeWidth = size * 0.055f
        canvas.drawCircle(centerX, centerY, size / 2f - stroke.strokeWidth / 2f, stroke)
        fill.color = HomeDashboardTheme.TEXT_PRIMARY
        trianglePath(centerX, centerY, size * 0.22f)
        canvas.drawPath(path, fill)
    }

    /** Right-pointing play triangle of half-height [size], centred on ([centerX], [centerY]). */
    private fun trianglePath(centerX: Float, centerY: Float, size: Float) {
        path.reset()
        path.moveTo(centerX - size * 0.6f, centerY - size)
        path.lineTo(centerX - size * 0.6f, centerY + size)
        path.lineTo(centerX + size, centerY)
        path.close()
    }

    private fun drawScrollButton(canvas: Canvas, box: MenuBox, layout: HomeDashboardLayout, up: Boolean, enabled: Boolean) {
        val d = layout.density
        val radius = box.width / 2f
        fill.color = HomeDashboardTheme.CARD
        canvas.drawCircle(box.centerX, box.centerY, radius, fill)
        stroke.color = HomeDashboardTheme.BORDER
        stroke.strokeWidth = HomeDashboardTheme.Dp.CARD_BORDER * d
        canvas.drawCircle(box.centerX, box.centerY, radius - stroke.strokeWidth / 2f, stroke)

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

        /** Black, used only through [HomeDashboardTheme.withAlpha] for the play-badge scrim. */
        const val SCRIM = 0xFF000000.toInt()
    }
}
