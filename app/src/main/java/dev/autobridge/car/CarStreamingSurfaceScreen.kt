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
import dev.autobridge.library.DesktopLinks
import dev.autobridge.library.StreamingFavoritesStore
import dev.autobridge.library.StreamingIcons
import dev.autobridge.library.StreamingLinks
import dev.autobridge.library.labelRes
import dev.autobridge.mirror.MirrorSurfaceOwnership
import dev.autobridge.ui.AppFonts

/**
 * The streaming launcher on the car: big brand cards, three to a row, drawn by the app on the car
 * surface (a NavigationTemplate, like the car home) instead of the host's grid template, whose
 * column count the head unit picks (four on a wide screen) and the app cannot set.
 *
 * The same tiles as [CarStreamingScreen]: the driver's favourites, the Video grid in its own
 * order, the rest of the catalog, and a tile to manage the favourites. A tap opens the site the
 * way that screen does (the desktop browser for the sites that may not play in the WebView, the
 * built-in car browser for the rest); the grid scrolls with the two buttons or a drag.
 *
 * Touch only for now: the cards are not rotary-focusable. The host-grid [CarStreamingScreen] stays
 * for a host below Car API 5, which has no surface to draw on.
 */
internal class CarStreamingSurfaceScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private sealed class Tile {
        class Site(val title: String, val url: String, val starred: Boolean, val note: String?) : Tile()
        object Manage : Tile()
    }

    private val appManager = carContext.getCarService(AppManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceDpi = 0
    private var stableArea: Rect? = null
    private var visibleArea: Rect? = null
    private var active = false

    private var tiles: List<Tile> = emptyList()

    /** The headings and how many tiles each covers, in tile order; the status strip's state. */
    private var sectionTitles: List<String> = emptyList()
    private var sectionSizes: List<Int> = emptyList()
    private var chromeStatus = DesktopLinks.ChromeStatus.UNAVAILABLE
    private var layout: StreamingGridLayout? = null
    private var scroll = 0f
    private var pressed = -1

    /** Card images by tile and size; rebuilt when the layout changes, dropped with the surface. */
    private val cardImages = HashMap<String, Bitmap>()

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val chevron = Path()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                active = true
                pressed = -1
                refreshTiles()
                MirrorSurfaceOwnership.claim(this@CarStreamingSurfaceScreen)
                appManager.setSurfaceCallback(this@CarStreamingSurfaceScreen)
                render()
            }

            override fun onStop(owner: LifecycleOwner) {
                active = false
                handler.removeCallbacksAndMessages(null)
                releaseSurface()
                if (MirrorSurfaceOwnership.release(this@CarStreamingSurfaceScreen)) appManager.setSurfaceCallback(null)
            }
        })
    }

    override fun onGetTemplate(): Template =
        // NavigationTemplate requires a non-empty action strip; Back is the one action here.
        NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(Action.BACK).build())
            .build()

    // --- Tiles ---------------------------------------------------------------------------------

    /** Re-read whenever the screen comes back to the front: favourites change on the manage screen. */
    private fun refreshTiles() {
        val favorites = StreamingFavoritesStore.list(carContext)
        val favoriteUrls = favorites.map { it.url }.toSet()
        val chrome = CarExtensions.canOpenChrome(carContext)
        val canInstall = CarExtensions.canInstallChrome(carContext)
        val chromeBadge = carContext.getString(R.string.streaming_chrome_badge)
        val needsChrome = carContext.getString(R.string.car_streaming_needs_chrome)
        val maybe = carContext.getString(R.string.car_streaming_maybe)
        val list = ArrayList<Tile>()
        val titles = ArrayList<String>()
        val sizes = ArrayList<Int>()
        // The phone's order and headings: favourites (with the tile that manages them), then each
        // group of the catalog, the streaming services first.
        favorites.forEach { list += Tile.Site(it.title, it.url, starred = true, note = null) }
        list += Tile.Manage
        titles += carContext.getString(R.string.streaming_favorites)
        sizes += favorites.size + 1
        StreamingLinks.grouped().forEach { (group, links) ->
            val rest = links.filterNot { it.url in favoriteUrls }
            if (rest.isEmpty()) return@forEach
            rest.forEach { link ->
                val note = if (!StreamingLinks.mayNotPlay(link)) null else when {
                    chrome -> chromeBadge
                    canInstall -> needsChrome
                    else -> maybe
                }
                list += Tile.Site(link.title, link.url, starred = false, note = note)
            }
            titles += carContext.getString(group.labelRes())
            sizes += rest.size
        }
        tiles = list
        sectionTitles = titles
        sectionSizes = sizes
        chromeStatus = DesktopLinks.chromeStatus(carContext)
        layout = null
        cardImages.clear()
    }

    // --- Surface -------------------------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        val next = surfaceContainer.surface ?: return
        if (next !== surface) releaseSurface()
        surface = next
        surfaceWidth = surfaceContainer.width
        surfaceHeight = surfaceContainer.height
        surfaceDpi = surfaceContainer.dpi
        layout = null
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

    /** Drops our producer connection to the surface; the host still owns the surface itself. */
    private fun releaseSurface() {
        surface?.let { runCatching { it.release() } }
        surface = null
        cardImages.clear()
    }

    /** The area the grid may use: the host's stable area, else its visible area, else everything. */
    private fun safeArea(): MenuBox {
        val full = Rect(0, 0, surfaceWidth, surfaceHeight)
        val inset = listOfNotNull(stableArea, visibleArea)
            .firstOrNull { !it.isEmpty && full.contains(it) && it.width() > surfaceWidth / 2 && it.height() > surfaceHeight / 2 }
            ?: full
        return MenuBox(inset.left.toFloat(), inset.top.toFloat(), inset.right.toFloat(), inset.bottom.toFloat())
    }

    // --- Touch ---------------------------------------------------------------------------------

    override fun onClick(x: Float, y: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || pressed >= 0) return
        val current = layout ?: return
        val hit = current.hit(x, y, scroll) ?: return
        when (hit.region) {
            StreamingRegion.SCROLL_UP -> return scrollBy(-current.scrollStep)
            StreamingRegion.SCROLL_DOWN -> return scrollBy(current.scrollStep)
            StreamingRegion.BANNER -> return openChromeSetup()
            StreamingRegion.CARD -> Unit
        }
        pressed = hit.index
        render()
        handler.postDelayed({
            val index = pressed
            pressed = -1
            if (!active || index !in tiles.indices) return@postDelayed
            activate(tiles[index])
            // A tile that opened something handed the surface over and this draws nothing.
            render()
        }, HomeDashboardTheme.PRESS_FEEDBACK_MS)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!active || !MirrorSurfaceOwnership.isOwner(this)) return
        scrollBy(distanceY)
    }

    private fun scrollBy(delta: Float) {
        val current = layout ?: return
        if (!current.scrollable) return
        scroll = (scroll + delta).coerceIn(0f, current.maxScroll)
        render()
    }

    /** The status strip: when Chrome or Brave is missing it opens the screen that says how to get one. */
    private fun openChromeSetup() {
        if (chromeStatus != DesktopLinks.ChromeStatus.MISSING) return
        releaseSurface()
        val url = StreamingLinks.all.first { StreamingLinks.mayNotPlay(it) }.let { it.url }
        val title = StreamingLinks.all.first { it.url == url }.title
        screenManager.push(CarChromeNeededScreen(carContext, title, url) { openBuiltIn(url) })
    }

    private fun activate(tile: Tile) {
        // The browsers attach their own surface producer, which they cannot while this screen is
        // still connected, so it lets go first (as the car home does).
        releaseSurface()
        when (tile) {
            is Tile.Manage -> CarNavigation.open(screenManager, "CarStreamingFavoritesScreen") { CarStreamingFavoritesScreen(carContext) }
            is Tile.Site -> open(tile.url)
        }
    }

    /**
     * The sites that may not play in the WebView open in Google Chrome for Linux (the desktop
     * engine), where it is installed; everything else, and the case of none, is the built-in
     * browser. A site already open is returned to, not reloaded.
     */
    private fun open(url: String) {
        if (DesktopLinks.wantsDesktop(url)) {
            if (CarExtensions.openUrl(carContext, screenManager, url)) return
            // Chrome is what these sites need and it is not installed yet: say so, with the way on.
            if (CarExtensions.canInstallChrome(carContext)) {
                val title = StreamingLinks.all.firstOrNull { it.url == url }?.title ?: url
                screenManager.push(CarChromeNeededScreen(carContext, title, url) { openBuiltIn(url) })
                return
            }
        }
        openBuiltIn(url)
    }

    private fun openBuiltIn(url: String) {
        val renderer = dev.autobridge.browser.CarBrowserRuntime.renderer(carContext)
        if (!dev.autobridge.browser.BrowserSiteEntry.resumes(renderer.livePageUrl, url)) {
            renderer.load(url)
        }
        CarNavigation.open(screenManager, "CarBrowserScreen") { dev.autobridge.car.CarBrowserScreen(carContext) }
    }

    // --- Drawing -------------------------------------------------------------------------------

    private fun render() {
        val target = surface ?: return
        if (!active || !MirrorSurfaceOwnership.isOwner(this) || !target.isValid) return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val density = (if (surfaceDpi > 0) surfaceDpi else BASELINE_DPI) / BASELINE_DPI.toFloat()
        val hasNotes = tiles.any { it is Tile.Site && it.note != null }
        val current = layout ?: StreamingGridLayout.compute(
            safeArea(), density, tiles.size, hasNotes,
            sections = sectionSizes, banner = chromeStatus != DesktopLinks.ChromeStatus.UNAVAILABLE
        ).also {
            layout = it
            scroll = scroll.coerceIn(0f, it.maxScroll)
            cardImages.clear()
        }
        val look = HomeDashboardLook.current()
        CarSurfaceCanvas.draw(carContext, target) { canvas -> paint(canvas, current, look) }
    }

    private fun paint(canvas: Canvas, layout: StreamingGridLayout, look: HomeDashboardLook) {
        val d = layout.density
        val face = AppFonts.forChoice(look.font, look.thai)
        val bold = Typeface.create(face, 700, false)
        val medium = Typeface.create(face, 500, false)
        canvas.drawColor(look.background)

        textPaint.typeface = bold
        textPaint.textSize = look.text(26f) * d
        textPaint.color = look.textPrimary
        val header = layout.title
        canvas.drawText(carContext.getString(R.string.car_streaming_title), header.left, baselineIn(textPaint, header), textPaint)

        canvas.save()
        canvas.clipRect(layout.viewport.left, layout.viewport.top, layout.viewport.right, layout.viewport.bottom)
        val radius = layout.cards.firstOrNull()?.let { it.width * 0.12f * 0.5f } ?: 0f
        layout.banner?.let { drawBanner(canvas, it.offset(-scroll), look, medium, d) }
        layout.headings.forEachIndexed { i, heading ->
            val box = heading.offset(-scroll)
            if (box.bottom < layout.viewport.top || box.top > layout.viewport.bottom) return@forEachIndexed
            textPaint.typeface = bold
            textPaint.textSize = look.text(19f) * d
            textPaint.color = look.textSecondary
            canvas.drawText(sectionTitles.getOrElse(i) { "" }, box.left + 4f * d, baselineIn(textPaint, box), textPaint)
        }
        tiles.forEachIndexed { index, tile ->
            val card = layout.cards[index].offset(-scroll)
            val label = layout.labels[index].offset(-scroll)
            if (label.bottom < layout.viewport.top || card.top > layout.viewport.bottom) return@forEachIndexed
            drawCard(canvas, tile, card)
            if (index == pressed) {
                rect.set(card.left, card.top, card.right, card.bottom)
                stroke.color = look.accent
                stroke.strokeWidth = 4f * d
                canvas.drawRoundRect(rect, radius * 2f, radius * 2f, stroke)
            }
            drawLabel(canvas, tile, label, look, medium, d)
        }
        canvas.restore()

        layout.scrollUp?.let { drawScrollButton(canvas, it, d, up = true, enabled = scroll > 0f, look = look) }
        layout.scrollDown?.let { drawScrollButton(canvas, it, d, up = false, enabled = scroll < layout.maxScroll, look = look) }
    }

    /** "A desktop browser is ready" or "needs Chrome or Brave" with a Set up pill, in one strip. */
    private fun drawBanner(canvas: Canvas, box: MenuBox, look: HomeDashboardLook, face: Typeface, d: Float) {
        val missing = chromeStatus == DesktopLinks.ChromeStatus.MISSING
        rect.set(box.left, box.top, box.right, box.bottom)
        fill.color = look.control
        canvas.drawRoundRect(rect, 12f * d, 12f * d, fill)
        textPaint.typeface = face
        textPaint.textSize = look.text(16f) * d
        var textRight = box.right - 16f * d
        if (missing) {
            val label = carContext.getString(R.string.streaming_chrome_install)
            val pillW = textPaint.measureText(label) + 28f * d
            val pillH = box.height - 14f * d
            rect.set(box.right - 10f * d - pillW, box.top + 7f * d, box.right - 10f * d, box.top + 7f * d + pillH)
            fill.color = look.accent
            canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, fill)
            textPaint.color = look.onAccent
            canvas.drawText(label, rect.left + 14f * d, baselineIn(textPaint, MenuBox(rect.left, rect.top, rect.right, rect.bottom)), textPaint)
            textRight = rect.left - 12f * d
        }
        textPaint.color = look.textPrimary
        val message = carContext.getString(if (missing) R.string.streaming_chrome_missing else R.string.streaming_chrome_ready)
        val shown = TextUtils.ellipsize(message, textPaint, textRight - (box.left + 16f * d), TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, box.left + 16f * d, baselineIn(textPaint, box), textPaint)
    }

    private fun drawCard(canvas: Canvas, tile: Tile, card: MenuBox) {
        val style = when (tile) {
            is Tile.Site -> StreamingIcons.styleFor(tile.title, tile.url)
            is Tile.Manage -> StreamingIcons.MANAGE
        }
        val starred = tile is Tile.Site && tile.starred
        val width = card.width.toInt().coerceAtLeast(1)
        val height = card.height.toInt().coerceAtLeast(1)
        val key = "${(tile as? Tile.Site)?.url ?: "manage"}|$width|$height|$starred"
        val bitmap = cardImages.getOrPut(key) { StreamingIcons.cardBitmap(style, width, height, starred) }
        rect.set(card.left, card.top, card.right, card.bottom)
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
    }

    private fun drawLabel(canvas: Canvas, tile: Tile, label: MenuBox, look: HomeDashboardLook, face: Typeface, d: Float) {
        val name = when (tile) {
            is Tile.Site -> tile.title
            is Tile.Manage -> carContext.getString(R.string.car_streaming_manage)
        }
        textPaint.typeface = face
        textPaint.textSize = look.text(17f) * d
        textPaint.color = look.textPrimary
        val nameBox = MenuBox(label.left, label.top, label.right, label.top + StreamingGridLayout.LABEL * d)
        val shown = TextUtils.ellipsize(name, textPaint, label.width - 8f * d, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, label.left + 4f * d, baselineIn(textPaint, nameBox), textPaint)
        val note = (tile as? Tile.Site)?.note ?: return
        textPaint.textSize = look.text(13f) * d
        textPaint.color = look.textSecondary
        val noteBox = MenuBox(label.left, nameBox.bottom, label.right, nameBox.bottom + StreamingGridLayout.NOTE * d)
        canvas.drawText(
            TextUtils.ellipsize(note, textPaint, label.width - 8f * d, TextUtils.TruncateAt.END).toString(),
            label.left + 4f * d, baselineIn(textPaint, noteBox), textPaint
        )
    }

    private fun drawScrollButton(canvas: Canvas, box: MenuBox, d: Float, up: Boolean, enabled: Boolean, look: HomeDashboardLook) {
        val radius = box.width / 2f
        fill.color = look.control
        canvas.drawCircle(box.centerX, box.centerY, radius, fill)
        val half = radius * 0.30f
        val rise = half * 0.55f * if (up) 1f else -1f
        chevron.reset()
        chevron.moveTo(box.centerX - half, box.centerY + rise)
        chevron.lineTo(box.centerX, box.centerY - rise)
        chevron.lineTo(box.centerX + half, box.centerY + rise)
        stroke.color = if (enabled) look.textPrimary else HomeDashboardTheme.withAlpha(look.textSecondary, 0.35f)
        stroke.strokeWidth = 3f * d
        canvas.drawPath(chevron, stroke)
    }

    private fun baselineIn(paint: Paint, box: MenuBox): Float {
        val metrics = paint.fontMetrics
        return box.top + (box.height - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
    }

    private companion object {
        /** The head unit's dpi that one dp is drawn at when it does not say (the car home's baseline). */
        const val BASELINE_DPI = 160
    }
}
