package dev.autobridge.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.autobridge.R
import dev.autobridge.display.CarSurfaceCanvas
import dev.autobridge.iptv.RadioPresetsStore
import dev.autobridge.logging.StructuredLog
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.radio.RadioSourceType
import dev.autobridge.radio.RadioStation
import dev.autobridge.ui.ImageLoader

/**
 * The car radio Now Playing surface: the shared MediaSession's selected station drawn large, with
 * its artwork, name, source badge, a prev / play-pause / next transport and the six presets as a
 * row along the bottom.
 *
 * It reuses the existing car rendering split rather than inventing a new one. On a Car API 5+ host
 * — which is the only level that delivers surface taps — it draws on the host surface the same way
 * [CarHomeDashboardScreen] does (NavigationTemplate + [CarSurfaceCanvas], [MirrorSurfaceOwnership]
 * guarding the one process-wide surface callback). On an older host it cannot receive taps, so it
 * hands straight back to the template [CarNowPlayingScreen] fallback, which carries the same
 * transport without custom drawing.
 *
 * Playback is never started here: audio always flows through the one shared
 * [dev.autobridge.media.MediaPlaybackClient] / session, so rotary and steering-wheel media buttons
 * drive the very same state with no extra code. Prev/play/next only issue session commands, and a
 * preset recall replays the preset's own stream URL on the session.
 */
class CarRadioNowPlayingScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private val appManager = carContext.getCarService(AppManager::class.java)
    private val media = MediaPlaybackClient(carContext)
    private val renderer by lazy { CarRadioNowPlayingRenderer(carContext) }
    private val handler = Handler(Looper.getMainLooper())

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var layout: CarRadioLayout? = null
    private var active = false
    private var pressed: CarRadioRegion? = null

    /** The six presets, re-read on every repaint and whenever this screen comes back to the front. */
    private var presets: List<RadioStation?> = List(RadioPresetsStore.SLOT_COUNT) { null }

    /** onClick reaches a SurfaceCallback from Car API 5; older hosts fall back to the template. */
    private val drawsSurface: Boolean by lazy { carContext.carAppApiLevel >= 5 }

    private var sessionPlayer: androidx.media3.common.Player? = null
    private val sessionListener = object : androidx.media3.common.Player.Listener {
        override fun onEvents(
            player: androidx.media3.common.Player,
            events: androidx.media3.common.Player.Events,
        ) {
            if (active) render()
        }
    }

    /** The session moves without firing events (buffering, position); a light tick keeps it live. */
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** A station logo landed: repaint once it is decoded. */
    private val thumbnails = CarRadioNowPlayingRenderer.Thumbnails { url ->
        if (url.isNullOrBlank()) null else ImageLoader.bitmap(carContext, url) { if (active) render() }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!drawsSurface) return
                active = true
                pressed = null
                refreshContent()
                MirrorSurfaceOwnership.claim(this@CarRadioNowPlayingScreen)
                appManager.setSurfaceCallback(this@CarRadioNowPlayingScreen)
                watchSession()
                handler.postDelayed(tick, TICK_MS)
                render()
            }

            override fun onStop(owner: LifecycleOwner) {
                if (!drawsSurface) return
                active = false
                handler.removeCallbacksAndMessages(null)
                unwatchSession()
                releaseSurface()
                if (MirrorSurfaceOwnership.release(this@CarRadioNowPlayingScreen)) {
                    appManager.setSurfaceCallback(null)
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                media.disconnect()
            }
        })
    }

    override fun onGetTemplate(): Template {
        // A host that cannot deliver surface taps gets the template transport instead of a surface
        // it could never drive — the required fallback for hosts below Car API 5.
        if (!drawsSurface) {
            handler.post {
                if (!isFinishing()) {
                    screenManager.pop()
                    CarNavigation.open(screenManager, "CarNowPlayingScreen") { CarNowPlayingScreen(carContext) }
                }
            }
            return NavigationTemplate.Builder().setActionStrip(backOnlyStrip()).build()
        }
        // Re-read presets on every template request, guarded by the same repaint() rule the other
        // radio screens use, so a preset saved on the phone shows here when the host asks to repaint.
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            presets = RadioPresetsStore.presets(carContext)
        }
        return NavigationTemplate.Builder().setActionStrip(backOnlyStrip()).build()
    }

    private fun backOnlyStrip(): ActionStrip =
        // NavigationTemplate requires a non-empty action strip; Back is the one host control here.
        ActionStrip.Builder().addAction(Action.BACK).build()

    private fun isFinishing(): Boolean =
        !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)

    // --- Session ------------------------------------------------------------------------------

    private fun watchSession() {
        if (media.isConnected) {
            attachSessionListener()
            render()
        } else {
            media.connect(onConnected = {
                if (active) {
                    attachSessionListener()
                    render()
                }
            }, onError = { if (active) render() })
        }
    }

    private fun attachSessionListener() {
        val player = media.player ?: return
        if (sessionPlayer === player) return
        sessionPlayer?.removeListener(sessionListener)
        player.addListener(sessionListener)
        sessionPlayer = player
    }

    private fun unwatchSession() {
        sessionPlayer?.removeListener(sessionListener)
        sessionPlayer = null
    }

    /** Re-reads the presets the row draws from; the layout is not sized by them, so it stays. */
    private fun refreshContent() {
        presets = RadioPresetsStore.presets(carContext)
    }

    // --- Surface callbacks --------------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        val next = surfaceContainer.surface ?: return
        if (next !== surface) releaseSurface()
        surface = next
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        layout = null
        StructuredLog.i(TAG, "surface ${surfaceWidth}x$surfaceHeight dpi=$surfaceDpi")
        CarDisplayInfo.record(surfaceWidth, surfaceHeight, surfaceDpi, source = "radio")
        render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        if (!MirrorSurfaceOwnership.isOwner(this)) return
        releaseSurface()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        this.stableArea = Rect(stableArea)
        layout = null
        render()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        this.visibleArea = Rect(visibleArea)
        layout = null
        render()
    }

    override fun onClick(x: Float, y: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || pressed != null) return
        val hit = layout?.hit(x, y) ?: return
        pressed = hit
        render()
        handler.postDelayed({
            pressed = null
            if (!active) return@postDelayed
            actOn(hit)
            render()
        }, HomeDashboardTheme.PRESS_FEEDBACK_MS)
    }

    private fun actOn(region: CarRadioRegion) {
        when (region) {
            CarRadioRegion.Play -> if (media.isPlaying) media.pause() else media.resume()
            CarRadioRegion.Previous -> if (media.hasPrevious) media.previous()
            CarRadioRegion.Next -> if (media.hasNext) media.next()
            is CarRadioRegion.Preset -> recallPreset(region.slot)
        }
    }

    /** A preset tap replays its own stream on the shared session; an empty slot says how to fill it. */
    private fun recallPreset(slot: Int) {
        val station = presets.getOrNull(slot - 1)
        if (station == null || station.streamUrl.isBlank()) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_radio_preset_empty_hint),
                CarToast.LENGTH_LONG
            ).show()
            return
        }
        if (!media.isConnected) {
            CarToast.makeText(
                carContext,
                carContext.getString(R.string.car_iptv_connecting),
                CarToast.LENGTH_SHORT
            ).show()
            return
        }
        media.play(station.streamUrl, station.title)
        CarToast.makeText(
            carContext,
            carContext.getString(R.string.car_radio_preset_recalled, slot),
            CarToast.LENGTH_SHORT
        ).show()
        render()
    }

    // --- Drawing ------------------------------------------------------------------------------

    private fun render() {
        val target = surface ?: return
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || !target.isValid) return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val density = (if (surfaceDpi > 0) surfaceDpi else BASELINE_DPI) / BASELINE_DPI.toFloat()
        val computed = layout ?: CarRadioLayout.compute(safeArea(), density).also { layout = it }
        CarSurfaceCanvas.draw(carContext, target) { canvas ->
            renderer.draw(
                canvas,
                computed,
                CarRadioNowPlayingRenderer.Content(
                    title = media.currentTitle,
                    subtitle = media.currentArtist,
                    artwork = media.currentArtworkData,
                    connected = media.isConnected,
                    playing = media.isPlaying,
                    hasPrevious = media.hasPrevious,
                    hasNext = media.hasNext,
                    presets = presets,
                ),
                pressed,
                thumbnails,
            )
        }
    }

    private fun safeArea(): CarRadioBox {
        val full = Rect(0, 0, surfaceWidth, surfaceHeight)
        val inset = listOfNotNull(stableArea, visibleArea)
            .firstOrNull {
                !it.isEmpty && full.contains(it) &&
                    it.width() > surfaceWidth / 2 && it.height() > surfaceHeight / 2
            } ?: full
        return CarRadioBox(inset.left.toFloat(), inset.top.toFloat(), inset.right.toFloat(), inset.bottom.toFloat())
    }

    private fun releaseSurface() {
        surface?.let { runCatching { it.release() } }
        surface = null
    }

    private companion object {
        const val TAG = "CarRadioNow"
        const val TICK_MS = 1_000L
        const val BASELINE_DPI = 160
    }
}

