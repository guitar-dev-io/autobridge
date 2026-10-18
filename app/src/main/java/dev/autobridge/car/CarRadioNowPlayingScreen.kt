package dev.autobridge.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import dev.autobridge.iptv.IptvHistoryStore
import dev.autobridge.media.AudioEq
import dev.autobridge.logging.StructuredLog
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.radio.RadioNowPlaying
import dev.autobridge.radio.RadioRepository
import dev.autobridge.radio.RadioSourceType
import dev.autobridge.radio.RadioStation
import dev.autobridge.ui.ImageLoader

/**
 * The car radio Now Playing surface: the shared MediaSession's selected station drawn large, with
 * its artwork, name, source badge and a prev / play-pause / next transport.
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
 * drive the very same state with no extra code. Prev/play/next only issue session commands.
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
            CarRadioRegion.Play -> if (media.isPlayRequested) media.pause() else media.resume()
            // The station list this was played from is the screen under this one.
            CarRadioRegion.AllStations -> screenManager.pop()
            CarRadioRegion.Previous -> if (media.hasPrevious) media.previous()
            CarRadioRegion.Next -> if (media.hasNext) media.next()
            // One button, the next sound preset: no sliders to fiddle with while driving.
            CarRadioRegion.Eq -> {
                val preset = AudioEq.cyclePreset(carContext)
                CarToast.makeText(
                    carContext,
                    carContext.getString(R.string.eq_car_changed, carContext.getString(preset.labelRes)),
                    CarToast.LENGTH_SHORT
                ).show()
            }
            CarRadioRegion.Favorite -> currentStation()?.let { station ->
                RadioRepository.RadioHistory.toggleFavorite(carContext, station)
            }
        }
    }

    /** The station playing now as the radio store knows stations, for the favourite button. */
    private fun currentStation(): RadioStation? {
        val url = media.currentUri?.takeIf { it.isNotBlank() } ?: return null
        val name = media.currentItemTitle ?: media.currentTitle ?: return null
        return RadioStation(
            RadioSourceType.INTERNET_RADIO, id = url, title = name, streamUrl = url,
            logo = media.currentArtworkUri.orEmpty()
        )
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
                    station = media.currentItemTitle,
                    artworkUrl = media.currentArtworkUri,
                    eqLabel = carContext.getString(
                        R.string.eq_car_button, carContext.getString(AudioEq.activePreset(carContext).labelRes)
                    ),
                    favorite = media.currentUri?.let { IptvHistoryStore.isFavorite(carContext, it) } == true,
                    connected = media.isConnected,
                    playing = media.isPlayRequested,
                    hasPrevious = media.hasPrevious,
                    hasNext = media.hasNext,
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
    object Eq : CarRadioRegion
    object Favorite : CarRadioRegion
    object AllStations : CarRadioRegion
}

/**
 * Where everything sits on the radio surface, computed once per surface/area change, after the
 * radio design's car player: a header row (the title, then the EQ, favourite and all-stations
 * pills on the right), and under it the cover on the left with the station's logo as a badge on
 * its corner, the text beside it, and the pause and next-station pills under the text. There is
 * no on-screen previous: the steering wheel steps both ways, and the hint under the text says so.
 *
 * Everything below the header scales down together on a short surface, so nothing overlaps.
 */
