package dev.autobridge.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import dev.autobridge.core.model.Insets
import dev.autobridge.core.model.RotationMode
import dev.autobridge.core.model.ScaleMode
import dev.autobridge.core.model.Size
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.display.MirrorDiagnostics
import dev.autobridge.input.DisplayTransform
import dev.autobridge.settings.MirrorSettings
import kotlin.math.roundToInt

/**
 * App-owned output renderer for geometry and frame-rate control.
 *
 * Capture still comes from the consented default display, so this mode is not a privileged
 * screen-off solution. It deliberately sits beside the direct AUTO_MIRROR path: the latter stays
 * zero-copy and is the default, while this engine owns an ImageReader, a bounded frame hand-off,
 * and the car-surface Canvas draw needed for FILL/STRETCH/ONE_TO_ONE.
 */
class SelfDrawnMirrorEngine(
    context: Context
) : MirrorEngine {
    companion object {
        private const val TAG = "AutoBridgeSelfDrawn"
        private const val MAX_IMAGES = 2
        private const val DEFAULT_DPI = 160

        /**
         * Fermata-style floating card: the mirrored phone image sits inset from every edge of the
         * car surface with rounded corners, rather than filling it edge to edge. Authored in dp so
         * the card reads the same physical size on every head unit; converted to px per surface
         * from its reported dpi, the same basis [dev.autobridge.browser.AutoUiSizes] uses.
         */
        private const val CARD_MARGIN_DP = 20f
        private const val CARD_CORNER_RADIUS_DP = 16f

        private fun dpToPx(dp: Float, dpi: Int): Float =
            dp * (dpi.takeIf { it > 0 } ?: DEFAULT_DPI) / 160f
    }

    private val appContext = context.applicationContext
    private val targetFpsProvider: () -> Int = {
        MirrorSettings.preferredFps?.coerceIn(1, 60)
            ?: RuntimeContextStore.context.value.vehicleProfile?.preferredFps?.coerceIn(1, 60)
            ?: 60
    }
    private val stateLock = Any()
    private val drawLock = Any()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private var running = false
    private var projection: MediaProjection? = null
    private var captureDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private var pendingImage: Image? = null
    private var renderScheduled = false
    private var output: OutputTarget? = null
    private var generation = 0L
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var sourceDpi = DEFAULT_DPI
    private var bitmap: Bitmap? = null
    private var rowScratch = ByteArray(0)
    private var pixelScratch = IntArray(0)
    private var lastRenderNanos = 0L

    override val supportedScaleModes: Set<ScaleMode> = ScaleMode.entries.toSet()
    override val supportedRotationModes: Set<RotationMode> = RotationMode.entries.toSet()

    /** Synchronous entry point used by MirrorCoordinator while it owns session state. */
    fun startBlocking(newProjection: MediaProjection): Boolean {
        stopBlocking()
        val source = phoneDisplaySize() ?: run {
            StructuredLogBridge.warn("Cannot determine the default display size")
            return false
        }
        val reader = runCatching {
            ImageReader.newInstance(source.width, source.height, PixelFormat.RGBA_8888, MAX_IMAGES)
        }.getOrElse { error ->
            Log.e(TAG, "Could not create ImageReader", error)
            return false
        }
        val capture = HandlerThread("AutoBridgeCapture").apply { start() }
        val render = HandlerThread("AutoBridgeRender").apply { start() }
        val captureHandler = Handler(capture.looper)
        val renderHandler = Handler(render.looper)

        synchronized(stateLock) {
            running = true
            projection = newProjection
            imageReader = reader
            captureThread = capture
            renderThread = render
            this.renderHandler = renderHandler
            sourceWidth = source.width
            sourceHeight = source.height
            sourceDpi = sourceDpi()
            bitmap = null
            lastRenderNanos = 0L
            MirrorDiagnostics.resetFrameStats()
        }
        reader.setOnImageAvailableListener({ availableReader -> onImageAvailable(availableReader) }, captureHandler)

        val virtual = runCatching {
            newProjection.createVirtualDisplay(
                "AutoBridgeSelfDrawnCapture",
                source.width,
                source.height,
                sourceDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            ) ?: error("No capture virtual display returned")
        }.getOrElse { error ->
            Log.e(TAG, "Could not create self-drawn capture display", error)
            stopBlocking()
            return false
        }
        synchronized(stateLock) {
            if (!running || imageReader !== reader) {
                runCatching { virtual.release() }
                return false
            }
            captureDisplay = virtual
        }
        MirrorDiagnostics.record("self_drawn_capture_created")
        StructuredLogBridge.info("Self-drawn renderer started ${source.width}x${source.height} @ ${sourceDpi}dpi")
        return true
    }

    /** Synchronous surface entry point used by MirrorCoordinator. */
    fun attachSurfaceBlocking(surface: Surface, width: Int, height: Int, dpi: Int): Boolean {
        if (!surface.isValid || width <= 0 || height <= 0) return false
        synchronized(stateLock) {
            if (!running) return false
            generation += 1
            output = OutputTarget(surface, width, height, dpi, generation)
            renderScheduled = false
        }
        val margin = dpToPx(CARD_MARGIN_DP, dpi).roundToInt()
        DisplayTransform.setCardMargin(Insets(left = margin, top = margin, right = margin, bottom = margin))
        MirrorDiagnostics.record("self_drawn_surface_attached")
        scheduleRender()
        return true
    }

    fun detachSurfaceBlocking(surface: Surface? = null) {
        synchronized(drawLock) {
            synchronized(stateLock) {
                val current = output
                if (current != null && (surface == null || current.surface === surface)) {
                    generation += 1
                    output = null
                }
            }
        }
        DisplayTransform.setCardMargin(Insets.ZERO)
        MirrorDiagnostics.record("self_drawn_surface_detached")
    }

    fun stopBlocking() {
        val resources: ResourcesToStop
        synchronized(drawLock) {
            synchronized(stateLock) {
                running = false
                generation += 1
                resources = ResourcesToStop(
                    pendingImage = pendingImage,
                    reader = imageReader,
                    virtualDisplay = captureDisplay,
                    captureThread = captureThread,
                    renderThread = renderThread
                )
                pendingImage = null
                output = null
                projection = null
                imageReader = null
                captureDisplay = null
                captureThread = null
                renderThread = null
                renderHandler = null
                bitmap = null
                renderScheduled = false
                lastRenderNanos = 0L
            }
        }
        runCatching { resources.pendingImage?.close() }
        runCatching { resources.reader?.close() }
        runCatching { resources.virtualDisplay?.release() }
        resources.captureThread?.quitSafely()
        resources.renderThread?.quitSafely()
        DisplayTransform.setCardMargin(Insets.ZERO)
        MirrorDiagnostics.record("self_drawn_stopped")
    }

    fun isRunning(): Boolean = synchronized(stateLock) { running && captureDisplay != null }

    fun sourceSize(): Size? = synchronized(stateLock) {
        Size(sourceWidth, sourceHeight).takeIf { it.isValid && running }
    }

    fun isRenderingReady(): Boolean = synchronized(stateLock) {
        running && captureDisplay != null && output?.surface?.isValid == true
    }

    override suspend fun start(request: MirrorStartRequest): Boolean = startBlocking(request.projection)

    override suspend fun stop() = stopBlocking()

    override suspend fun attachSurface(surface: Surface, width: Int, height: Int, dpi: Int): Boolean =
        attachSurfaceBlocking(surface, width, height, dpi)

    override suspend fun detachSurface(surface: Surface?) = detachSurfaceBlocking(surface)

    override fun setScaleMode(mode: ScaleMode) {
        // DisplayTransform is the shared volatile geometry source; the next frame takes a new
        // immutable RenderPlan. The coordinator keeps AUTO_MIRROR on FIT separately.
    }

    override fun setRotationMode(mode: RotationMode) {
        // See setScaleMode: rotation is sampled into RenderPlan at frame time.
    }

    private fun scheduleRender() {
        synchronized(stateLock) {
            if (!running || renderScheduled) return
            renderScheduled = true
            renderHandler?.post(::renderPending)
        }
    }

    private fun onImageAvailable(availableReader: ImageReader) {
        var image: Image? = null
        var sourceDiscarded = 0L
        while (true) {
            val next = runCatching { availableReader.acquireNextImage() }.getOrNull() ?: break
            image?.let {
                runCatching { it.close() }
                sourceDiscarded += 1L
            }
            image = next
        }
        if (sourceDiscarded > 0L) MirrorDiagnostics.recordFrameDropped(sourceDiscarded)
        val newest = image ?: return
        var replaced: Image? = null
        var accepted = false
        synchronized(stateLock) {
            if (running && imageReader === availableReader) {
                replaced = pendingImage
                pendingImage = newest
                MirrorDiagnostics.recordFrameCaptured()
                accepted = true
                if (!renderScheduled) {
                    renderScheduled = true
                    renderHandler?.post(::renderPending)
                }
            }
        }
        runCatching { replaced?.close() }
        if (!accepted) runCatching { newest.close() }
        if (replaced != null) MirrorDiagnostics.recordFrameDropped()
    }

    private fun renderPending() {
        val image = synchronized(stateLock) {
            val next = pendingImage
            pendingImage = null
            next
        }
        if (image != null) {
            try {
                renderImage(image)
            } finally {
                runCatching { image.close() }
            }
        }

        val scheduleAgain = synchronized(stateLock) {
            if (running && pendingImage != null) {
                true
            } else {
                renderScheduled = false
                false
            }
        }
        if (scheduleAgain) renderHandler?.post(::renderPending)
    }

    private fun renderImage(image: Image) {
        val target = synchronized(stateLock) { output }
        if (target == null || !target.surface.isValid) {
            MirrorDiagnostics.recordFrameDropped()
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val fps = targetFpsProvider().coerceIn(1, 60)
        val frameInterval = 1_000_000_000L / fps
        if (lastRenderNanos != 0L && now - lastRenderNanos < frameInterval) {
            MirrorDiagnostics.recordFrameDropped()
            return
        }

        val source = ensureBitmap(image.width, image.height) ?: run {
            MirrorDiagnostics.recordFrameDropped()
            return
        }
        if (!copyImage(image, source)) {
            MirrorDiagnostics.recordFrameDropped()
            return
        }
        val plan = RenderPlan.resolve(
            surfaceGeneration = target.generation,
            outputWidth = target.width,
            outputHeight = target.height,
            sourceWidth = source.width,
            sourceHeight = source.height,
            cardCornerRadiusPx = dpToPx(CARD_CORNER_RADIUS_DP, target.dpi)
        ) ?: run {
            MirrorDiagnostics.recordFrameDropped()
            return
        }
        synchronized(drawLock) {
            val stillCurrent = synchronized(stateLock) {
                running && output?.generation == plan.surfaceGeneration && output?.surface === target.surface
            }
            if (!stillCurrent) {
                MirrorDiagnostics.recordFrameDropped()
                return
            }

            val canvas = runCatching { target.surface.lockCanvas(null) }.getOrNull()
            if (canvas == null) {
                MirrorDiagnostics.recordFrameDropped()
                return
            }
            var posted = false
            try {
                plan.draw(canvas, source, paint)
                posted = true
            } catch (error: RuntimeException) {
                Log.w(TAG, "Canvas draw failed for surface generation ${plan.surfaceGeneration}", error)
            } finally {
                runCatching { target.surface.unlockCanvasAndPost(canvas) }
            }
            if (posted) {
                lastRenderNanos = now
                val latencyMs = image.timestamp.takeIf { it > 0L }
                    ?.let { ((now - it).coerceAtLeast(0L) / 1_000_000L) }
                MirrorDiagnostics.recordFrameRendered(latencyMs)
            } else {
                MirrorDiagnostics.recordFrameDropped()
            }
        }
    }

    private fun ensureBitmap(width: Int, height: Int): Bitmap? {
        synchronized(stateLock) {
            if (!running || width <= 0 || height <= 0) return null
            if (bitmap?.width != width || bitmap?.height != height) {
                bitmap?.recycle()
                bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
                    .getOrNull()
            }
            return bitmap
        }
    }

    private fun copyImage(image: Image, target: Bitmap): Boolean {
        val plane = image.planes.firstOrNull() ?: return false
        val buffer = plane.buffer ?: return false
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return false
        return runCatching {
            if (pixelStride == 4 && rowStride == image.width * 4) {
                buffer.rewind()
                target.copyPixelsFromBuffer(buffer)
            } else {
                val rowBytes = image.width * pixelStride
                if (rowScratch.size < rowBytes) rowScratch = ByteArray(rowBytes)
                if (pixelScratch.size < image.width) pixelScratch = IntArray(image.width)
                for (y in 0 until image.height) {
                    val rowStart = y * rowStride
                    if (rowStart + rowBytes > buffer.limit()) return@runCatching false
                    buffer.position(rowStart)
                    buffer.get(rowScratch, 0, rowBytes)
                    for (x in 0 until image.width) {
                        val offset = x * pixelStride
                        val red = rowScratch[offset].toInt() and 0xff
                        val green = if (offset + 1 < rowBytes) rowScratch[offset + 1].toInt() and 0xff else red
                        val blue = if (offset + 2 < rowBytes) rowScratch[offset + 2].toInt() and 0xff else red
                        val alpha = if (offset + 3 < rowBytes) rowScratch[offset + 3].toInt() and 0xff else 0xff
                        pixelScratch[x] = Color.argb(alpha, red, green, blue)
                    }
                    target.setPixels(pixelScratch, 0, image.width, 0, y, image.width, 1)
                }
            }
            true
        }.getOrElse { error ->
            Log.w(TAG, "Image copy failed", error)
            false
        }
    }

    private fun phoneDisplaySize(): Size? {
        val manager = appContext.getSystemService(DisplayManager::class.java)
        val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null && Build.VERSION.SDK_INT >= 30) {
            val bounds = appContext.createDisplayContext(display)
                .getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics
                ?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return Size(bounds.width(), bounds.height())
            }
        }
        if (display != null) {
            @Suppress("DEPRECATION")
            val point = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            if (point.x > 0 && point.y > 0) return Size(point.x, point.y)
        }
        val metrics = appContext.resources.displayMetrics
        return Size(metrics.widthPixels, metrics.heightPixels).takeIf { it.isValid }
    }

    @Suppress("DEPRECATION")
    private fun sourceDpi(): Int {
        val display = appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            if (metrics.densityDpi > 0) return metrics.densityDpi
        }
        return appContext.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: DEFAULT_DPI
    }

    private data class OutputTarget(
        val surface: Surface,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val generation: Long
    )

    private data class ResourcesToStop(
        val pendingImage: Image?,
        val reader: ImageReader?,
        val virtualDisplay: VirtualDisplay?,
        val captureThread: HandlerThread?,
        val renderThread: HandlerThread?
    )

    /** Keeps this renderer independent from the coordinator's lock/logging implementation. */
    private object StructuredLogBridge {
        fun info(message: String) = dev.autobridge.logging.StructuredLog.i(TAG, message)
        fun warn(message: String) = dev.autobridge.logging.StructuredLog.w(TAG, message)
    }
}
