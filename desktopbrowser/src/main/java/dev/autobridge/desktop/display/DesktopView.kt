package dev.autobridge.desktop.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * The desktop browser on the phone: an ordinary hardware-accelerated View that draws the latest
 * frame in [onDraw].
 *
 * Not a SurfaceView with `lockHardwareCanvas()`: on HyperOS 3 / MediaTek / Android 16 that path
 * aborts the process inside HWUI ("drawRenderNode called on a context with no surface!") — it
 * happened on the test phone, and CarSurfaceCanvas records the same failure for car surfaces. A
 * View is drawn by the window's own renderer, which has no such failure mode.
 */
class DesktopView(context: Context) : View(context) {
    var renderer: FrameRenderer? = null

    private val lock = Any()
    private var frame: Bitmap? = null
    private var src = Rect()
    private var dst: RectF? = null
    private var message: String? = null

    /** Called on the render thread with a bitmap the view now owns. */
    fun present(bitmap: Bitmap?, src: Rect, dst: RectF?, message: String?) {
        synchronized(lock) {
            if (bitmap != null) frame = bitmap
            this.src = src
            this.dst = dst
            this.message = message
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val r = renderer ?: return
        synchronized(lock) {
            r.paintFrame(canvas, frame?.takeIf { message == null }, src, dst, message, width, height)
        }
    }
}