internal class CarRadioLayout private constructor(
    val density: Float,
    /** Below-the-header scale: 1 on a roomy surface, less on a short one. */
    val fit: Float,
    val content: CarRadioBox,
    val titleBaseline: Float,
    val artwork: CarRadioBox,
    val badge: CarRadioBox,
    val textLeft: Float,
    val textRight: Float,
    val textTop: Float,
    val play: CarRadioBox,
    val next: CarRadioBox,
    val eq: CarRadioBox,
    val favorite: CarRadioBox,
    val allStations: CarRadioBox,
) {
    fun dp(v: Float) = v * density

    /** A size in the body below the header, scaled with [fit]. */
    fun body(v: Float) = v * density * fit

    fun hit(x: Float, y: Float): CarRadioRegion? {
        if (inside(play, x, y)) return CarRadioRegion.Play
        if (inside(next, x, y)) return CarRadioRegion.Next
        if (inside(eq, x, y)) return CarRadioRegion.Eq
        if (inside(favorite, x, y)) return CarRadioRegion.Favorite
        if (inside(allStations, x, y)) return CarRadioRegion.AllStations
        return null
    }

    private fun inside(box: CarRadioBox, x: Float, y: Float) =
        box.width > 0f && x >= box.left && x <= box.right && y >= box.top && y <= box.bottom

    companion object {
        /** Offsets in the text column, in body dp from [textTop]; the block is [TEXT_BLOCK] tall. */
        const val BADGE_LINE = 18f
        const val TITLE_LINE = 72f
        const val SUBTITLE_LINE = 108f
        const val HINT_LINE = 138f
        const val BUTTONS_TOP = 162f
        const val BUTTON_HEIGHT = 76f
        const val TEXT_BLOCK = BUTTONS_TOP + BUTTON_HEIGHT

        fun compute(area: CarRadioBox, density: Float): CarRadioLayout {
            fun dp(v: Float) = v * density
            val content = CarRadioBox(area.left + dp(28f), area.top + dp(22f), area.right - dp(28f), area.bottom - dp(22f))

            // Header: 56dp pills, right-aligned, all stations outermost.
            val pill = dp(56f)
            val headerBottom = content.top + pill
            val gap = dp(14f)
            val allStations = CarRadioBox(content.right - dp(170f), content.top, content.right, headerBottom)
            val favorite = CarRadioBox(allStations.left - gap - dp(140f), content.top, allStations.left - gap, headerBottom)
            val eq = CarRadioBox(favorite.left - gap - dp(220f), content.top, favorite.left - gap, headerBottom)
            val titleBaseline = content.top + pill / 2f + dp(10f)

            // Body: cover left, text right, the block centred in what is left under the header.
            val bodyTop = headerBottom + dp(18f)
            val bodyHeight = (content.bottom - bodyTop).coerceAtLeast(dp(120f))
            val fit = (bodyHeight / dp(TEXT_BLOCK + 20f)).coerceIn(0.55f, 1f)
            fun body(v: Float) = v * density * fit

            val art = minOf(body(300f), bodyHeight, content.width * 0.34f)
            val artTop = bodyTop + (bodyHeight - art) / 2f
            val artwork = CarRadioBox(content.left, artTop, content.left + art, artTop + art)
            val badgeSize = art * 92f / 300f
            val badgeOut = art * 12f / 300f
            val badge = CarRadioBox(
                artwork.right + badgeOut - badgeSize, artwork.bottom + badgeOut - badgeSize,
                artwork.right + badgeOut, artwork.bottom + badgeOut
            )

            val textLeft = artwork.right + body(44f)
            val textRight = content.right
            val block = body(TEXT_BLOCK)
            val textTop = (artwork.centerY - block / 2f).coerceIn(bodyTop, (content.bottom - block).coerceAtLeast(bodyTop))
            val buttonsTop = textTop + body(BUTTONS_TOP)
            val buttonsBottom = buttonsTop + body(BUTTON_HEIGHT)
            val play = CarRadioBox(textLeft, buttonsTop, (textLeft + body(200f)).coerceAtMost(textRight), buttonsBottom)
            val nextLeft = play.right + body(16f)
            val next = CarRadioBox(nextLeft, buttonsTop, (nextLeft + body(250f)).coerceAtMost(textRight), buttonsBottom)

            return CarRadioLayout(
                density, fit, content, titleBaseline, artwork, badge, textLeft, textRight, textTop,
                play, next, eq, favorite, allStations
            )
        }
    }
}

