package dev.autobridge.settings

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.WindowCompat
import dev.autobridge.design.ThemeHolder
import dev.autobridge.mycar.MyCarFacts
import dev.autobridge.ui.AutoBridgeDesign
import dev.autobridge.ui.AppFonts

/**
 * The parts of Settings > Appearance a phone screen applies to its own window, for the screens
 * that open from My Car, Mirror and Dual Display (the tools). The page itself paints from
 * [ThemeHolder.palette] through `AutoBridgeDesign`; what is left is the window around it.
 *
 * Call [applyWindow] from `onCreate`, after `AppearanceStore.refreshPalette`.
 */
object ScreenAppearance {

    /**
     * Matches the window to the palette: its background (so a light page does not open over the
     * dark AppTheme window for a frame) and the system bar icons, dark on a light page. Below
     * Android 15 the bars are not drawn edge to edge, so they also take the page colour.
     */
    @Suppress("DEPRECATION")
    fun applyWindow(activity: Activity) {
        val palette = ThemeHolder.palette
        val window = activity.window
        window.decorView.setBackgroundColor(palette.background)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = palette.background
            window.navigationBarColor = palette.background
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.isLight
            isAppearanceLightNavigationBars = palette.isLight
        }
    }

    /**
     * The alert-dialog theme that matches the page, for `AlertDialog.Builder(context, it)`: the app
     * theme is dark, so without this a light page opens dark dialogs.
     */
    fun dialogTheme(): Int =
        if (ThemeHolder.palette.isLight) dev.autobridge.R.style.ToolDialogLight
        else dev.autobridge.R.style.ToolDialogDark

    /**
     * A section accent (`AutoBridgeDesign.ACCENT_*`, tuned for the dark page) made readable as text
     * or an icon on the current page: unchanged on a dark page, darkened on a light one, where the
     * pastel accents would wash out on white.
     */
    fun readable(color: Int): Int {
        if (!ThemeHolder.palette.isLight) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(1f)
        hsv[2] = hsv[2] * 0.55f
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    /** The status role for a My Car standing: on track is good, soon is warn, overdue is danger. */
    fun tone(tone: MyCarFacts.Tone): Int = when (tone) {
        MyCarFacts.Tone.ALERT -> ThemeHolder.palette.danger
        MyCarFacts.Tone.WARN -> ThemeHolder.palette.warn
        MyCarFacts.Tone.NORMAL -> ThemeHolder.palette.good
        MyCarFacts.Tone.EMPTY -> ThemeHolder.palette.textSecondary
    }

    /** What a [primaryButton] can be doing (design system, "Components & states"). */
    enum class ButtonState { ENABLED, DISABLED, LOADING }

    /**
     * A page's one main action, full width: the accent fill with its text on the accent's own ink,
     * a lighter fill while pressed (the ripple), the raised fill with quiet text when it cannot be
     * used, and a spinner beside the label while its work runs. 52 dp tall.
     */
    fun primaryButton(context: Context, label: String, state: ButtonState = ButtonState.ENABLED, onClick: () -> Unit): View {
        val palette = ThemeHolder.palette
        val enabled = state == ButtonState.ENABLED
        val fill = if (enabled) palette.accent else palette.raised
        val ink = when (state) {
            ButtonState.ENABLED -> palette.onAccent
            ButtonState.LOADING -> palette.textSecondary
            ButtonState.DISABLED -> palette.textTertiary
        }
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(52)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = if (enabled) {
                AutoBridgeDesign.tappable(context, fill, 16, palette.onAccent, Color.TRANSPARENT)
            } else {
                AutoBridgeDesign.surface(context, fill, 16, Color.TRANSPARENT)
            }
            if (state == ButtonState.LOADING) {
                addView(ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(palette.accent)
                }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
            }
            addView(TextView(context).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                typeface = AppFonts.bold()
                setTextColor(ink)
            })
            contentDescription = label
            isEnabled = enabled
            isClickable = enabled
            isFocusable = true
            if (enabled) setOnClickListener { onClick() }
        }
    }
}
