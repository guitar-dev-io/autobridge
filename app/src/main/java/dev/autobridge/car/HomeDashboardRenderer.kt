package dev.autobridge.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
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
import dev.autobridge.design.HomeLayout
import dev.autobridge.design.ThemeHolder
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.Wallpaper
import dev.autobridge.settings.AppearanceStore
import kotlin.math.min
import kotlin.math.roundToInt
import dev.autobridge.ui.AppFonts
import dev.autobridge.display.SystemReading
import dev.autobridge.display.SystemStats
import java.util.Locale

/**
 * Paints the whole AutoBridge home onto the car surface: background, header, the Now Playing card
 * with its controls (or its empty state), the quick-access card grid, the Recently Sent row with the
 * queue button and, only when the column cannot fit, the scroll buttons.
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
     * @param paused it is the live session, paused: the caption says "Paused" rather than "Continue".
     * @param nextEnabled there is something for the next button to play.
     */
    data class State(
        val focused: Int = -1,
        val pressed: HomeHit? = null,
        val scroll: Float = 0f,
        val playing: Boolean = false,
        val paused: Boolean = false,
        val nextEnabled: Boolean = false,
        /** CPU, memory and temperature for the header; null hides the line. */
        val status: SystemReading? = null
    )

    /** Resolves a thumbnail URL to a decoded bitmap, or null while there is none to draw. */
    fun interface Thumbnails {
        fun bitmap(url: String?): Bitmap?
    }

    private val logo: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_autobridge_launcher)
    private val card = HomeMenuCard()

    private val wordmark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectionTitle = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val pillText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val emptyTitle = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val heroTitle = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val caption = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val meta = TextPaint(Paint.ANTI_ALIAS_FLAG)
    /** The header's system line: figures keep their width as they change, so the line does not shake. */
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }

    /** The family the paints above were last given; see [syncFonts]. */
    private var fontFace: Typeface? = null

    init {
        syncFonts()
    }

    /**
     * Gives the text paints the car's font: the Appearance family and Thai style, or the defaults
     * when "Use this look on Android Auto" is off. Cheap when nothing changed (one lookup), so it
     * runs before every measure and draw, and a font picked on the phone shows on the next frame.
     */
    private fun syncFonts() {
        val spec = ThemeHolder.spec
        val car = if (spec.carSameLook) spec else AppearanceSpec.DEFAULT
        val face = AppFonts.forChoice(car.font, car.thai)
        if (face === fontFace && face != null) return
        fontFace = face
        val bold = Typeface.create(face, 700, false)
        val medium = Typeface.create(face, 500, false)
        val regular = Typeface.create(face, 400, false)
        listOf(wordmark, sectionTitle, pillText, emptyTitle, heroTitle, card.label).forEach { it.typeface = bold }
        title.typeface = medium
        statusPaint.typeface = medium
        listOf(caption, meta).forEach { it.typeface = regular }
        heroTitleLayout = null
        heroTitleKey = null
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    /** Dashed outline of the empty states; its dash is set per frame from the layout's scale. */
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var dashUnit = 0f
    private val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val source = Rect()
    private val path = Path()
    private val chevron = Path()

    /** Fallback-artwork ramps, keyed by accent and rounded height; see [artworkShader]. */
    private val shaders = HashMap<Long, LinearGradient>()
    /** The card colour [shaders] were mixed with; a new one empties the cache. */
    private var shaderSurface = 0

    /** The look's wallpaper with its dim, drawn once per look and surface size; see [wallpaper]. */
    private var wallpaperBitmap: Bitmap? = null
    private var wallpaperKey: String? = null

    /** The look this frame paints with; set at the top of [draw]. */
    private var look = HomeDashboardLook.DEFAULT

    /** The hero title wrapped to two lines, kept until its text, width or size changes. */
    private var heroTitleLayout: StaticLayout? = null
    private var heroTitleKey: String? = null

    /** Card label width at a text size, measured with the exact paint the cards draw with. */
    fun measureLabel(text: String, size: Float): Float {
        syncFonts()
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
        syncFonts()
        applyLook(layout.look)
        drawBackground(canvas)
        drawHeader(canvas, layout)
        state.status?.let { drawStatus(canvas, layout, it) }

        canvas.save()
        val viewport = layout.viewport
        // Room for the focus halo, which straddles the card edge.
        val halo = HomeDashboardTheme.Dp.FOCUS_GLOW * layout.density
        canvas.clipRect(viewport.left - halo, viewport.top, viewport.right + halo, viewport.bottom + halo)
        canvas.translate(0f, -state.scroll)

        val hero = layout.hero
        val item = content.continueWatching
        if (hero != null && item != null) drawHero(canvas, hero, layout, item, state, thumbnails)
        layout.emptyHero?.let { drawEmptyHero(canvas, it, layout) }
        val strip = layout.strip
        if (strip != null && item != null) drawStrip(canvas, strip, layout, item, state, thumbnails)
        layout.emptyStrip?.let { drawEmptyStrip(canvas, it, layout) }

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

        if (layout.kind == HomeLayout.DASHBOARD) drawRecent(canvas, layout, content, state, thumbnails)

        canvas.restore()

        layout.scrollUp?.let { drawScrollButton(canvas, it, layout, up = true, enabled = state.scroll > 0f) }
        layout.scrollDown?.let {
            drawScrollButton(canvas, it, layout, up = false, enabled = state.scroll < layout.maxScroll)
        }
    }

    // --------------------------------------------------------------------------- background

    /** Text colours that only change with the look; sizes are set where each text is drawn. */
    private fun applyLook(next: HomeDashboardLook) {
        look = next
        sectionTitle.color = next.textSection
        emptyTitle.color = next.textPrimary
        heroTitle.color = next.textPrimary
        title.color = next.textPrimary
        meta.color = next.textSecondary
    }

    /** The theme background, then the wallpaper and its dim when the look shows one on the car. */
    private fun drawBackground(canvas: Canvas) {
        canvas.drawColor(look.background)
        if (look.wallpaper == Wallpaper.NONE) return
        wallpaper(canvas.width, canvas.height)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /**
     * The wallpaper at the surface's size, dim included, painted once and kept until the look or
     * the size changes: the dashboard repaints on every scroll pixel and must not build gradients
     * or decode a photo inside a frame. "Your photo" is also redrawn when the file changes.
     */
    private fun wallpaper(width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val photo = if (look.wallpaper == Wallpaper.CUSTOM) AppearanceStore.customWallpaperFile(context) else null
        val key = "${look.wallpaper}|${look.wallpaperDim}|${look.palette}|$width|$height|${photo?.lastModified()}"
        if (key == wallpaperKey) return wallpaperBitmap
        wallpaperKey = key
        wallpaperBitmap?.recycle()
        wallpaperBitmap = runCatching { paintWallpaper(width, height, photo) }
            .onFailure { android.util.Log.w(TAG, "wallpaper failed: ${it.message}") }
            .getOrNull()
        return wallpaperBitmap
    }

    private fun paintWallpaper(width: Int, height: Int, photo: java.io.File?): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val light = look.palette.isLight
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(look.background)
        when (look.wallpaper) {
            Wallpaper.NONE -> Unit
            Wallpaper.GLOW -> {
                // An accent glow from the top-right corner: an ellipse 90% wide and 45% tall,
                // fading out by 70% of its radius, as the design's radial gradient draws it.
                val rx = w * 0.9f
                val ry = h * 0.45f
                paint.shader = RadialGradient(
                    0f, 0f, rx,
                    intArrayOf(HomeDashboardTheme.withAlpha(look.accent, 0x30 / 255f), look.accent and 0x00FFFFFF),
                    floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP
                ).also {
                    val m = android.graphics.Matrix()
                    m.setScale(1f, ry / rx)
                    m.postTranslate(w * 0.9f, 0f)
                    it.setLocalMatrix(m)
                }
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            Wallpaper.LINES -> {
                // Hairlines at 135 degrees, 16dp apart.
                val d = context.resources.displayMetrics.density.coerceAtLeast(1f)
                val step = 16f * d
                paint.color = HomeDashboardTheme.withAlpha(look.palette.hairline, 0.5f)
                paint.strokeWidth = d
                var x = -h
                while (x < w) {
                    canvas.drawLine(x, 0f, x + h, h, paint)
                    x += step
                }
            }
            Wallpaper.NIGHT_ROAD -> {
                paint.shader = LinearGradient(
                    0f, 0f, 0f, h,
                    if (light) HomeDashboardLook.NIGHT_ROAD_LIGHT else HomeDashboardLook.NIGHT_ROAD_DARK,
                    if (light) HomeDashboardLook.NIGHT_ROAD_LIGHT_STOPS else HomeDashboardLook.NIGHT_ROAD_DARK_STOPS,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, w, h, paint)
            }
            Wallpaper.SOLID -> canvas.drawColor(if (light) HomeDashboardLook.SOLID_LIGHT else HomeDashboardLook.SOLID_DARK)
            Wallpaper.CUSTOM -> {
                val image = photo?.takeIf { it.isFile }?.let { decodeCovering(it, width, height) }
                if (image != null) {
                    val scale = maxOf(w / image.width, h / image.height)
                    val cw = w / scale
                    val ch = h / scale
                    source.set(
                        ((image.width - cw) / 2f).roundToInt(), ((image.height - ch) / 2f).roundToInt(),
                        ((image.width + cw) / 2f).roundToInt(), ((image.height + ch) / 2f).roundToInt()
                    )
                    canvas.drawBitmap(image, source, RectF(0f, 0f, w, h), paint)
                    image.recycle()
                } else {
                    // The diagonal ramp "Your photo" shows in the design until a photo is picked.
                    paint.shader = LinearGradient(
                        0f, 0f, w * 0.34f, h,
                        HomeDashboardLook.CUSTOM_FALLBACK, HomeDashboardLook.CUSTOM_FALLBACK_STOPS, Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, w, h, paint)
                }
            }
        }
        if (look.wallpaperDim > 0) {
            val veil = if (light) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            canvas.drawColor(HomeDashboardTheme.withAlpha(veil, look.wallpaperDim / 100f))
        }
        return bitmap
    }

    /** The photo decoded at no more than twice the size it has to cover, so a camera image fits. */
    private fun decodeCovering(file: java.io.File, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Recently Sent rows: a step rounder than the look's buttons, as the design draws them. */
    private fun rowRadius(layout: HomeDashboardLayout) = layout.dp(look.buttonRadius + 4f)

    // ------------------------------------------------------------------------------- header

    private fun drawHeader(canvas: Canvas, layout: HomeDashboardLayout) {
        val box = layout.logo
        logo?.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        logo?.draw(canvas)

        wordmark.textSize = layout.titleSize
        val metrics = wordmark.fontMetrics
        // Centre the cap-height block on the logo, not the full ascent/descent box.
        val baseline = box.centerY - (metrics.ascent + metrics.descent) / 2f
        wordmark.color = look.textPrimary
        canvas.drawText(WORD_AUTO, layout.titleX, baseline, wordmark)
        wordmark.color = look.accent
        canvas.drawText(WORD_BRIDGE, layout.titleX + wordmark.measureText(WORD_AUTO), baseline, wordmark)
    }

    /**
     * "CPU 12% · RAM 61% · 38°C", right-aligned on the header line. The temperature turns warm or
     * hot with the system's thermal status; when the line would run into the wordmark, CPU then
     * RAM are dropped so the temperature, the figure that matters on a hot dashboard, stays.
     */
    private fun drawStatus(canvas: Canvas, layout: HomeDashboardLayout, reading: SystemReading) {
        statusPaint.textSize = layout.sp(HomeDashboardTheme.Dp.EMPTY_HINT)
        val heat = when (reading.heat) {
            SystemStats.Heat.OK -> look.textSecondary
            SystemStats.Heat.WARM -> look.palette.warn
            SystemStats.Heat.HOT -> look.palette.danger
        }
        val parts = ArrayList<Pair<String, Int>>(3)
        reading.cpuPercent?.let { parts += "CPU $it%" to look.textSecondary }
        parts += "RAM ${reading.ramPercent}%" to look.textSecondary
        reading.tempC?.let { parts += String.format(Locale.US, "%.0f\u00B0C", it) to heat }

        val separator = "  \u00B7  "
        val separatorWidth = statusPaint.measureText(separator)
        fun width(list: List<Pair<String, Int>>) =
            list.sumOf { statusPaint.measureText(it.first).toDouble() }.toFloat() + separatorWidth * (list.size - 1).coerceAtLeast(0)

        wordmark.textSize = layout.titleSize
        val titleEnd = layout.titleX + wordmark.measureText(WORD_AUTO + WORD_BRIDGE)
        val right = layout.viewport.right
        val room = right - titleEnd - layout.dp(16f)
        while (parts.size > 1 && width(parts) > room) parts.removeAt(0)
        if (width(parts) > room) return

        val metrics = statusPaint.fontMetrics
        val baseline = layout.logo.centerY - (metrics.ascent + metrics.descent) / 2f
        var x = right - width(parts)
        parts.forEachIndexed { i, (text, color) ->
            if (i > 0) {
                statusPaint.color = look.textSecondary
                canvas.drawText(separator, x, baseline, statusPaint)
                x += separatorWidth
            }
            statusPaint.color = color
            canvas.drawText(text, x, baseline, statusPaint)
            x += statusPaint.measureText(text)
        }
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
        card.drawBody(
            canvas, hero.card, heroRadius(layout), look,
            if (cardPressed) HomeMenuCard.State.PRESSED else HomeMenuCard.State.NORMAL, layout.density
        )

        drawThumbnail(canvas, hero.art, layout.dp(look.artRadius), item.artwork, thumbnails)

        // Caption ("YouTube · Now playing") in the source's accent, then the title on up to two lines.
        caption.textSize = layout.sp(HomeDashboardTheme.Dp.HERO_CAPTION)
        caption.color = look.hue(item.artwork.accent)
        heroTitle.textSize = layout.sp(layout.profile.heroTitle)
        val captionText = captionText(item, state)
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
        fill.color = look.progressTrack
        rect.set(bar.left, bar.top, bar.right, bar.bottom)
        canvas.drawRoundRect(rect, barRadius, barRadius, fill)
        item.progress?.let { progress ->
            fill.color = look.accent
            rect.set(bar.left, bar.top, bar.left + bar.width * progress, bar.bottom)
            canvas.drawRoundRect(rect, barRadius, barRadius, fill)
        }
        meta.textSize = layout.sp(HomeDashboardTheme.Dp.HERO_TIME)
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

    /** "YouTube · Now playing": the source, then whether it plays, is paused or can be resumed. */
    private fun captionText(item: ContinueItem, state: State): String {
        val status = context.getString(
            when {
                state.playing -> R.string.car_home_now_playing
                state.paused -> R.string.car_home_paused
                else -> R.string.car_home_continue_watching
            }
        )
        return listOf(item.sourceLabel, status).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** The Now Playing card and its empty state are a step rounder than the grid's cards. */
    private fun heroRadius(layout: HomeDashboardLayout) = layout.dp(look.cardRadius + HERO_EXTRA_RADIUS)

    // ------------------------------------------------------------- Compact now-playing strip

    private fun drawStrip(
        canvas: Canvas,
        strip: StripBoxes,
        layout: HomeDashboardLayout,
        item: ContinueItem,
        state: State,
        thumbnails: Thumbnails
    ) {
        val pressed = state.pressed?.region
        rect.set(strip.card.left, strip.card.top, strip.card.right, strip.card.bottom)
        fill.color = if (pressed == HomeRegion.CONTINUE) look.controlPressed else look.control
        val radius = layout.dp(look.buttonRadius)
        canvas.drawRoundRect(rect, radius, radius, fill)

        drawThumbnail(canvas, strip.art, layout.dp(look.chipRadius(HomeDashboardTheme.Dp.COMPACT_STRIP_ART)), item.artwork, thumbnails)

        title.textSize = layout.sp(HomeDashboardTheme.Dp.COMPACT_TITLE)
        meta.textSize = layout.sp(HomeDashboardTheme.Dp.COMPACT_META)
        val titleLine = lineHeight(title)
        val metaLine = lineHeight(meta)
        var y = strip.text.centerY - (titleLine + metaLine) / 2f
        drawEllipsized(canvas, item.title, title, strip.text.left, strip.text.right, y, titleLine)
        y += titleLine
        drawEllipsized(canvas, captionText(item, state), meta, strip.text.left, strip.text.right, y, metaLine)

        drawControl(canvas, strip.play, layout, if (state.playing) Glyph.PAUSE else Glyph.PLAY, primary = true,
            enabled = true, pressed = pressed == HomeRegion.CONTROL_PLAY)
    }

    /** The strip's place with nothing to play: an outline that says so, not a button. */
    private fun drawEmptyStrip(canvas: Canvas, box: MenuBox, layout: HomeDashboardLayout) {
        dashedRoundRect(canvas, box, layout.dp(look.buttonRadius), look.emptyOutline, layout)
        val iconSize = layout.dp(24f)
        val start = box.left + layout.dp(24f)
        stroke.color = look.textDisabled
        stroke.strokeWidth = 2f * iconSize / 24f
        icon(canvas, Icon.PHONE, start, box.centerY - iconSize / 2f, iconSize)
        meta.textSize = layout.sp(HomeDashboardTheme.Dp.COMPACT_META)
        meta.color = look.textDisabled
        drawEllipsized(
            canvas, context.getString(R.string.car_home_nothing_playing), meta,
            start + iconSize + layout.dp(16f), box.right - layout.dp(20f), box.top, box.height
        )
        meta.color = look.textSecondary
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
            primary && pressed -> HomeDashboardTheme.mix(look.accent, look.textPrimary, 0.35f)
            primary -> look.accent
            pressed -> look.controlPressed
            else -> look.control
        }
        canvas.drawCircle(box.centerX, box.centerY, radius, fill)

        val color = when {
            primary -> look.onAccent
            enabled -> look.textPrimary
            else -> look.controlDisabledGlyph
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

        // Section title, with a chevron when it opens the full list.
        val title = layout.sectionTitle
        val headerPressed = pressed?.region == HomeRegion.RECENT_HEADER
        sectionTitle.textSize = layout.sp(HomeDashboardTheme.Dp.SECTION_TITLE)
        sectionTitle.color = if (headerPressed) look.accent else look.textSection
        if (title != null) canvas.drawText(context.getString(R.string.car_home_recent_from_phone), title.left, baselineIn(sectionTitle, title), sectionTitle)
        layout.recentHeader?.let { box ->
            val size = layout.dp(HomeDashboardTheme.Dp.SECTION_CHEVRON)
            stroke.color = if (headerPressed) look.accent else look.textSecondary
            stroke.strokeWidth = 2f * size / 24f
            icon(canvas, Icon.CHEVRON, box.right - size, box.centerY - size / 2f, size)
        }

        layout.queueHeader?.let { box ->
            drawQueuePill(canvas, box, layout, queueLink(content.queueTotal), pressed?.region == HomeRegion.QUEUE_HEADER)
        }
        layout.queueEmpty?.let { box -> drawQueueEmpty(canvas, box, layout) }

        content.recentlySent.forEachIndexed { index, item ->
            val box = layout.recentRows.getOrNull(index) ?: return@forEachIndexed
            val rowPressed = pressed?.region == HomeRegion.RECENT_ITEM && pressed.index == index
            drawRow(canvas, box, layout, item, rowPressed, thumbnails)
        }
        layout.emptyRecent?.let { box ->
            dashedRoundRect(canvas, box, rowRadius(layout), look.emptyOutline, layout)
            val iconSize = layout.dp(24f)
            val start = box.left + layout.dp(20f)
            stroke.color = look.textDisabled
            stroke.strokeWidth = 2f * iconSize / 24f
            icon(canvas, Icon.SEND, start, box.centerY - iconSize / 2f, iconSize)
            meta.textSize = layout.sp(HomeDashboardTheme.Dp.ROW_TITLE)
            val textLeft = start + iconSize + layout.dp(14f)
            drawEllipsized(
                canvas, context.getString(R.string.car_home_nothing_sent), meta,
                textLeft, box.right - layout.dp(20f), box.top, box.height
            )
        }
    }

    /** "Queue · 2 items": the button's text, shared with the layout so its tap target fits it. */
    fun queueLink(count: Int): String =
        if (count > 0) context.resources.getQuantityString(R.plurals.car_home_queue_items, count, count)
        else context.getString(R.string.car_home_queue_empty)

    private fun drawQueuePill(canvas: Canvas, box: MenuBox, layout: HomeDashboardLayout, label: String, pressed: Boolean) {
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = if (pressed) look.queuePillPressed else look.queuePill
        canvas.drawRoundRect(rect, box.height / 2f, box.height / 2f, fill)

        var x = box.left + layout.dp(HomeDashboardTheme.Dp.QUEUE_PILL_PADDING_START)
        val iconSize = layout.dp(HomeDashboardTheme.Dp.QUEUE_PILL_ICON)
        stroke.color = look.queuePillText
        fill.color = look.queuePillText
        stroke.strokeWidth = 2f * iconSize / 24f
        icon(canvas, Icon.QUEUE, x, box.centerY - iconSize / 2f, iconSize)
        x += iconSize + layout.dp(HomeDashboardTheme.Dp.QUEUE_PILL_GAP)

        pillText.textSize = layout.sp(HomeDashboardTheme.Dp.SECTION_LINK)
        pillText.color = look.queuePillText
        canvas.drawText(label, x, baselineIn(pillText, box), pillText)

        val chevron = layout.dp(HomeDashboardTheme.Dp.QUEUE_PILL_CHEVRON)
        stroke.color = look.queuePillText
        stroke.strokeWidth = 2f * chevron / 24f
        icon(canvas, Icon.CHEVRON, box.right - layout.dp(HomeDashboardTheme.Dp.QUEUE_PILL_PADDING_END) - chevron,
            box.centerY - chevron / 2f, chevron)
    }

    /** The queue button's place when the queue is empty: an outline that says so, not a button. */
    private fun drawQueueEmpty(canvas: Canvas, box: MenuBox, layout: HomeDashboardLayout) {
        dashedRoundRect(canvas, box, box.height / 2f, look.emptyPillOutline, layout, width = 1f)
        var x = box.left + layout.dp(HomeDashboardTheme.Dp.QUEUE_PILL_PADDING_START)
        val iconSize = layout.dp(HomeDashboardTheme.Dp.QUEUE_PILL_ICON)
        stroke.color = look.textDisabled
        fill.color = look.textDisabled
        stroke.strokeWidth = 2f * iconSize / 24f
        icon(canvas, Icon.QUEUE, x, box.centerY - iconSize / 2f, iconSize)
        x += iconSize + layout.dp(HomeDashboardTheme.Dp.QUEUE_PILL_GAP)
        pillText.textSize = layout.sp(HomeDashboardTheme.Dp.SECTION_LINK)
        pillText.color = look.textDisabled
        canvas.drawText(queueLink(0), x, baselineIn(pillText, box), pillText)
    }

    /** "Nothing playing yet" in the Now Playing slot, so the grid beside it never moves. */
    private fun drawEmptyHero(canvas: Canvas, box: MenuBox, layout: HomeDashboardLayout) {
        dashedRoundRect(canvas, box, heroRadius(layout), look.emptyOutline, layout)

        val pad = layout.dp(HomeDashboardTheme.Dp.EMPTY_PADDING)
        val gap = layout.dp(HomeDashboardTheme.Dp.EMPTY_GAP)
        val tile = layout.dp(HomeDashboardTheme.Dp.EMPTY_ICON)
        emptyTitle.textSize = layout.sp(HomeDashboardTheme.Dp.EMPTY_TITLE)
        meta.textSize = layout.sp(HomeDashboardTheme.Dp.EMPTY_HINT)
        val titleLine = lineHeight(emptyTitle)
        val hintLines = context.getString(R.string.car_home_nothing_playing_hint).split('\n')
        val hintLine = meta.textSize * 1.5f
        val block = tile + gap + titleLine + gap + hintLine * hintLines.size
        val left = box.left + pad
        val right = box.right - pad
        var y = box.centerY - block / 2f

        rect.set(left, y, left + tile, y + tile)
        fill.color = look.accentSoft
        val tileRadius = layout.dp(look.chipRadius(HomeDashboardTheme.Dp.EMPTY_ICON))
        canvas.drawRoundRect(rect, tileRadius, tileRadius, fill)
        val glyph = tile * 34f / 72f
        stroke.color = look.accent
        stroke.strokeWidth = 2f * glyph / 24f
        icon(canvas, Icon.PHONE, left + (tile - glyph) / 2f, y + (tile - glyph) / 2f, glyph)
        y += tile + gap

        drawEllipsized(canvas, context.getString(R.string.car_home_nothing_playing), emptyTitle, left, right, y, titleLine)
        y += titleLine + gap
        hintLines.forEach { line ->
            drawEllipsized(canvas, line, meta, left, right, y, hintLine)
            y += hintLine
        }
    }

    private fun dashedRoundRect(
        canvas: Canvas,
        box: MenuBox,
        radius: Float,
        color: Int,
        layout: HomeDashboardLayout,
        width: Float = HomeDashboardTheme.Dp.EMPTY_BORDER
    ) {
        if (dashUnit != layout.unit) {
            val dash = layout.dp(HomeDashboardTheme.Dp.EMPTY_DASH)
            dashed.pathEffect = DashPathEffect(floatArrayOf(dash, dash * 0.75f), 0f)
            dashUnit = layout.unit
        }
        dashed.color = color
        dashed.strokeWidth = width * layout.density
        rect.set(box.left, box.top, box.right, box.bottom)
        rect.inset(dashed.strokeWidth / 2f, dashed.strokeWidth / 2f)
        canvas.drawRoundRect(rect, radius, radius, dashed)
    }

    private enum class Icon { CHEVRON, QUEUE, PHONE, SEND }

    /**
     * The small line icons of the section, on the design's 24-unit grid at ([left], [top]) and
     * [size] square. Strokes use [stroke] as the caller set it; QUEUE's play mark uses [fill].
     */
    private fun icon(canvas: Canvas, kind: Icon, left: Float, top: Float, size: Float) {
        val k = size / 24f
        fun x(v: Float) = left + v * k
        fun y(v: Float) = top + v * k
        path.reset()
        when (kind) {
            Icon.CHEVRON -> {
                path.moveTo(x(9f), y(5f)); path.lineTo(x(16f), y(12f)); path.lineTo(x(9f), y(19f))
                canvas.drawPath(path, stroke)
            }
            Icon.SEND -> {
                path.moveTo(x(4f), y(12f)); path.lineTo(x(16f), y(12f))
                path.moveTo(x(12f), y(6f)); path.lineTo(x(18f), y(12f)); path.lineTo(x(12f), y(18f))
                canvas.drawPath(path, stroke)
            }
            Icon.QUEUE -> {
                canvas.drawLine(x(4f), y(6f), x(16f), y(6f), stroke)
                canvas.drawLine(x(4f), y(12f), x(16f), y(12f), stroke)
                canvas.drawLine(x(4f), y(18f), x(12f), y(18f), stroke)
                path.moveTo(x(16f), y(15f)); path.lineTo(x(21f), y(18f)); path.lineTo(x(16f), y(21f)); path.close()
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
            Icon.PHONE -> {
                rect.set(x(6f), y(2.5f), x(18f), y(21.5f))
                canvas.drawRoundRect(rect, 2.5f * k, 2.5f * k, stroke)
                canvas.drawLine(x(10.5f), y(18.5f), x(13.5f), y(18.5f), stroke)
            }
        }
    }

    private fun drawRow(
        canvas: Canvas,
        box: MenuBox,
        layout: HomeDashboardLayout,
        item: SentItem,
        pressed: Boolean,
        thumbnails: Thumbnails
    ) {
        card.drawBody(
            canvas, box, rowRadius(layout), look,
            if (pressed) HomeMenuCard.State.PRESSED else HomeMenuCard.State.NORMAL, layout.density
        )

        val icon = layout.dp(HomeDashboardTheme.Dp.ROW_ICON)
        val start = box.left + layout.dp(HomeDashboardTheme.Dp.ROW_PADDING_START)
        val tile = MenuBox(start, box.centerY - icon / 2f, start + icon, box.centerY + icon / 2f)
        val iconRadius = layout.dp(look.chipRadius(HomeDashboardTheme.Dp.ROW_ICON))
        // A real still when the source has one; the source's tinted glyph tile otherwise.
        val bitmap = thumbnails.bitmap(item.artwork.thumbnailUrl)
        if (bitmap != null) drawThumbnail(canvas, tile, iconRadius, item.artwork, thumbnails)
        else card.drawIconTile(canvas, tile, item.artwork.glyph, look.chip(item.artwork.accent), iconRadius, layout.density)

        title.textSize = layout.sp(HomeDashboardTheme.Dp.ROW_TITLE)
        meta.textSize = layout.sp(HomeDashboardTheme.Dp.ROW_META)
        val textLeft = tile.right + layout.dp(HomeDashboardTheme.Dp.ROW_TEXT_GAP)
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
                look.hue(artwork.accent),
                WHITE
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
        fill.color = look.control
        canvas.drawCircle(box.centerX, box.centerY, radius, fill)

        val half = radius * 0.30f
        val rise = half * 0.55f * if (up) 1f else -1f
        chevron.reset()
        chevron.moveTo(box.centerX - half, box.centerY + rise)
        chevron.lineTo(box.centerX, box.centerY - rise)
        chevron.lineTo(box.centerX + half, box.centerY + rise)
        stroke.color = if (enabled) look.textPrimary
        else HomeDashboardTheme.withAlpha(look.textSecondary, 0.35f)
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
        if (shaderSurface != look.surface) {
            shaders.clear()
            shaderSurface = look.surface
        }
        val hue = look.hue(accent)
        val key = ((hue.toLong() and 0xFFFFFFFFL) shl 20) or box.height.roundToInt().toLong()
        return shaders.getOrPut(key) {
            LinearGradient(
                0f, 0f, 0f, box.height,
                HomeDashboardTheme.mix(look.surface, hue, HomeDashboardTheme.ARTWORK_TINT_TOP),
                HomeDashboardTheme.mix(look.surface, hue, HomeDashboardTheme.ARTWORK_TINT_BOTTOM),
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

        /** How much rounder the Now Playing card is than the look's card radius. */
        const val HERO_EXTRA_RADIUS = 2f
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TAG = "HomeDashboard"
    }
}