/** A rectangle on the car surface. */
internal data class CarRadioBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

/** What a tap on the radio surface stands for. */
internal sealed interface CarRadioRegion {
    object Play : CarRadioRegion
    object Previous : CarRadioRegion
    object Next : CarRadioRegion
    data class Preset(val slot: Int) : CarRadioRegion
}

/**
 * Where everything sits on the radio surface, computed once per surface/area change. Landscape:
 * artwork top-left, text beside it, transport under them, presets along the bottom.
 */
internal class CarRadioLayout private constructor(
    val density: Float,
    val content: CarRadioBox,
    val artwork: CarRadioBox,
    val textLeft: Float,
    val textRight: Float,
    val textTop: Float,
    val previous: CarRadioBox,
    val play: CarRadioBox,
    val next: CarRadioBox,
    val presetCells: List<CarRadioBox>,
) {
    fun dp(v: Float) = v * density

    fun hit(x: Float, y: Float): CarRadioRegion? {
        if (inside(play, x, y)) return CarRadioRegion.Play
        if (inside(previous, x, y)) return CarRadioRegion.Previous
        if (inside(next, x, y)) return CarRadioRegion.Next
        presetCells.forEachIndexed { index, box ->
            if (inside(box, x, y)) return CarRadioRegion.Preset(index + 1)
        }
        return null
    }

    private fun inside(box: CarRadioBox, x: Float, y: Float) =
        x >= box.left && x <= box.right && y >= box.top && y <= box.bottom

    companion object {
        fun compute(area: CarRadioBox, density: Float): CarRadioLayout {
            fun dp(v: Float) = v * density
            val margin = dp(24f)
            val content = CarRadioBox(area.left + margin, area.top + margin, area.right - margin, area.bottom - margin)

            // Presets along the bottom: six equal cells with gaps.
            val presetHeight = dp(64f).coerceAtMost(content.height * 0.22f)
            val presetTop = content.bottom - presetHeight
            val presetGap = dp(10f)
            val cellWidth = (content.width - presetGap * (RadioPresetsStore.SLOT_COUNT - 1)) / RadioPresetsStore.SLOT_COUNT
            val presetCells = (0 until RadioPresetsStore.SLOT_COUNT).map { i ->
                val left = content.left + i * (cellWidth + presetGap)
                CarRadioBox(left, presetTop, left + cellWidth, content.bottom)
            }

            // Transport row above the presets.
            val controlsBottom = presetTop - dp(18f)
            val primary = dp(84f).coerceAtMost(content.height * 0.30f)
            val secondary = primary * 0.78f
            val controlGap = dp(28f)
            val controlsTop = controlsBottom - primary
            val cx = content.centerX
            val play = CarRadioBox(cx - primary / 2f, controlsTop, cx + primary / 2f, controlsBottom)
            val prevRight = play.left - controlGap
            val previous = CarRadioBox(
                prevRight - secondary, play.centerY - secondary / 2f, prevRight, play.centerY + secondary / 2f
            )
            val nextLeft = play.right + controlGap
            val next = CarRadioBox(
                nextLeft, play.centerY - secondary / 2f, nextLeft + secondary, play.centerY + secondary / 2f
            )

            // Artwork + text occupy the band above the controls.
            val upperBottom = controlsTop - dp(18f)
            val upperHeight = (upperBottom - content.top).coerceAtLeast(dp(80f))
            val artSize = upperHeight.coerceAtMost(content.width * 0.42f)
            val artwork = CarRadioBox(content.left, content.top, content.left + artSize, content.top + artSize)
            val textLeft = artwork.right + dp(24f)
            val textRight = content.right

            return CarRadioLayout(
                density, content, artwork, textLeft, textRight, content.top, previous, play, next, presetCells
            )
        }
    }
}

