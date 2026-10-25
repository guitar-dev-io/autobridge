package dev.autobridge.ui.home

import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperLook
import dev.autobridge.design.WallpaperTarget

/**
 * Which wallpaper the phone Home draws behind itself, decided from Settings > Appearance >
 * Wallpaper. Pure so the fallbacks are unit-tested; the shared wallpaper painter draws the result.
 */
object HomeWallpaper {

    /**
     * The wallpaper for the phone on a [light] or dark theme: [Wallpaper.NONE] when the user aimed
     * it at the car only, and when "Your photo" is chosen but [customExists] says the picked photo
     * is gone.
     */
    fun forPhone(spec: AppearanceSpec, customExists: Boolean, light: Boolean = false): Wallpaper = when {
        spec.wallpaperTarget == WallpaperTarget.CAR -> Wallpaper.NONE
        spec.wallpaperLook(light).wallpaper == Wallpaper.CUSTOM && !customExists -> Wallpaper.NONE
        else -> spec.wallpaperLook(light).wallpaper
    }

    /** The whole look the phone draws: [forPhone]'s wallpaper with that theme's dim, blur, angle and crop. */
    fun lookForPhone(spec: AppearanceSpec, customExists: Boolean, light: Boolean): WallpaperLook =
        spec.wallpaperLook(light).copy(wallpaper = forPhone(spec, customExists, light))
}
