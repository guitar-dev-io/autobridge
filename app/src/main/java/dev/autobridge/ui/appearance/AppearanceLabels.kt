package dev.autobridge.ui.appearance

import android.content.Context
import dev.autobridge.R
import dev.autobridge.design.AccentColor
import dev.autobridge.design.CardStyle
import dev.autobridge.design.Corners
import dev.autobridge.design.FontChoice
import dev.autobridge.design.HomeLayout
import dev.autobridge.design.IconStyle
import dev.autobridge.design.TextStep
import dev.autobridge.design.ThaiStyle
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemePreset
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperTarget
import dev.autobridge.ui.appearance.AppearanceModel.TextStyle

/** The user-facing name of every Appearance choice. */
object AppearanceLabels {

    fun modeShort(context: Context, mode: ThemeMode): String = context.getString(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.appearance_mode_short_system
            ThemeMode.LIGHT -> R.string.appearance_mode_short_light
            ThemeMode.DARK -> R.string.appearance_mode_short_dark
            ThemeMode.AMOLED -> R.string.appearance_mode_short_amoled
            ThemeMode.AUTO -> R.string.appearance_mode_short_auto
        }
    )

    fun mode(context: Context, mode: ThemeMode): String = context.getString(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.appearance_mode_system
            ThemeMode.LIGHT -> R.string.appearance_mode_light
            ThemeMode.DARK -> R.string.appearance_mode_dark
            ThemeMode.AMOLED -> R.string.appearance_mode_amoled
            ThemeMode.AUTO -> R.string.appearance_mode_auto
        }
    )

    /** [systemDark] is Android's night mode, named in the System row's description. */
    fun modeDescription(context: Context, mode: ThemeMode, systemDark: Boolean): String = context.getString(
        when (mode) {
            ThemeMode.SYSTEM -> if (systemDark) R.string.appearance_mode_system_desc_dark else R.string.appearance_mode_system_desc_light
            ThemeMode.LIGHT -> R.string.appearance_mode_light_desc
            ThemeMode.DARK -> R.string.appearance_mode_dark_desc
            ThemeMode.AMOLED -> R.string.appearance_mode_amoled_desc
            ThemeMode.AUTO -> R.string.appearance_mode_auto_desc
        }
    )

    fun modeGlyph(mode: ThemeMode): String = when (mode) {
        ThemeMode.SYSTEM -> Glyphs.PHONE
        ThemeMode.LIGHT -> Glyphs.SUN
        ThemeMode.DARK -> Glyphs.MOON
        ThemeMode.AMOLED -> Glyphs.AMOLED
        ThemeMode.AUTO -> Glyphs.CLOCK
    }

    fun preset(context: Context, preset: ThemePreset): String = context.getString(
        when (preset) {
            ThemePreset.AUTOBRIDGE -> R.string.appearance_preset_autobridge
            ThemePreset.GRAPHITE -> R.string.appearance_preset_graphite
            ThemePreset.OCEAN -> R.string.appearance_preset_ocean
            ThemePreset.EMBER -> R.string.appearance_preset_ember
            ThemePreset.DUSK -> R.string.appearance_preset_dusk
        }
    )

    fun accent(context: Context, accent: AccentColor): String = context.getString(
        when (accent) {
            AccentColor.SKY -> R.string.appearance_accent_sky
            AccentColor.TEAL -> R.string.appearance_accent_teal
            AccentColor.AMBER -> R.string.appearance_accent_amber
            AccentColor.VIOLET -> R.string.appearance_accent_violet
            AccentColor.ROSE -> R.string.appearance_accent_rose
            AccentColor.LIME -> R.string.appearance_accent_lime
        }
    )

    /** Font family names are the typefaces' own names; only the system one is described. */
    fun font(context: Context, font: FontChoice): String = when (font) {
        FontChoice.INTER -> "Inter"
        FontChoice.MANROPE -> "Manrope"
        FontChoice.IBM_PLEX -> "IBM Plex Sans"
        FontChoice.NOTO -> "Noto Sans"
        FontChoice.SYSTEM -> context.getString(R.string.appearance_font_system)
    }

    /** The short name in the preview's summary line. */
    fun fontShort(font: FontChoice): String = when (font) {
        FontChoice.INTER -> "Inter"
        FontChoice.MANROPE -> "Manrope"
        FontChoice.IBM_PLEX -> "Plex"
        FontChoice.NOTO -> "Noto"
        FontChoice.SYSTEM -> "Roboto"
    }

    fun fontDescription(context: Context, font: FontChoice): String = context.getString(
        when (font) {
            FontChoice.INTER -> R.string.appearance_font_inter_desc
            FontChoice.MANROPE -> R.string.appearance_font_manrope_desc
            FontChoice.IBM_PLEX -> R.string.appearance_font_plex_desc
            FontChoice.NOTO -> R.string.appearance_font_noto_desc
            FontChoice.SYSTEM -> R.string.appearance_font_system_desc
        }
    )

    fun thai(context: Context, style: ThaiStyle): String = context.getString(
        when (style) {
            ThaiStyle.SYSTEM -> R.string.appearance_thai_system
            ThaiStyle.LOOPED -> R.string.appearance_thai_looped
            ThaiStyle.LOOPLESS -> R.string.appearance_thai_loopless
        }
    )

    fun thaiDescription(context: Context, style: ThaiStyle): String = context.getString(
        when (style) {
            ThaiStyle.SYSTEM -> R.string.appearance_thai_system_desc
            ThaiStyle.LOOPED -> R.string.appearance_thai_looped_desc
            ThaiStyle.LOOPLESS -> R.string.appearance_thai_loopless_desc
        }
    )

    fun step(context: Context, step: TextStep): String = context.getString(
        when (step) {
            TextStep.SMALL -> R.string.appearance_text_small
            TextStep.DEFAULT -> R.string.appearance_text_default
            TextStep.LARGE -> R.string.appearance_text_large
            TextStep.LARGER -> R.string.appearance_text_larger
            TextStep.HUGE -> R.string.appearance_text_huge
            TextStep.MAX -> R.string.appearance_text_max
        }
    )

    fun layout(context: Context, layout: HomeLayout): String = context.getString(
        when (layout) {
            HomeLayout.CLASSIC -> R.string.appearance_home_classic
            HomeLayout.DASHBOARD -> R.string.appearance_home_dashboard
            HomeLayout.COMPACT -> R.string.appearance_home_compact
        }
    )

    fun wallpaper(context: Context, wallpaper: Wallpaper): String = context.getString(
        when (wallpaper) {
            Wallpaper.NONE -> R.string.appearance_wallpaper_none
            Wallpaper.GLOW -> R.string.appearance_wallpaper_glow
            Wallpaper.LINES -> R.string.appearance_wallpaper_lines
            Wallpaper.NIGHT_ROAD -> R.string.appearance_wallpaper_night_road
            Wallpaper.SOLID -> R.string.appearance_wallpaper_solid
            Wallpaper.CUSTOM -> R.string.appearance_wallpaper_custom
        }
    )

    fun target(context: Context, target: WallpaperTarget): String = context.getString(
        when (target) {
            WallpaperTarget.PHONE -> R.string.appearance_target_phone
            WallpaperTarget.CAR -> R.string.appearance_target_car
            WallpaperTarget.BOTH -> R.string.appearance_target_both
        }
    )

    fun iconStyle(context: Context, style: IconStyle): String = context.getString(
        when (style) {
            IconStyle.TINTED -> R.string.appearance_icon_tinted
            IconStyle.ACCENT -> R.string.appearance_icon_accent
            IconStyle.MONO -> R.string.appearance_icon_mono
        }
    )

    fun cardStyle(context: Context, style: CardStyle): String = context.getString(
        when (style) {
            CardStyle.ELEVATED -> R.string.appearance_card_elevated
            CardStyle.OUTLINED -> R.string.appearance_card_outlined
            CardStyle.GLASS -> R.string.appearance_card_glass
        }
    )

    fun corners(context: Context, corners: Corners): String = context.getString(
        when (corners) {
            Corners.SOFT -> R.string.appearance_corners_soft
            Corners.STANDARD -> R.string.appearance_corners_standard
            Corners.SHARP -> R.string.appearance_corners_sharp
        }
    )

    fun textStyle(context: Context, style: TextStyle): String = context.getString(
        when (style) {
            TextStyle.DISPLAY -> R.string.appearance_sizes_display
            TextStyle.TITLE -> R.string.appearance_sizes_title
            TextStyle.HEADING -> R.string.appearance_sizes_heading
            TextStyle.BODY -> R.string.appearance_sizes_body
            TextStyle.CAPTION -> R.string.appearance_sizes_caption
            TextStyle.OVERLINE -> R.string.appearance_sizes_overline
        }
    )
}
