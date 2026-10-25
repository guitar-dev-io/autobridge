package dev.autobridge.design

/**
 * [AppearanceSpec] to and from a flat string map, the shape SharedPreferences stores.
 *
 * Enums are stored by name. A missing key or a name this build does not know (a value written by a
 * newer version, or one since removed) falls back to that field's default instead of failing, so a
 * downgrade never wipes the rest of the user's choices.
 */
object AppearanceCodec {
    fun encode(spec: AppearanceSpec): Map<String, String> = encodeBase(spec) + (spec.lightWallpaper?.let(::encodeLight) ?: emptyMap())

    private fun encodeLight(look: WallpaperLook): Map<String, String> = mapOf(
        "lw_wallpaper" to look.wallpaper.name,
        "lw_dim" to look.dim.toString(),
        "lw_blur" to look.blur.toString(),
        "lw_angle" to look.angle.toString(),
        "lw_phone_crop" to cropOf(look.phoneCrop),
        "lw_car_crop" to cropOf(look.carCrop)
    )

    private fun cropOf(crop: WallpaperCrop) = "${crop.zoom},${crop.x},${crop.y}"

    private fun encodeBase(spec: AppearanceSpec): Map<String, String> = mapOf(
        "mode" to spec.mode.name,
        "preset" to spec.preset.name,
        "accent" to spec.accent.name,
        "font" to spec.font.name,
        "thai" to spec.thai.name,
        "text_step" to spec.textStep.name,
        "follow_system_text" to spec.followSystemText.toString(),
        "home_layout" to spec.homeLayout.name,
        "wallpaper" to spec.wallpaper.name,
        "wallpaper_target" to spec.wallpaperTarget.name,
        "wallpaper_dim" to spec.wallpaperDim.toString(),
        "wallpaper_blur" to spec.wallpaperBlur.toString(),
        "wallpaper_angle" to spec.wallpaperAngle.toString(),
        "phone_crop" to cropOf(spec.phoneCrop),
        "car_crop" to cropOf(spec.carCrop),
        "blur" to spec.blurBehindCards.toString(),
        "icon_style" to spec.iconStyle.name,
        "card_style" to spec.cardStyle.name,
        "corners" to spec.corners.name,
        "tile_labels" to spec.showTileLabels.toString(),
        "car_same_look" to spec.carSameLook.toString(),
        "car_system_status" to spec.carSystemStatus.toString(),
        "auto_dark_from" to spec.autoDarkFrom.toString(),
        "auto_dark_to" to spec.autoDarkTo.toString()
    )

    fun decode(map: Map<String, String?>): AppearanceSpec {
        val d = AppearanceSpec.DEFAULT
        return AppearanceSpec(
            mode = enumOr(map["mode"], d.mode),
            preset = enumOr(map["preset"], d.preset),
            accent = enumOr(map["accent"], d.accent),
            font = enumOr(map["font"], d.font),
            thai = enumOr(map["thai"], d.thai),
            textStep = enumOr(map["text_step"], d.textStep),
            followSystemText = map["follow_system_text"]?.toBooleanStrictOrNull() ?: d.followSystemText,
            homeLayout = enumOr(map["home_layout"], d.homeLayout),
            wallpaper = enumOr(map["wallpaper"], d.wallpaper),
            wallpaperTarget = enumOr(map["wallpaper_target"], d.wallpaperTarget),
            wallpaperDim = map["wallpaper_dim"]?.toIntOrNull()?.coerceIn(0, AppearanceSpec.MAX_DIM) ?: d.wallpaperDim,
            wallpaperBlur = blurOr(map["wallpaper_blur"], d.wallpaperBlur),
            wallpaperAngle = angleOr(map["wallpaper_angle"], d.wallpaperAngle),
            phoneCrop = cropOr(map["phone_crop"], d.phoneCrop),
            carCrop = cropOr(map["car_crop"], d.carCrop),
            lightWallpaper = decodeLight(map),
            blurBehindCards = map["blur"]?.toBooleanStrictOrNull() ?: d.blurBehindCards,
            iconStyle = enumOr(map["icon_style"], d.iconStyle),
            cardStyle = enumOr(map["card_style"], d.cardStyle),
            corners = enumOr(map["corners"], d.corners),
            showTileLabels = map["tile_labels"]?.toBooleanStrictOrNull() ?: d.showTileLabels,
            carSameLook = map["car_same_look"]?.toBooleanStrictOrNull() ?: d.carSameLook,
            carSystemStatus = map["car_system_status"]?.toBooleanStrictOrNull() ?: d.carSystemStatus,
            autoDarkFrom = minuteOr(map["auto_dark_from"], d.autoDarkFrom),
            autoDarkTo = minuteOr(map["auto_dark_to"], d.autoDarkTo)
        )
    }

    /** The light wallpaper, present only when a light wallpaper name was stored. */
    private fun decodeLight(map: Map<String, String?>): WallpaperLook? {
        val name = map["lw_wallpaper"] ?: return null
        val d = AppearanceSpec.DEFAULT.darkLook
        return WallpaperLook(
            wallpaper = enumValues<Wallpaper>().firstOrNull { it.name == name } ?: return null,
            dim = map["lw_dim"]?.toIntOrNull()?.coerceIn(0, AppearanceSpec.MAX_DIM) ?: d.dim,
            blur = blurOr(map["lw_blur"], d.blur),
            angle = angleOr(map["lw_angle"], d.angle),
            phoneCrop = cropOr(map["lw_phone_crop"], d.phoneCrop),
            carCrop = cropOr(map["lw_car_crop"], d.carCrop)
        )
    }

    private fun blurOr(raw: String?, fallback: Int): Int = raw?.toIntOrNull()?.coerceIn(0, WallpaperCatalog.MAX_BLUR) ?: fallback

    private fun angleOr(raw: String?, fallback: Int): Int = raw?.toIntOrNull()?.coerceIn(0, 360) ?: fallback

    /** "zoom,x,y"; anything else, or a missing value, is the whole image. */
    private fun cropOr(raw: String?, fallback: WallpaperCrop): WallpaperCrop {
        val parts = raw?.split(',')?.map { it.trim().toIntOrNull() ?: return fallback } ?: return fallback
        if (parts.size != 3) return fallback
        return WallpaperCrop(parts[0], parts[1], parts[2]).clamped()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private fun minuteOr(raw: String?, fallback: Int): Int =
        raw?.toIntOrNull()?.takeIf { it in 0 until 24 * 60 } ?: fallback
}
