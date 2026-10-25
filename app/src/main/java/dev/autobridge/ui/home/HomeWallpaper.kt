package dev.autobridge.ui.home

import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperTarget

/**
 * Which wallpaper the phone Home draws behind itself, decided from Settings > Appearance >
 * Wallpaper. Pure so the fallbacks are unit-tested; [WallpaperDrawable] paints the result.
 */
object HomeWallpaper {

    /**
     * The wallpaper for the phone: [Wallpaper.NONE] when the user aimed it at the car only, and
     * when "Your photo" is chosen but [customExists] says the picked photo is gone.
     */
    fun forPhone(spec: AppearanceSpec, customExists: Boolean): Wallpaper = when {
        spec.wallpaperTarget == WallpaperTarget.CAR -> Wallpaper.NONE
        spec.wallpaper == Wallpaper.CUSTOM && !customExists -> Wallpaper.NONE
        else -> spec.wallpaper
    }

    /**
     * The dim laid over the wallpaper as ARGB: black over a dark theme, white over a light one,
     * [dim] percent opaque (clamped to 0..[AppearanceSpec.MAX_DIM]). No wallpaper, no dim.
     */
    fun dimColor(wallpaper: Wallpaper, dim: Int, light: Boolean): Int {
        if (wallpaper == Wallpaper.NONE) return 0
        val alpha = Math.round(dim.coerceIn(0, AppearanceSpec.MAX_DIM) * 255 / 100f)
        return (alpha shl 24) or (if (light) 0xFFFFFF else 0x000000)
    }
}