/**
 * Paints the radio Now Playing surface. Every Paint and Path is built once; the per-frame work is
 * ellipsizing a few text lines, so this stays cheap on the 1s tick.
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
        /** The queue item's own name (the station), which [title] replaces with the song once the stream names it. */
        val station: String? = null,
        /** The station logo the item was queued with: the badge, or the cover when the stream has none. */
        val artworkUrl: String? = null,
        val eqLabel: String = "",
        val favorite: Boolean = false,
    )

    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
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
        canvas.drawColor(BACKGROUND)
        drawHeader(canvas, layout, content, pressed)
        drawArtwork(canvas, layout, content, thumbnails)
        drawText(canvas, layout, content)
        drawButtons(canvas, layout, content, pressed)
    }

    // --- Header -------------------------------------------------------------------------------

    private fun drawHeader(canvas: Canvas, layout: CarRadioLayout, content: Content, pressed: CarRadioRegion?) {
        text.typeface = medium
        text.textSize = layout.dp(28f)
        text.color = TEXT_SOFT
        val title = context.getString(R.string.car_lib_now_playing)
        drawEllipsized(canvas, title, text, layout.content.left, layout.eq.left - layout.dp(14f), layout.titleBaseline)

        drawPill(canvas, layout, layout.eq, Icon.EQ, content.eqLabel, warm = false, pressed = pressed == CarRadioRegion.Eq)
        drawPill(
            canvas, layout, layout.favorite, if (content.favorite) Icon.STAR_ON else Icon.STAR,
            context.getString(R.string.radio_favorite_add), warm = content.favorite,
            pressed = pressed == CarRadioRegion.Favorite
        )
        drawPill(
            canvas, layout, layout.allStations, Icon.LIST, context.getString(R.string.radio_all_stations),
            warm = false, pressed = pressed == CarRadioRegion.AllStations
        )
    }

    private enum class Icon { EQ, STAR, STAR_ON, LIST, PAUSE, PLAY, NEXT }

    /** A rounded pill: an icon then a label, centred; warm when it is on (the favourite). */
    private fun drawPill(
        canvas: Canvas, layout: CarRadioLayout, box: CarRadioBox, icon: Icon, label: String,
        warm: Boolean, pressed: Boolean, primary: Boolean = false, enabled: Boolean = true,
        textSize: Float = layout.dp(18f),
    ) {
        if (box.width <= 0f) return
        rect.set(box.left, box.top, box.right, box.bottom)
        val radius = box.height / 2f
        fill.style = Paint.Style.FILL
        fill.color = when {
            primary && pressed -> HomeDashboardTheme.mix(ACCENT, Color.WHITE, 0.25f)
            primary -> ACCENT
            pressed -> CONTROL_PRESSED
            warm -> WARM
            else -> CONTROL
        }
        canvas.drawRoundRect(rect, radius, radius, fill)

        val ink = when {
            primary -> ON_ACCENT
            !enabled -> TEXT_DISABLED
            warm -> ACCENT
            else -> TEXT_PRIMARY
        }
        text.typeface = if (primary) bold else medium
        text.textSize = textSize
        text.color = ink
        val iconSize = textSize * 1.3f
        val spacing = textSize * 0.45f
        val room = box.width - box.height * 0.7f - iconSize - spacing
        val clipped = TextUtils.ellipsize(label, text, room.coerceAtLeast(0f), TextUtils.TruncateAt.END)
        val labelWidth = text.measureText(clipped, 0, clipped.length)
        val total = iconSize + (if (clipped.isEmpty()) 0f else spacing + labelWidth)
        val startX = box.centerX - total / 2f
        drawIcon(canvas, icon, startX, box.centerY - iconSize / 2f, iconSize, ink)
        if (clipped.isNotEmpty()) {
            val metrics = text.fontMetrics
            val baseline = box.centerY - (metrics.ascent + metrics.descent) / 2f
            text.color = ink
            canvas.drawText(clipped, 0, clipped.length, startX + iconSize + spacing, baseline, text)
        }
    }

    /** The design's icons on a 24-unit grid, drawn at [size] with its top-left at ([left], [top]). */
    private fun drawIcon(canvas: Canvas, icon: Icon, left: Float, top: Float, size: Float, color: Int) {
        val unit = size / 24f
        fun x(v: Float) = left + v * unit
        fun y(v: Float) = top + v * unit
        fill.color = color
        fill.strokeCap = Paint.Cap.ROUND
        fill.strokeJoin = Paint.Join.ROUND
        fill.strokeWidth = 2f * unit
        when (icon) {
            Icon.EQ -> {
                fill.style = Paint.Style.STROKE
                for (cx in floatArrayOf(6f, 12f, 18f)) canvas.drawLine(x(cx), y(4f), x(cx), y(20f), fill)
                for ((cx, cy) in listOf(6f to 14f, 12f to 8f, 18f to 16f)) {
                    fill.style = Paint.Style.FILL
                    fill.color = CONTROL
                    canvas.drawCircle(x(cx), y(cy), 2f * unit, fill)
                    fill.style = Paint.Style.STROKE
                    fill.color = color
                    canvas.drawCircle(x(cx), y(cy), 2f * unit, fill)
                }
            }
            Icon.STAR, Icon.STAR_ON -> {
                path.reset()
                val points = floatArrayOf(12f, 3f, 14.7f, 8.6f, 20.8f, 9.5f, 16.4f, 13.8f, 17.4f, 19.9f, 12f, 17f, 6.6f, 19.9f, 7.6f, 13.8f, 3.2f, 9.5f, 9.3f, 8.6f)
                path.moveTo(x(points[0]), y(points[1]))
                var i = 2
                while (i < points.size) { path.lineTo(x(points[i]), y(points[i + 1])); i += 2 }
                path.close()
                fill.style = if (icon == Icon.STAR_ON) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                canvas.drawPath(path, fill)
            }
            Icon.LIST -> {
                fill.style = Paint.Style.STROKE
                canvas.drawLine(x(4f), y(6f), x(20f), y(6f), fill)
                canvas.drawLine(x(4f), y(12f), x(20f), y(12f), fill)
                canvas.drawLine(x(4f), y(18f), x(14f), y(18f), fill)
            }
            Icon.PAUSE -> {
                fill.style = Paint.Style.FILL
                rect.set(x(6f), y(5f), x(10f), y(19f)); canvas.drawRoundRect(rect, unit, unit, fill)
                rect.set(x(14f), y(5f), x(18f), y(19f)); canvas.drawRoundRect(rect, unit, unit, fill)
            }
            Icon.PLAY -> {
                fill.style = Paint.Style.FILL
                path.reset(); path.moveTo(x(7f), y(5f)); path.lineTo(x(7f), y(19f)); path.lineTo(x(19f), y(12f)); path.close()
                canvas.drawPath(path, fill)
            }
            Icon.NEXT -> {
                fill.style = Paint.Style.FILL
                path.reset(); path.moveTo(x(6f), y(6f)); path.lineTo(x(6f), y(18f)); path.lineTo(x(15f), y(12f)); path.close()
                canvas.drawPath(path, fill)
                rect.set(x(16f), y(6f), x(18f), y(18f)); canvas.drawRect(rect, fill)
            }
        }
        fill.style = Paint.Style.FILL
    }

    // --- Cover and badge ----------------------------------------------------------------------

    private fun drawArtwork(canvas: Canvas, layout: CarRadioLayout, content: Content, thumbnails: Thumbnails) {
        val box = layout.artwork
        val radius = box.width * 48f / 300f
        val cover = decodeEmbedded(content.artwork)
        val logo = thumbnails.bitmap(content.artworkUrl)
        val station = content.station ?: content.title.orEmpty()

        // The stream's own cover in the square with the logo as the badge; without a cover the
        // logo takes the square; without either, the station's colour card with its frequency.
        val big = cover ?: logo
        if (big != null) {
            drawCropped(canvas, big, box, radius)
        } else {
            rect.set(box.left, box.top, box.right, box.bottom)
            fill.color = if (station.isBlank()) CONTROL else CarStationTile.colorFor(station)
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (station.isNotBlank()) {
                text.typeface = bold
                text.color = Color.WHITE
                val label = CarStationTile.label(station)
                text.textSize = box.width * (if (label.length <= 2) 0.36f else 0.24f)
                val width = text.measureText(label)
                val metrics = text.fontMetrics
                canvas.drawText(label, box.centerX - width / 2f, box.centerY - (metrics.ascent + metrics.descent) / 2f, text)
            }
        }
        if (cover != null && logo != null) {
            val badge = layout.badge
            val border = badge.width * 5f / 92f
            val badgeRadius = badge.width * 22f / 92f
            rect.set(badge.left - border, badge.top - border, badge.right + border, badge.bottom + border)
            fill.color = BACKGROUND
            canvas.drawRoundRect(rect, badgeRadius + border, badgeRadius + border, fill)
            drawCropped(canvas, logo, badge, badgeRadius)
        }
    }

    private fun drawCropped(canvas: Canvas, bitmap: Bitmap, box: CarRadioBox, radius: Float) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        rect.set(box.left, box.top, box.right, box.bottom)
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
        fill.color = Color.WHITE
        canvas.drawRect(rect, fill)
        canvas.drawBitmap(bitmap, source, rect, bitmapPaint)
        canvas.restore()
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun decodeEmbedded(data: ByteArray?): Bitmap? {
        if (data == null || data.isEmpty()) return null
        return runCatching { android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
    }

    // --- Text ---------------------------------------------------------------------------------

    private fun drawText(canvas: Canvas, layout: CarRadioLayout, content: Content) {
        val left = layout.textLeft
        val right = layout.textRight
        if (right - left < layout.dp(8f)) return
        val top = layout.textTop

        // "● Live": the red dot says on air.
        text.typeface = medium
        text.textSize = layout.body(17f)
        val badgeY = top + layout.body(CarRadioLayout.BADGE_LINE)
        val dot = layout.body(5f)
        fill.color = LIVE_RED
        canvas.drawCircle(left + dot, badgeY - text.textSize * 0.35f, dot, fill)
        text.color = WARM_TEXT
        canvas.drawText(context.getString(R.string.radio_live), left + dot * 2f + layout.body(8f), badgeY, text)

        // The song when the stream names it, the station otherwise; see RadioNowPlaying.
        val now = RadioNowPlaying.of(content.station, content.title, content.subtitle)
        val title = when {
            content.title == null -> context.getString(R.string.car_radio_nothing_playing)
            else -> now.song ?: now.station
        }
        text.typeface = bold
        text.color = TEXT_PRIMARY
        text.textSize = layout.body(44f)
        drawEllipsized(canvas, title, text, left, right, top + layout.body(CarRadioLayout.TITLE_LINE))

        val subtitle = when {
            !content.connected -> context.getString(R.string.car_lib_connecting_session)
            content.title == null -> context.getString(R.string.car_lib_nothing_playing)
            now.song != null -> listOfNotNull(now.artist, now.station).joinToString(" · ")
            else -> now.artist ?: context.getString(
                if (content.playing) R.string.car_lib_playing else R.string.car_lib_paused
            )
        }
        text.typeface = regular
        text.color = TEXT_CONTROL
        text.textSize = layout.body(22f)
        drawEllipsized(canvas, subtitle, text, left, right, top + layout.body(CarRadioLayout.SUBTITLE_LINE))

        text.color = TEXT_HINT
        text.textSize = layout.body(17f)
        drawEllipsized(canvas, context.getString(R.string.car_radio_wheel_hint), text, left, right, top + layout.body(CarRadioLayout.HINT_LINE))
    }

    // --- Transport ----------------------------------------------------------------------------

    private fun drawButtons(canvas: Canvas, layout: CarRadioLayout, content: Content, pressed: CarRadioRegion?) {
        drawPill(
            canvas, layout, layout.play, if (content.playing) Icon.PAUSE else Icon.PLAY,
            context.getString(if (content.playing) R.string.car_radio_stop else R.string.radio_play),
            warm = false, pressed = pressed == CarRadioRegion.Play, primary = true,
            enabled = content.connected, textSize = layout.body(24f)
        )
        drawPill(
            canvas, layout, layout.next, Icon.NEXT, context.getString(R.string.radio_next_station),
            warm = false, pressed = pressed == CarRadioRegion.Next, enabled = content.hasNext,
            textSize = layout.body(24f)
        )
    }

    private fun drawEllipsized(canvas: Canvas, value: String, paint: TextPaint, left: Float, right: Float, baseline: Float) {
        if (value.isBlank() || right <= left) return
        val clipped = TextUtils.ellipsize(value, paint, right - left, TextUtils.TruncateAt.END)
        canvas.drawText(clipped, 0, clipped.length, left, baseline, paint)
    }

    private companion object {
        // The radio design's car palette.
        val BACKGROUND = 0xFF0B0D10.toInt()
        val CONTROL = 0xFF1C2027.toInt()
        val CONTROL_PRESSED = 0xFF343A44.toInt()
        val WARM = 0xFF2A2116.toInt()
        val ACCENT = 0xFFFFB35C.toInt()
        val ON_ACCENT = 0xFF2A1A05.toInt()
        val WARM_TEXT = 0xFFFFC985.toInt()
        val LIVE_RED = 0xFFFF5C5C.toInt()
        val TEXT_PRIMARY = 0xFFF3F5F7.toInt()
        val TEXT_SOFT = 0xFFC9CDD3.toInt()
        val TEXT_CONTROL = 0xFFE3E6EA.toInt()
        val TEXT_HINT = 0xFFA9AFB8.toInt()
        val TEXT_DISABLED = 0xFF5A616B.toInt()
    }
}
