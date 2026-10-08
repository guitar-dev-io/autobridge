package dev.autobridge.duoscreen.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import dev.autobridge.duoscreen.layout.DuoScreenLayout
import dev.autobridge.duoscreen.layout.DuoScreenLayout.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Draws every pane into the one Surface the car host gives us.
 *
 * Each pane's VirtualDisplay renders into its own SurfaceTexture, sampled as
 * GL_TEXTURE_EXTERNAL_OES and drawn as a quad at the pane's rect — which is what makes a *moving*
 * or *resizing* pane cheap: the quad changes every frame while the VirtualDisplay keeps its old
 * size until [DuoScreenResizeDebouncer] says the gesture has settled.
 *
 * One pane filling the whole surface does not need any of this (the display can render straight
 * into the car Surface, as Phase 1 does); this starts earning its keep at two.
 *
 * All GL work happens on a single HandlerThread with the EGL context current on it; the public
 * entry points are blocking hand-offs to that thread, the same shape
 * [dev.autobridge.mirror.SelfDrawnMirrorEngine] uses for its render thread.
 */
class DuoScreenCompositor {
    private companion object {
        const val TAG = "AutoBridgeDuoGl"
        const val FLOAT_BYTES = 4
        const val COORDS_PER_VERTEX = 2
        const val THREAD_JOIN_TIMEOUT_MS = 1_000L

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        const val SOLID_VERTEX_SHADER = """
            attribute vec4 aPosition;
            void main() {
                gl_Position = aPosition;
            }
        """

        const val SOLID_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """

        /**
         * The app-drawn chrome (control bar, focus edge, arrange labels) as a plain 2D texture over
         * the panes. Its own pair because the pane program samples an external texture.
         */
        const val OVERLAY_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        /** The overlay bitmap's rows run top-down, so the strip's bottom corners sample t = 1. */
        val OVERLAY_TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        /** Behind and between the panes: the design's surface colour (#0B0D10), not black. */
        val CLEAR_COLOR = floatArrayOf(0x0B / 255f, 0x0D / 255f, 0x10 / 255f, 1f)

        /** Tex coords for a triangle strip: bottom-left, bottom-right, top-left, top-right. */
        val TEX_COORDS = floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f
        )

        /** Selection frame drawn in edit mode: thickness in px and its colour. */
        const val HIGHLIGHT_THICKNESS_PX = 8
        val HIGHLIGHT_COLOR = floatArrayOf(1f, 0.65f, 0f, 1f)

        /** The grabbed seam, drawn brighter than the selection frame so the grab is unmistakable. */
        val DIVIDER_COLOR = floatArrayOf(1f, 0.85f, 0.3f, 1f)

        /**
         * Fill for a pane with nothing to show yet — no app picked, or its app still starting — so
         * the driver sees where each pane sits instead of one black screen. Dark tints from
         * docs/design/02_CarDuo.png (green, rose), plus a blue for a third pane; picked by pane id.
         */
        val EMPTY_PANE_COLORS = arrayOf(
            floatArrayOf(0x16 / 255f, 0x24 / 255f, 0x1F / 255f, 1f),
            floatArrayOf(0x24 / 255f, 0x1B / 255f, 0x20 / 255f, 1f),
            floatArrayOf(0x17 / 255f, 0x1E / 255f, 0x2A / 255f, 1f)
        )
    }

    private class Pane(
        val id: Int,
        val textureId: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        var rect: Rect
    ) {
        val transform = FloatArray(16)
        var hasFrame = false
    }

    // A new HandlerThread per session: the host can deliver its Surface again after (or without)
    // onSurfaceDestroyed, and a finished Thread cannot be started a second time.
    private val glThread = DuoScreenThreadSlot(
        create = { HandlerThread("AutoBridgeDuoGl").apply { start() } },
        shutdown = { thread ->
            thread.quitSafely()
            thread.join(THREAD_JOIN_TIMEOUT_MS)
            if (thread.isAlive) Log.w(TAG, "GL thread did not exit within ${THREAD_JOIN_TIMEOUT_MS}ms")
        }
    )
    private var handler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** Kept so a second host Surface can be bound to the same context; see [attachBlocking]. */
    private var eglConfig: EGLConfig? = null
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var texMatrixHandle = 0
    private var textureHandle = 0

    private var solidProgram = 0
    private var solidPositionHandle = 0
    private var solidColorHandle = 0

    private val panes = LinkedHashMap<Int, Pane>()
    private var outputWidth = 0
    private var outputHeight = 0
    private var renderScheduled = false
    private var highlightedPaneId: Int? = null
    private var dividerBand: Rect? = null

    private var overlayProgram = 0
    private var overlayPositionHandle = 0
    private var overlayTexCoordHandle = 0
    private var overlayTextureHandle = 0
    private var overlayTexture = 0

    /** Paints the chrome; replaced, never mutated, so it is safe to run here on the GL thread. */
    private var overlayPainter: ((Canvas) -> Unit)? = null
    private var overlayBitmap: Bitmap? = null
    private var overlayDirty = false
    private var overlayUploadedSize = 0L
    private val overlayTexCoordBuffer: FloatBuffer = floatBuffer(OVERLAY_TEX_COORDS)

    private val texCoordBuffer: FloatBuffer = floatBuffer(TEX_COORDS)
    private val vertexBuffer: FloatBuffer = floatBuffer(FloatArray(8))

    /** True once a GL context exists, whether or not a host Surface is bound to it right now. */
    val hasContext: Boolean get() = handler != null && eglContext != EGL14.EGL_NO_CONTEXT

    /**
     * Binds [output] as the surface everything is drawn into, starting the GL thread and the EGL
     * context the first time. Blocks until it is ready.
     *
     * Called again after [detachBlocking] for the *same* session: the context, its pane textures
     * and the VirtualDisplays rendering into them are all still alive, so the host taking its
     * Surface away and giving a new one back costs one EGLSurface, not a relaunch of every pane
     * app. Only [stopBlocking] ends a session.
     */
    fun attachBlocking(output: Surface, width: Int, height: Int): Boolean {
        if (!output.isValid || width <= 0 || height <= 0) return false
        val threadHandler = handler ?: Handler(glThread.start().looper).also { handler = it }
        val ready = runOnGlThread(threadHandler) {
            outputWidth = width
            outputHeight = height
            // Whatever was selected or grabbed belonged to a gesture the surface swap interrupted.
            highlightedPaneId = null
            dividerBand = null
            overlayDirty = true
            // Order matters: the shaders are GL calls, so they need a context that is already
            // current on a surface. On a re-bind both already exist and only the surface changes.
            val hadContext = eglContext != EGL14.EGL_NO_CONTEXT
            when {
                !hadContext && !initEglContext() -> false
                !bindWindowSurface(output) -> false
                !hadContext && !initProgram() -> false
                else -> true
            }
        }
        // A failed first bind must clean up here or the thread and any half-built EGL state would
        // outlive it; a failed re-bind leaves the session intact for the next Surface to try.
        if (!ready && !hasContext) stopBlocking()
        if (ready) scheduleRender()
        return ready
    }

    /**
     * Releases the host Surface and nothing else: the GL thread, the context and every pane
     * texture stay, ready for the next [attachBlocking].
     *
     * While detached the pane apps keep rendering into their SurfaceTextures until those queues
     * fill and the apps block in dequeueBuffer, which is the right thing for a pane nobody is
     * looking at — they neither burn battery nor lose their state, and the first frame after the
     * surface comes back is whatever they last drew.
     */
    fun detachBlocking() {
        val threadHandler = handler ?: return
        runOnGlThread(threadHandler) {
            releaseWindowSurface()
            true
        }
    }

    /**
     * Creates a pane's texture and returns the Surface its VirtualDisplay should render into, or
     * null. The SurfaceTexture's buffer size is the pane's current rect, so the hosted app lays
     * out for the size it is actually shown at.
     */
    fun addPaneBlocking(paneId: Int, rect: Rect): Surface? {
        val threadHandler = handler ?: return null
        var created: Surface? = null
        runOnGlThread(threadHandler) {
            removePaneLocked(paneId)
            val textureId = createExternalTexture()
            if (textureId == 0) return@runOnGlThread false
            val surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(rect.width.coerceAtLeast(1), rect.height.coerceAtLeast(1))
            }
            val surface = Surface(surfaceTexture)
            val pane = Pane(paneId, textureId, surfaceTexture, surface, rect)
            surfaceTexture.setOnFrameAvailableListener({
                pane.hasFrame = true
                scheduleRender()
            }, threadHandler)
            panes[paneId] = pane
            created = surface
            true
        }
        return created
    }

    /** Moves/resizes a pane's quad immediately; the stretched old frame shows until the next one. */
    fun setRect(paneId: Int, rect: Rect) {
        val threadHandler = handler ?: return
        threadHandler.post {
            panes[paneId]?.let { pane ->
                pane.rect = rect
                scheduleRender()
            }
        }
    }

    /**
     * Matches the texture's buffer size to the pane's rect, which is the expensive half of a resize
     * and is why the caller waits for the gesture to settle before calling it.
     */
    fun commitSize(paneId: Int, width: Int, height: Int) {
        val threadHandler = handler ?: return
        threadHandler.post {
            panes[paneId]?.surfaceTexture?.setDefaultBufferSize(
                width.coerceAtLeast(1),
                height.coerceAtLeast(1)
            )
        }
    }

    fun removePane(paneId: Int) {
        val threadHandler = handler ?: return
        threadHandler.post {
            removePaneLocked(paneId)
            if (highlightedPaneId == paneId) highlightedPaneId = null
            scheduleRender()
        }
    }

    /** Re-inserts a pane last so it draws over the others, matching the pane set's own z-order. */
    fun bringToFront(paneId: Int) {
        val threadHandler = handler ?: return
        threadHandler.post {
            val pane = panes.remove(paneId) ?: return@post
            panes[paneId] = pane
            scheduleRender()
        }
    }

    /** Draws the edit-mode selection frame around [paneId], or clears it when null. */
    fun setHighlight(paneId: Int?) {
        val threadHandler = handler ?: return
        threadHandler.post {
            highlightedPaneId = paneId
            scheduleRender()
        }
    }

    /** Draws the grabbed seam as a filled band at [band], or clears it when null. */
    fun setDividerBand(band: Rect?) {
        val threadHandler = handler ?: return
        threadHandler.post {
            dividerBand = band
            scheduleRender()
        }
    }

    /**
     * Sets what the chrome overlay draws, or clears it when null. [painter] runs on the GL thread
     * into a surface-sized transparent bitmap, so it must only read what it captured.
     */
    fun setOverlay(painter: ((Canvas) -> Unit)?) {
        val threadHandler = handler ?: return
        threadHandler.post {
            overlayPainter = painter
            overlayDirty = true
            scheduleRender()
        }
    }

    /** Releases every pane, the EGL context and the GL thread. Safe to call more than once. */
    fun stopBlocking() {
        val threadHandler = handler ?: return
        handler = null
        runOnGlThread(threadHandler) {
            panes.keys.toList().forEach(::removePaneLocked)
            releaseEgl()
            true
        }
        glThread.stop()
    }

    private fun scheduleRender() {
        val threadHandler = handler ?: return
        if (renderScheduled) return
        renderScheduled = true
        threadHandler.post {
            renderScheduled = false
            render()
        }
    }

    private fun render() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.w(TAG, "eglMakeCurrent failed: ${EGL14.eglGetError()}")
            return
        }
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(CLEAR_COLOR[0], CLEAR_COLOR[1], CLEAR_COLOR[2], CLEAR_COLOR[3])
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Insertion order is z-order: a pane brought to front is re-inserted last and draws on top.
        panes.values.forEach { pane ->
            if (pane.hasFrame) {
                runCatching { pane.surfaceTexture.updateTexImage() }
                    .onFailure { Log.w(TAG, "updateTexImage failed for pane ${pane.id}", it) }
                pane.surfaceTexture.getTransformMatrix(pane.transform)
                drawPane(pane)
            } else {
                drawEmptyPane(pane)
            }
        }

        highlightedPaneId?.let { id -> panes[id]?.let(::drawHighlight) }
        dividerBand?.let(::drawDividerBand)
        drawOverlay()

        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
        }
    }

    /** Repaints the chrome bitmap when it changed, then blends it over everything drawn so far. */
    private fun drawOverlay() {
        val painter = overlayPainter ?: return
        if (overlayProgram == 0 || outputWidth <= 0 || outputHeight <= 0) return
        if (overlayDirty) {
            var bitmap = overlayBitmap
            if (bitmap == null || bitmap.width != outputWidth || bitmap.height != outputHeight) {
                bitmap?.recycle()
                bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                overlayBitmap = bitmap
            }
            bitmap.eraseColor(0)
            runCatching { painter(Canvas(bitmap)) }.onFailure { Log.w(TAG, "Overlay paint failed", it) }
            if (overlayTexture == 0) overlayTexture = createOverlayTexture()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
            val size = (bitmap.width.toLong() shl 32) or bitmap.height.toLong()
            // Same size as last time: replace the pixels instead of reallocating the texture.
            if (size == overlayUploadedSize) GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            else GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            overlayUploadedSize = size
            overlayDirty = false
        }
        if (overlayTexture == 0) return

        GLES20.glUseProgram(overlayProgram)
        writeVertices(Rect(0, 0, outputWidth, outputHeight))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTexture)
        GLES20.glUniform1i(overlayTextureHandle, 0)
        GLES20.glEnableVertexAttribArray(overlayPositionHandle)
        GLES20.glVertexAttribPointer(
            overlayPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
            COORDS_PER_VERTEX * FLOAT_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(overlayTexCoordHandle)
        GLES20.glVertexAttribPointer(
            overlayTexCoordHandle, 2, GLES20.GL_FLOAT, false, 2 * FLOAT_BYTES, overlayTexCoordBuffer
        )
        // The bitmap is premultiplied, which is what this blend expects.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(overlayPositionHandle)
        GLES20.glDisableVertexAttribArray(overlayTexCoordHandle)
        GLES20.glUseProgram(program)
    }

    private fun createOverlayTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        if (textureId == 0) return 0
        overlayUploadedSize = 0L
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textureId
    }

    /** Four filled bands inside the pane's edges; GL line width above 1px is not portable. */
    private fun drawHighlight(pane: Pane) {
        if (solidProgram == 0) return
        val rect = pane.rect
        val thickness = HIGHLIGHT_THICKNESS_PX.coerceAtMost(minOf(rect.width, rect.height) / 2)
        if (thickness <= 0) return
        GLES20.glUseProgram(solidProgram)
        GLES20.glUniform4fv(solidColorHandle, 1, HIGHLIGHT_COLOR, 0)
        GLES20.glEnableVertexAttribArray(solidPositionHandle)

        drawBand(rect.left, rect.top, rect.right, rect.top + thickness)
        drawBand(rect.left, rect.bottom - thickness, rect.right, rect.bottom)
        drawBand(rect.left, rect.top, rect.left + thickness, rect.bottom)
        drawBand(rect.right - thickness, rect.top, rect.right, rect.bottom)

        GLES20.glDisableVertexAttribArray(solidPositionHandle)
        GLES20.glUseProgram(program)
    }

    private fun drawEmptyPane(pane: Pane) {
        if (solidProgram == 0) return
        val rect = pane.rect
        if (rect.width <= 0 || rect.height <= 0) return
        GLES20.glUseProgram(solidProgram)
        GLES20.glUniform4fv(
            solidColorHandle, 1, EMPTY_PANE_COLORS[Math.floorMod(pane.id, EMPTY_PANE_COLORS.size)], 0
        )
        GLES20.glEnableVertexAttribArray(solidPositionHandle)
        drawBand(rect.left, rect.top, rect.right, rect.bottom)
        GLES20.glDisableVertexAttribArray(solidPositionHandle)
        GLES20.glUseProgram(program)
    }

    /** One filled band over the seam the driver has hold of, drawn above every pane. */
    private fun drawDividerBand(band: Rect) {
        if (solidProgram == 0) return
        if (band.width <= 0 || band.height <= 0) return
        GLES20.glUseProgram(solidProgram)
        GLES20.glUniform4fv(solidColorHandle, 1, DIVIDER_COLOR, 0)
        GLES20.glEnableVertexAttribArray(solidPositionHandle)
        drawBand(band.left, band.top, band.right, band.bottom)
        GLES20.glDisableVertexAttribArray(solidPositionHandle)
        GLES20.glUseProgram(program)
    }

    private fun drawBand(left: Int, top: Int, right: Int, bottom: Int) {
        writeVertices(Rect(left, top, right - left, bottom - top))
        GLES20.glVertexAttribPointer(
            solidPositionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
            COORDS_PER_VERTEX * FLOAT_BYTES, vertexBuffer
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawPane(pane: Pane) {
        writeVertices(pane.rect)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, pane.textureId)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, pane.transform, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
            COORDS_PER_VERTEX * FLOAT_BYTES, vertexBuffer
        )
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(
            texCoordHandle, 4, GLES20.GL_FLOAT, false, 4 * FLOAT_BYTES, texCoordBuffer
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /** Pane rect (top-left origin, pixels) to the strip's four NDC corners. */
    private fun writeVertices(rect: Rect) {
        if (outputWidth <= 0 || outputHeight <= 0) return
        val left = 2f * rect.left / outputWidth - 1f
        val right = 2f * rect.right / outputWidth - 1f
        val top = 1f - 2f * rect.top / outputHeight
        val bottom = 1f - 2f * rect.bottom / outputHeight
        vertexBuffer.clear()
        vertexBuffer.put(left).put(bottom)
        vertexBuffer.put(right).put(bottom)
        vertexBuffer.put(left).put(top)
        vertexBuffer.put(right).put(top)
        vertexBuffer.position(0)
    }

    private fun removePaneLocked(paneId: Int) {
        val pane = panes.remove(paneId) ?: return
        runCatching { pane.surface.release() }
        runCatching { pane.surfaceTexture.setOnFrameAvailableListener(null) }
        runCatching { pane.surfaceTexture.release() }
        GLES20.glDeleteTextures(1, intArrayOf(pane.textureId), 0)
    }

    private fun initEglContext(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return fail("eglGetDisplay")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return fail("eglInitialize")

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0) ||
            configCount[0] == 0
        ) return fail("eglChooseConfig")

        eglConfig = configs[0]
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")
        return true
    }

    /**
     * Points the existing context at [output]. The programs and textures are owned by the context,
     * not by the surface, so a re-bind keeps every pane exactly as it was.
     */
    private fun bindWindowSurface(output: Surface): Boolean {
        val config = eglConfig ?: return fail("no EGLConfig")
        releaseWindowSurface()
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, output, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            return fail("eglMakeCurrent")
        }
        return true
    }

    private fun releaseWindowSurface() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(
            eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
        )
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun initProgram(): Boolean {
        program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) return false
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")

        solidProgram = linkProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
        if (solidProgram != 0) {
            solidPositionHandle = GLES20.glGetAttribLocation(solidProgram, "aPosition")
            solidColorHandle = GLES20.glGetUniformLocation(solidProgram, "uColor")
        } else {
            // The panes still composite; only the edit-mode frame and empty-pane tint are missing.
            Log.w(TAG, "Solid-colour program unavailable")
        }

        overlayProgram = linkProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        if (overlayProgram != 0) {
            overlayPositionHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
            overlayTexCoordHandle = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
            overlayTextureHandle = GLES20.glGetUniformLocation(overlayProgram, "uTexture")
        } else {
            // The panes still composite and take touch; only the drawn controls are missing.
            Log.w(TAG, "Overlay program unavailable")
        }
        return true
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) return 0
        val linkedProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(linkedProgram, vertexShader)
        GLES20.glAttachShader(linkedProgram, fragmentShader)
        GLES20.glLinkProgram(linkedProgram)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(linkedProgram, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(linkedProgram)}")
            GLES20.glDeleteProgram(linkedProgram)
            return 0
        }
        return linkedProgram
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        if (textureId == 0) return 0
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        return textureId
    }

    private fun releaseEgl() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (solidProgram != 0) {
            GLES20.glDeleteProgram(solidProgram)
            solidProgram = 0
        }
        if (overlayProgram != 0) {
            GLES20.glDeleteProgram(overlayProgram)
            overlayProgram = 0
        }
        if (overlayTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(overlayTexture), 0)
            overlayTexture = 0
        }
        overlayUploadedSize = 0L
        overlayBitmap?.recycle()
        overlayBitmap = null
        overlayPainter = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            releaseWindowSurface()
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglConfig = null
    }

    private fun fail(stage: String): Boolean {
        Log.e(TAG, "$stage failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        return false
    }

    private fun runOnGlThread(threadHandler: Handler, block: () -> Boolean): Boolean {
        val latch = CountDownLatch(1)
        var result = false
        threadHandler.post {
            result = runCatching(block).onFailure { Log.e(TAG, "GL work failed", it) }.getOrDefault(false)
            latch.countDown()
        }
        latch.await()
        return result
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
}
