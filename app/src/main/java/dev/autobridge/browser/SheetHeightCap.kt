package dev.autobridge.browser

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.WindowInsetsCompat

/**
 * Shared height ceiling for the phone bottom sheets. The cap is applied while measuring (never
 * pinned into layoutParams), so a sheet shrinks back by itself when content or orientation change.
 */
object SheetHeightCap {
    /** Sheets never exceed this share of the screen height. */
    const val MAX_FRACTION = 0.78f

    /** Minimum clear gap kept below the status bar so the handle stays out of the shade swipe zone. */
    const val MIN_TOP_GAP_DP = 72

    private const val MIN_HEIGHT_DP = 240

    fun maxHeightPx(view: View): Int {
        val res = view.resources
        val density = res.displayMetrics.density
        val usable = res.displayMetrics.heightPixels
        val insetTop = view.rootWindowInsets
            ?.let { WindowInsetsCompat.toWindowInsetsCompat(it, view) }
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top
        val statusInset = insetTop ?: statusBarHeightFallback(view.context)
        val byFraction = (usable * MAX_FRACTION).toInt()
        val byGap = usable - statusInset - (MIN_TOP_GAP_DP * density).toInt()
        return minOf(byFraction, byGap).coerceAtLeast((MIN_HEIGHT_DP * density).toInt())
    }

    private fun statusBarHeightFallback(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    /** Clamp a height measure spec so the view can never be taller than the cap. */
    fun clamp(view: View, heightSpec: Int): Int {
        val max = maxHeightPx(view)
        val size = View.MeasureSpec.getSize(heightSpec)
        return when (View.MeasureSpec.getMode(heightSpec)) {
            View.MeasureSpec.UNSPECIFIED ->
                View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST)
            else ->
                View.MeasureSpec.makeMeasureSpec(minOf(size, max), View.MeasureSpec.AT_MOST)
        }
    }
}

/** ScrollView whose height is capped; its content scrolls once it exceeds the cap. */
class CappedScrollView(context: Context) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, SheetHeightCap.clamp(this, heightMeasureSpec))
    }
}

/** LinearLayout whose height is capped; weighted children absorb the overflow. */
class CappedLinearLayout(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, SheetHeightCap.clamp(this, heightMeasureSpec))
    }
}