/**
 * Paints the radio Now Playing surface. Every Paint and Path is built once; the per-frame work is
 * ellipsizing two text lines and drawing the preset chips, so this stays cheap on the 1s tick.
 */
internal class CarRadioNowPlayingRenderer(private val context: CarContext) {

    fun interface Thumbnails {
        fun bitmap(url: String?): Bitmap?
    }

    data class Content(
        val title: String?,
        val subtitle: String?,
        val artwork: ByteArray?,
        val connected: Boolean,
        val playing: Boolean,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
        val presets: List<RadioStation?>,
    )

    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bold
        color = HomeDashboardTheme.TEXT_PRIMARY
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = regular
        color = HomeDashboardTheme.TEXT_SECONDARY
    }
    private val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bold
        color = HomeDashboardTheme.ACCENT_RADIO
    }
    private val presetNumber = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
    private val presetLabel = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium }

    private val rect = RectF()
    private val source = Rect()
    private val path = Path()

    fun draw(
        canvas: Canvas,
        layout: CarRadioLayout,
        content: Content,
        pressed: CarRadioRegion?,
        thumbnails: Thumbnails,
    ) {
        canvas.drawColor(HomeDashboardTheme.BACKGROUND)

        drawArtwork(canvas, layout, content, thumbnails)
        drawText(canvas, layout, content)
        drawTransport(canvas, layout, content, pressed)
        drawPresets(canvas, layout, content, pressed)
    }

    private fun drawArtwork(canvas: Canvas, layout: CarRadioLayout, content: Content, thumbnails: Thumbnails) {
        val box = layout.artwork
        rect.set(box.left, box.top, box.right, box.bottom)
        val radius = layout.dp(18f)
        fill.color = HomeDashboardTheme.CARD
        canvas.drawRoundRect(rect, radius, radius, fill)

        // The session carries embedded artwork bytes; a URL still (handled by [thumbnails]) is
        // reserved for a later metadata source. Either way the fallback glyph fills the gap.
        val bitmap = decodeEmbedded(content.artwork) ?: thumbnails.bitmap(null)
        if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
            val scale = maxOf(box.width / bitmap.width, box.height / bitmap.height)
            val cw = (box.width / scale).coerceAtMost(bitmap.width.toFloat())
            val ch = (box.height / scale).coerceAtMost(bitmap.height.toFloat())
            source.set(
                ((bitmap.width - cw) / 2f).toInt(),
                ((bitmap.height - ch) / 2f).toInt(),
                ((bitmap.width + cw) / 2f).toInt(),
                ((bitmap.height + ch) / 2f).toInt(),
            )
            canvas.save()
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(path)
            canvas.drawBitmap(bitmap, source, rect, fill)
            canvas.restore()
            return
        }
        // Fallback glyph: a radio mark in the section's amber identity.
        fill.color = HomeDashboardTheme.ACCENT_RADIO
        val g = box.width * 0.34f
        val cxg = box.centerX
        val cyg = box.centerY
        canvas.drawCircle(cxg, cyg + g * 0.35f, g * 0.28f, fill)
        fill.strokeWidth = g * 0.14f
        fill.style = Paint.Style.STROKE
        canvas.drawLine(cxg, cyg + g * 0.35f, cxg + g * 0.5f, cyg - g * 0.7f, fill)
        fill.style = Paint.Style.FILL
    }

    private fun decodeEmbedded(data: ByteArray?): Bitmap? {
        if (data == null || data.isEmpty()) return null
        return runCatching { android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
    }

    private fun drawText(canvas: Canvas, layout: CarRadioLayout, content: Content) {
        val left = layout.textLeft
        val right = layout.textRight
        if (right - left < layout.dp(8f)) return

        // Source badge above the title.
        badgePaint.textSize = layout.dp(15f)
        val badge = context.getString(R.string.radio_source_internet).uppercase()
        var y = layout.textTop + layout.dp(22f)
        canvas.drawText(badge, left, y, badgePaint)

        titlePaint.textSize = layout.dp(30f)
        y += layout.dp(44f)
        val title = content.title ?: context.getString(R.string.car_radio_nothing_playing)
        drawEllipsized(canvas, title, titlePaint, left, right, y)

        val subtitle = when {
            !content.connected -> context.getString(R.string.car_lib_connecting_session)
            content.title == null -> context.getString(R.string.car_lib_nothing_playing)
            else -> content.subtitle ?: context.getString(
                if (content.playing) R.string.car_lib_playing else R.string.car_lib_paused
            )
        }
        subtitlePaint.textSize = layout.dp(18f)
        y += layout.dp(30f)
        drawEllipsized(canvas, subtitle, subtitlePaint, left, right, y)
    }

    private fun drawTransport(canvas: Canvas, layout: CarRadioLayout, content: Content, pressed: CarRadioRegion?) {
        drawControl(canvas, layout, layout.previous, Glyph.PREVIOUS, primary = false,
            enabled = content.hasPrevious, pressed = pressed == CarRadioRegion.Previous)
        drawControl(canvas, layout, layout.play, if (content.playing) Glyph.PAUSE else Glyph.PLAY, primary = true,
            enabled = content.connected, pressed = pressed == CarRadioRegion.Play)
        drawControl(canvas, layout, layout.next, Glyph.NEXT, primary = false,
            enabled = content.hasNext, pressed = pressed == CarRadioRegion.Next)
    }

    private enum class Glyph { PREVIOUS, PLAY, PAUSE, NEXT }

    private fun drawControl(
        canvas: Canvas,
        layout: CarRadioLayout,
        box: CarRadioBox,
        glyph: Glyph,
        primary: Boolean,
        enabled: Boolean,
        pressed: Boolean,
    ) {
        val radius = box.width / 2f
        // The active control is the shared electric-blue ACCENT_RADIO_ACTIVE, never the periwinkle
        // home ACCENT; the amber ACCENT_RADIO stays the section identity (badge, fallback artwork).
        fill.color = when {
            primary && pressed -> HomeDashboardTheme.mix(
                HomeDashboardTheme.ACCENT_RADIO_ACTIVE, HomeDashboardTheme.TEXT_PRIMARY, 0.30f
            )
            primary -> HomeDashboardTheme.ACCENT_RADIO_ACTIVE
            pressed -> HomeDashboardTheme.CONTROL_PRESSED
            else -> HomeDashboardTheme.CONTROL
        }
        canvas.drawCircle(box.centerX, box.centerY, radius, fill)

        fill.color = when {
            primary -> HomeDashboardTheme.ON_ACCENT_RADIO_ACTIVE
            enabled -> HomeDashboardTheme.TEXT_CONTROL
            else -> HomeDashboardTheme.CONTROL_DISABLED_GLYPH
        }
        val size = box.width * 0.42f
        val unit = size / 24f
        val ox = box.centerX - size / 2f
        val oy = box.centerY - size / 2f
        fun x(v: Float) = ox + v * unit
        fun y(v: Float) = oy + v * unit
        when (glyph) {
            Glyph.PREVIOUS -> {
                rect.set(x(6f), y(5f), x(8f), y(19f)); canvas.drawRect(rect, fill)
                path.reset(); path.moveTo(x(20f), y(5f)); path.lineTo(x(20f), y(19f)); path.lineTo(x(9f), y(12f)); path.close()
                canvas.drawPath(path, fill)
            }
            Glyph.NEXT -> {
                rect.set(x(16f), y(5f), x(18f), y(19f)); canvas.drawRect(rect, fill)
                path.reset(); path.moveTo(x(4f), y(5f)); path.lineTo(x(4f), y(19f)); path.lineTo(x(15f), y(12f)); path.close()
                canvas.drawPath(path, fill)
            }
            Glyph.PAUSE -> {
                rect.set(x(6f), y(5f), x(10f), y(19f)); canvas.drawRect(rect, fill)
                rect.set(x(14f), y(5f), x(18f), y(19f)); canvas.drawRect(rect, fill)
            }
            Glyph.PLAY -> {
                path.reset(); path.moveTo(x(8f), y(5f)); path.lineTo(x(8f), y(19f)); path.lineTo(x(19f), y(12f)); path.close()
                canvas.drawPath(path, fill)
            }
        }
    }

    private fun drawPresets(canvas: Canvas, layout: CarRadioLayout, content: Content, pressed: CarRadioRegion?) {
        val radius = layout.dp(14f)
        layout.presetCells.forEachIndexed { index, box ->
            val slot = index + 1
            val station = content.presets.getOrNull(index)
            val isPressed = (pressed as? CarRadioRegion.Preset)?.slot == slot
            rect.set(box.left, box.top, box.right, box.bottom)
            fill.color = if (isPressed) HomeDashboardTheme.CARD_PRESSED else HomeDashboardTheme.CARD
            canvas.drawRoundRect(rect, radius, radius, fill)

            val pad = layout.dp(8f)
            presetNumber.textSize = layout.dp(15f)
            // A filled slot numbers in the active blue; an empty one is muted.
            presetNumber.color = if (station != null) HomeDashboardTheme.ACCENT_RADIO_ACTIVE
            else HomeDashboardTheme.TEXT_DISABLED
            canvas.drawText(slot.toString(), box.left + pad, box.top + pad + presetNumber.textSize, presetNumber)

            presetLabel.textSize = layout.dp(13f)
            presetLabel.color = if (station != null) HomeDashboardTheme.TEXT_PRIMARY else HomeDashboardTheme.TEXT_DISABLED
            val label = station?.title ?: context.getString(R.string.car_radio_preset_empty)
            drawEllipsized(
                canvas, label, presetLabel, box.left + pad, box.right - pad,
                box.bottom - pad - presetLabel.descent()
            )
        }
    }

    private fun drawEllipsized(canvas: Canvas, text: String, paint: TextPaint, left: Float, right: Float, baseline: Float) {
        if (text.isBlank() || right <= left) return
        val clipped = TextUtils.ellipsize(text, paint, right - left, TextUtils.TruncateAt.END)
        canvas.drawText(clipped, 0, clipped.length, left, baseline, paint)
    }
}
