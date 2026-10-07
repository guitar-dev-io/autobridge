package dev.autobridge.duoscreen.render

import android.graphics.Bitmap
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

        /**
         * Rounded-rect coverage in window pixels (gl_FragCoord, bottom-left origin). [uClip] is
         * left, bottom, right, top; a radius of 0 turns it off. highp where the GPU has it: at
         * mediump a coordinate past 1024 px is only good to about a pixel.
         */
        const val ROUNDED_CLIP = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform vec4 uClip;
            uniform float uRadius;
            float coverage() {
                if (uRadius <= 0.0) return 1.0;
                vec2 p = gl_FragCoord.xy;
                vec2 c = clamp(p, uClip.xy + uRadius, uClip.zw - uRadius);
                return clamp(uRadius - length(p - c) + 0.5, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
        """ + ROUNDED_CLIP + """
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                // Pane content is opaque; only the rounded corners are blended.
                gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, coverage());
            }
        """

        const val SOLID_VERTEX_SHADER = """
            attribute vec4 aPosition;
            void main() {
                gl_Position = aPosition;
            }
        """

        const val SOLID_FRAGMENT_SHADER = ROUNDED_CLIP + """
            uniform vec4 uColor;
            void main() {
                gl_FragColor = vec4(uColor.rgb, uColor.a * coverage());
            }
        """

        /** The controls overlay: a plain 2D texture (a painted Bitmap), drawn with alpha blending. */
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

        /**
         * Overlay tex coords in strip order (bottom-left, bottom-right, top-left, top-right). A
         * Bitmap uploads with its top row at t = 0, so the top corners take t = 0.
         */
        val OVERLAY_TEX_COORDS = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )

        /** Tex coords for a triangle strip: bottom-left, bottom-right, top-left, top-right. */
        val TEX_COORDS = floatArrayOf(
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f
        )

        /** What shows between and around the panes: the design's surface colour, #0B0D10. */
        val BACKGROUND_COLOR = floatArrayOf(0x0B / 255f, 0x0D / 255f, 0x10 / 255f, 1f)

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
    private var clipHandle = 0
    private var radiusHandle = 0
    private var solidClipHandle = 0
    private var solidRadiusHandle = 0

    /** Corner radius of every pane, in surface pixels; set on the GL thread via [setCornerRadius]. */
    private var cornerRadius = 0f

    private var overlayProgram = 0
    private var overlayPositionHandle = 0
    private var overlayTexCoordHandle = 0
    private var overlayTextureHandle = 0
    private var overlayTextureId = 0

    /** Where the uploaded overlay is drawn, or null when there is none. */
    private var overlayRect: Rect? = null

    /**
     * An overlay handed over by [setOverlay] and not uploaded yet. Uploading needs the context
     * current on a surface, which only [render] guarantees, so the swap happens there.
     */
    private var pendingOverlay: PendingOverlay? = null

    private class PendingOverlay(val bitmap: Bitmap?, val rect: Rect?)

    private val panes = LinkedHashMap<Int, Pane>()
    private var outputWidth = 0
    private var outputHeight = 0
    private var renderScheduled = false
    private var highlightedPaneId: Int? = null
    private var dividerBand: Rect? = null

    private val texCoordBuffer: FloatBuffer = floatBuffer(TEX_COORDS)
    private val overlayTexCoordBuffer: FloatBuffer = floatBuffer(OVERLAY_TEX_COORDS)
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

    /** Rounds every pane's corners by [radiusPx] (0 = square), as in the design. */
    fun setCornerRadius(radiusPx: Int) {
        val threadHandler = handler ?: return
        threadHandler.post {
            cornerRadius = radiusPx.coerceAtLeast(0).toFloat()
            scheduleRender()
        }
    }

    /**
     * Draws [bitmap] over every pane at [rect] (surface pixels), or removes the overlay when either
     * is null. Takes ownership of [bitmap]: it is recycled once uploaded, or if it is replaced by a
     * newer one first. Used for the controls Duo Screen draws on the car surface itself.
     */
    fun setOverlay(bitmap: Bitmap?, rect: Rect?) {
        val threadHandler = handler
        if (threadHandler == null) {
            bitmap?.recycle()
            return
        }
        threadHandler.post {
            pendingOverlay?.bitmap?.recycle()
            pendingOverlay = PendingOverlay(bitmap, rect)
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
        pendingOverlay?.let { pending ->
            pendingOverlay = null
            applyOverlay(pending)
        }
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(BACKGROUND_COLOR[0], BACKGROUND_COLOR[1], BACKGROUND_COLOR[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // Blending is on for the whole frame: it is what anti-aliases the rounded pane corners.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
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

    /** Uploads (or clears) the overlay on the GL thread, with the context current. */
    private fun applyOverlay(pending: PendingOverlay) {
        val bitmap = pending.bitmap
        val rect = pending.rect
        if (bitmap == null || rect == null || overlayProgram == 0) {
            overlayRect = null
            bitmap?.recycle()
            return
        }
        if (overlayTextureId == 0) overlayTextureId = createOverlayTexture()
        if (overlayTextureId == 0) {
            overlayRect = null
            bitmap.recycle()
            return
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        runCatching { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0) }
            .onSuccess { overlayRect = rect }
            .onFailure {
                overlayRect = null
                Log.w(TAG, "Overlay upload failed", it)
            }
        bitmap.recycle()
    }

    /**
     * The controls, above every pane. The painted Bitmap is premultiplied, hence ONE /
     * ONE_MINUS_SRC_ALPHA rather than SRC_ALPHA.
     */
    private fun drawOverlay() {
        val rect = overlayRect ?: return
        if (overlayProgram == 0 || overlayTextureId == 0) return
        if (rect.width <= 0 || rect.height <= 0) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(overlayProgram)
        writeVertices(rect)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
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
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(overlayPositionHandle)
        GLES20.glDisableVertexAttribArray(overlayTexCoordHandle)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
    }

    private fun createOverlayTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        if (textureId == 0) return 0
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
        // Clipped to the pane's own rounded outline, so the frame follows its corners.
        setClip(solidClipHandle, solidRadiusHandle, rect, cornerRadius)
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
        setClip(solidClipHandle, solidRadiusHandle, rect, cornerRadius)
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
        setClip(solidClipHandle, solidRadiusHandle, band, 0f)
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

    /** Sets a program's rounded-rect clip to [rect] (top-left origin) in window coordinates. */
    private fun setClip(clip: Int, radius: Int, rect: Rect, radiusPx: Float) {
        GLES20.glUniform4f(
            clip,
            rect.left.toFloat(),
            (outputHeight - rect.bottom).toFloat(),
            rect.right.toFloat(),
            (outputHeight - rect.top).toFloat()
        )
        GLES20.glUniform1f(radius, radiusPx.coerceAtMost(minOf(rect.width, rect.height) / 2f))
    }

    private fun drawPane(pane: Pane) {
        writeVertices(pane.rect)
        setClip(clipHandle, radiusHandle, pane.rect, cornerRadius)
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
        clipHandle = GLES20.glGetUniformLocation(program, "uClip")
        radiusHandle = GLES20.glGetUniformLocation(program, "uRadius")

        solidProgram = linkProgram(SOLID_VERTEX_SHADER, SOLID_FRAGMENT_SHADER)
        if (solidProgram != 0) {
            solidPositionHandle = GLES20.glGetAttribLocation(solidProgram, "aPosition")
            solidColorHandle = GLES20.glGetUniformLocation(solidProgram, "uColor")
            solidClipHandle = GLES20.glGetUniformLocation(solidProgram, "uClip")
            solidRadiusHandle = GLES20.glGetUniformLocation(solidProgram, "uRadius")
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
            // The panes still composite; only the on-surface controls are missing.
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
        if (overlayTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(overlayTextureId), 0)
            overlayTextureId = 0
        }
        overlayRect = null
        pendingOverlay?.bitmap?.recycle()
        pendingOverlay = null
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
