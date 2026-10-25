package dev.autobridge.design

/**
 * [AppearanceSpec] to and from a flat string map, the shape SharedPreferences stores.
 *
 * Enums are stored by name. A missing key or a name this build does not know (a value written by a
 * newer version, or one since removed) falls back to that field's default instead of failing, so a
 * downgrade never wipes the rest of the user's choices.
 */
object AppearanceCodec {
    fun encode(spec: AppearanceSpec): Map<String, String> = mapOf(
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
        "blur" to spec.blurBehindCards.toString(),
        "icon_style" to spec.iconStyle.name,
        "card_style" to spec.cardStyle.name,
        "corners" to spec.corners.name,
        "tile_labels" to spec.showTileLabels.toString(),
        "car_same_look" to spec.carSameLook.toString(),
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
            blurBehindCards = map["blur"]?.toBooleanStrictOrNull() ?: d.blurBehindCards,
            iconStyle = enumOr(map["icon_style"], d.iconStyle),
            cardStyle = enumOr(map["card_style"], d.cardStyle),
            corners = enumOr(map["corners"], d.corners),
            showTileLabels = map["tile_labels"]?.toBooleanStrictOrNull() ?: d.showTileLabels,
            carSameLook = map["car_same_look"]?.toBooleanStrictOrNull() ?: d.carSameLook,
            autoDarkFrom = minuteOr(map["auto_dark_from"], d.autoDarkFrom),
            autoDarkTo = minuteOr(map["auto_dark_to"], d.autoDarkTo)
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private fun minuteOr(raw: String?, fallback: Int): Int =
        raw?.toIntOrNull()?.takeIf { it in 0 until 24 * 60 } ?: fallback
}
