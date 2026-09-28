package dev.autobridge.browser

import android.graphics.Color

/**
 * Single source of truth for browser colors, shared by the phone [BrowserActivity] (native Views)
 * and the car [CarWebRenderer] (Canvas-drawn chrome), so both presentations read as one app the way
 * a Fermata-style browser keeps its toolbar/menu look consistent across screens.
 *
 * Sizing deliberately lives in [AutoUiSizes], not here. This object used to also expose a
 * `chromeScale(shortestWidthDp)` that grew icons with the screen's *dimension*, which is what made
 * a wide head unit render toolbar glyphs at tablet size.
 */
object BrowserTheme {
    val background = Color.rgb(20, 22, 26)
    val toolbarBackground = Color.rgb(20, 22, 26)
    val addressPillBackground = Color.rgb(38, 41, 48)
    val iconEnabled = Color.WHITE
    val iconDisabled = Color.rgb(100, 108, 120)
    val textPrimary = Color.WHITE
    val textSecondary = Color.rgb(200, 206, 214)
    val accent = Color.rgb(32, 156, 255)
    val secureBadge = Color.rgb(120, 200, 130)
    val insecureBadge = Color.rgb(230, 170, 90)
    val errorBackground = Color.rgb(28, 20, 20)
    val errorAccent = Color.rgb(230, 100, 90)

    /** Drawer panel and the dimming layer it sits on. */
    val drawerBackground = Color.rgb(16, 18, 22)
    val scrim = Color.argb(140, 0, 0, 0)
}
