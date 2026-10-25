package dev.autobridge.ui.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.autobridge.R
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.ThemeMode
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.Wallpaper
import dev.autobridge.design.WallpaperCatalog
import dev.autobridge.design.WallpaperCrop
import dev.autobridge.design.WallpaperKind
import dev.autobridge.design.WallpaperLook
import dev.autobridge.design.WallpaperTarget
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.appearance.AppearanceUi.p
import kotlin.math.roundToInt

/**
 * What the Wallpaper page remembers while it is open and the spec has not changed: the tab being
 * browsed, which theme is being edited when the wallpapers are separate, and which screen's crop
 * is showing. It is the page's own state, so it never reaches the stored [AppearanceSpec].
 */
internal class WallpaperUiState {
    var kind: WallpaperKind? = null
    var editLight: Boolean? = null
    var cropCar: Boolean = false
}

/**
 * Settings > Appearance > Wallpaper: pick a plain colour, a gradient, one of the bundled images or
 * your own photo; place and blur it; dim it; give Light and Dark their own. Wallpaper is drawn on
 * AutoBridge's own screens only (the phone Home and the car home), and the page says so; it has no
 * way to touch the Android Auto launcher or the phone's system wallpaper.
 */
internal object WallpaperPage {

    private const val TILE_PHOTO_COLUMNS = 2

    /** The palettes the live preview should draw with for what this page is editing. */
    fun previewPalettes(spec: AppearanceSpec, ui: WallpaperUiState, systemDark: Boolean): Pair<ThemePalette, ThemePalette> {
        val light = AppearanceModel.editedLight(spec, p.isLight, ui.editLight)
        return themed(spec, light, systemDark)
    }

    /** "Dark · Midnight road": what the preview line says. */
    fun previewSummary(context: Context, spec: AppearanceSpec, ui: WallpaperUiState): String {
        val light = AppearanceModel.editedLight(spec, p.isLight, ui.editLight)
        return context.getString(
            R.string.appearance_wallpaper_preview_summary,
            context.getString(if (light) R.string.appearance_wallpaper_light else R.string.appearance_wallpaper_dark),
            AppearanceLabels.wallpaper(context, spec.wallpaperLook(light).wallpaper)
        )
    }

    /** The phone and car palettes of a light or dark theme, with the user's preset and accent. */
    private fun themed(spec: AppearanceSpec, light: Boolean, systemDark: Boolean): Pair<ThemePalette, ThemePalette> {
        if (light == p.isLight) return p to dev.autobridge.design.ThemeHolder.carPalette
        val mode = when {
            light -> ThemeMode.LIGHT
            spec.mode == ThemeMode.AMOLED -> ThemeMode.AMOLED
            else -> ThemeMode.DARK
        }
        val pinned = spec.copy(mode = mode)
        return ThemeResolver.resolve(pinned, systemDark) to ThemeResolver.resolveCar(pinned, systemDark)
    }

    private fun LinearLayout.add(view: View, topDp: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(topDp) })
    }

    fun build(context: Context, body: LinearLayout, actions: AppearanceActions) {
        val spec = actions.spec
        val ui = actions.wallpaperUi
        val editLight = AppearanceModel.editedLight(spec, p.isLight, ui.editLight)
        val look = spec.wallpaperLook(editLight)
        val (pal, _) = themed(spec, editLight, actions.systemDark)
        val density = context.resources.displayMetrics.density
        val photoPreview = WallpaperPhoto.preview(context)
        val kind = ui.kind ?: look.wallpaper.kind

        /** Writes a change to the look of the theme being edited. */
        fun edit(transform: (WallpaperLook) -> WallpaperLook) =
            actions.change { it.withWallpaperLook(editLight, transform(it.wallpaperLook(editLight))) }

        if (!spec.carSameLook) body.add(carNote(context, actions), topDp = 12)
        body.add(scope(context), topDp = 12)

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_show_on)))
        body.add(AppearanceUi.segmented(
            context, WallpaperTarget.entries.map { it to AppearanceLabels.target(context, it) }, spec.wallpaperTarget
        ) { target -> actions.change { it.copy(wallpaperTarget = target) } })

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_wallpaper_split_title)))
        body.add(themeCard(context, actions, spec, editLight, density, photoPreview))

        val editName = context.getString(if (editLight) R.string.appearance_wallpaper_light else R.string.appearance_wallpaper_dark)
        body.add(AppearanceUi.label(context, context.getString(
            R.string.appearance_wallpaper_choose_for,
            if (spec.separateWallpapers) editName else context.getString(R.string.appearance_wallpaper_all_themes, editName)
        )))
        body.add(AppearanceUi.segmented(
            context, WallpaperKind.entries.map { it to AppearanceLabels.wallpaperKind(context, it) }, kind
        ) { picked -> ui.kind = picked; actions.refresh() })

        when (kind) {
            WallpaperKind.PHOTO -> body.add(photoTab(context, actions, look, pal, density, photoPreview) { w -> edit { it.copy(wallpaper = w) } }, topDp = 12)
            else -> body.add(gallery(context, kind, look, pal, density) { w ->
                edit { it.copy(wallpaper = w, angle = if (WallpaperCatalog.turns(w)) WallpaperCatalog.defaultAngle(w) else it.angle) }
            }, topDp = 12)
        }
        if (kind == WallpaperKind.GRADIENT) body.add(angleCard(context, look) { angle -> edit { it.copy(angle = angle) } }, topDp = 12)
        if (kind == WallpaperKind.IMAGE) body.add(
            AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_images_note), 13f, p.textTertiary).apply {
                setLineSpacing(0f, 1.3f)
                setPadding(context.dp(4), 0, context.dp(4), 0)
            }, topDp = 10
        )

        if (WallpaperCatalog.crops(look.wallpaper) && (look.wallpaper != Wallpaper.CUSTOM || photoPreview != null)) {
            body.add(AppearanceUi.label(context, context.getString(R.string.appearance_wallpaper_crop_title)))
            body.add(cropCard(context, actions, spec, look, pal, photoPreview) { device, crop ->
                edit { if (device == WallDevice.CAR) it.copy(carCrop = crop) else it.copy(phoneCrop = crop) }
            })
        }

        body.add(AppearanceUi.label(context, context.getString(R.string.appearance_wallpaper_effects)))
        body.add(effectsCard(context, actions, spec, look, editLight, pal, photoPreview,
            onBlur = { blur -> edit { it.copy(blur = blur) } }, onDim = { dim -> edit { it.copy(dim = dim) } }))

        body.add(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_reset), 15f, p.danger, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(48)
            background = ripple(p.hairline, context)
            AppearanceUi.makeTappable(this, text.toString()) { actions.askResetWallpaper() }
        }, topDp = 18)
    }

    private fun ripple(stroke: Int, context: Context) =
        AppearanceUi.ripple(AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.button, stroke))

    // ============================================================================ scope + notes

    private fun carNote(context: Context, actions: AppearanceActions): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = AppearanceUi.shape(context, p.raised, p.corners.button)
        setPadding(context.dp(14), context.dp(6), context.dp(6), context.dp(6))
        addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_car_note), 13f, p.textSecondary).apply {
            setLineSpacing(0f, 1.25f)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_car_note_action), 13f, p.accent, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(44)
            setPadding(context.dp(12), 0, context.dp(12), 0)
            AppearanceUi.makeTappable(this, text.toString()) { actions.change { it.copy(carSameLook = true) } }
        })
    }

    /** Where wallpaper shows, and the two places it never reaches. */
    private fun scope(context: Context): View {
        fun row(glyph: String, tint: Int, text: String): View = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(36)
            addView(AppearanceUi.glyph(context, glyph, tint, 18, 2.2f))
            addView(AppearanceUi.text(context, text, 14f, if (tint == p.accent) p.text else p.textSecondary),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = context.dp(10) })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppearanceUi.cardBackground(context)
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(12))
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_scope_title).uppercase(), 11f, p.textTertiary, bold = true)
                .apply { letterSpacing = 0.06f; setPadding(0, 0, 0, context.dp(4)) })
            addView(row(Glyphs.CHECK, p.accent, context.getString(R.string.appearance_wallpaper_scope_phone)))
            addView(row(Glyphs.CHECK, p.accent, context.getString(R.string.appearance_wallpaper_scope_car)))
            addView(AppearanceUi.hairline(context), LinearLayout.LayoutParams(-1, maxOf(1, context.dp(1))).apply { topMargin = context.dp(4); bottomMargin = context.dp(4) })
            addView(row(Glyphs.LOCK, p.textSecondary, context.getString(R.string.appearance_wallpaper_scope_launcher) + " · " + context.getString(R.string.appearance_wallpaper_scope_never).lowercase()))
            addView(row(Glyphs.LOCK, p.textSecondary, context.getString(R.string.appearance_wallpaper_scope_system) + " · " + context.getString(R.string.appearance_wallpaper_scope_never).lowercase()))
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_scope_note), 13f, p.textSecondary).apply {
                setLineSpacing(0f, 1.3f)
                setPadding(0, context.dp(6), 0, 0)
            })
        }
    }

    // ================================================================================ light/dark

    private fun themeCard(
        context: Context, actions: AppearanceActions, spec: AppearanceSpec, editLight: Boolean, density: Float, photo: Bitmap?
    ): View = AppearanceUi.paddedCard(context).apply {
        val shown = context.getString(if (p.isLight) R.string.appearance_wallpaper_light else R.string.appearance_wallpaper_dark)
        addView(AppearanceUi.toggleRow(
            context,
            context.getString(R.string.appearance_wallpaper_split_title),
            context.getString(R.string.appearance_wallpaper_split_caption, shown),
            spec.separateWallpapers
        ) {
            actions.wallpaperUi.editLight = p.isLight
            actions.change { it.withSeparateWallpapers(!it.separateWallpapers, editLight) }
        })
        if (!spec.separateWallpapers) {
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_split_off), 13f, p.textSecondary).apply {
                setLineSpacing(0f, 1.3f)
                setPadding(0, context.dp(8), 0, 0)
            })
            return@apply
        }
        addView(AppearanceUi.hairline(context), LinearLayout.LayoutParams(-1, maxOf(1, context.dp(1))).apply { topMargin = context.dp(8) })
        val tiles = listOf(true, false).map { light ->
            val look = spec.wallpaperLook(light)
            val on = light == editLight
            val (themePal, _) = themed(spec, light, actions.systemDark)
            val name = context.getString(if (light) R.string.appearance_wallpaper_light else R.string.appearance_wallpaper_dark)
            val state = context.getString(if (on) R.string.appearance_wallpaper_editing else R.string.appearance_wallpaper_tap_to_edit) +
                if (light == p.isLight) " · " + context.getString(R.string.appearance_wallpaper_showing_now) else ""
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                background = AppearanceUi.optionBackground(context, on)
                addView(FrameLayout(context).apply {
                    background = WallpaperDrawable(
                        look.copy(dim = 0, blur = 0), WallDevice.CAR, themePal, density, context.dp(10).toFloat(),
                        if (look.wallpaper == Wallpaper.CUSTOM) photo else null
                    )
                    addView(FrameLayout(context).apply {
                        background = AppearanceUi.oval(0x8C000000.toInt())
                        addView(GlyphView(context, if (light) Glyphs.SUN else Glyphs.MOON, Color.WHITE, 2f),
                            FrameLayout.LayoutParams(context.dp(14), context.dp(14), Gravity.CENTER))
                    }, FrameLayout.LayoutParams(context.dp(24), context.dp(24), Gravity.START or Gravity.TOP).apply {
                        setMargins(context.dp(6), context.dp(6), 0, 0)
                    })
                }, LinearLayout.LayoutParams(-1, context.dp(68)))
                addView(AppearanceUi.text(context, name + " · " + AppearanceLabels.wallpaper(context, look.wallpaper), 13f, if (on) p.accent else p.text, bold = true, lines = 1),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
                addView(AppearanceUi.text(context, state, 11f, p.textSecondary, lines = 1))
                AppearanceUi.makeTappable(this, "$name. $state", on) {
                    if (!on) { actions.wallpaperUi.editLight = light; actions.wallpaperUi.kind = null; actions.refresh() }
                }
            }
        }
        addView(AppearanceUi.grid(context, tiles, columns = 2, gapDp = 8), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
    }

    // =================================================================================== gallery

    /** One tile: the wallpaper itself in a rounded frame, an accent ring when chosen, and its name. */
    private fun tile(
        context: Context, wallpaper: Wallpaper, look: WallpaperLook, pal: ThemePalette, density: Float, heightDp: Int,
        photo: Bitmap?, hint: String?, onPick: () -> Unit
    ): View {
        val on = wallpaper == look.wallpaper
        val name = AppearanceLabels.wallpaper(context, wallpaper)
        val tileLook = WallpaperLook(wallpaper, dim = 0, blur = 0, angle = look.angle)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(context).apply {
                background = WallpaperDrawable(tileLook, WallDevice.CAR, pal, density, context.dp(p.corners.tile).toFloat(), photo)
                foreground = AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.tile, if (on) p.accent else p.hairline, strokeDp = 2)
                if (on) addView(FrameLayout(context).apply {
                    background = AppearanceUi.oval(p.accent)
                    addView(GlyphView(context, Glyphs.CHECK, p.onAccent, 3f), FrameLayout.LayoutParams(context.dp(14), context.dp(14), Gravity.CENTER))
                }, FrameLayout.LayoutParams(context.dp(22), context.dp(22), Gravity.END or Gravity.TOP).apply { setMargins(0, context.dp(6), context.dp(6), 0) })
            }, LinearLayout.LayoutParams(-1, context.dp(heightDp)))
            addView(AppearanceUi.text(context, name, 13f, if (on) p.accent else p.textSecondary, bold = on, lines = 1).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
            if (hint != null) addView(AppearanceUi.text(context, hint, 11f, p.textTertiary, lines = 1).apply { gravity = Gravity.CENTER })
            background = AppearanceUi.ripple(AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.tile))
            AppearanceUi.makeTappable(this, if (on) context.getString(R.string.appearance_selected, name) else name, on) { if (!on) onPick() }
        }
    }

    private fun gallery(
        context: Context, kind: WallpaperKind, look: WallpaperLook, pal: ThemePalette, density: Float, onPick: (Wallpaper) -> Unit
    ): View {
        val images = kind == WallpaperKind.IMAGE
        val tiles = WallpaperCatalog.inKind(kind).map { w ->
            tile(context, w, look, pal, density, heightDp = if (images) 100 else 64, photo = null, hint = AppearanceLabels.suits(context, w)) { onPick(w) }
        }
        return AppearanceUi.grid(context, tiles, columns = if (images) 2 else 4, gapDp = if (images) 10 else 8)
    }

    private fun angleCard(context: Context, look: WallpaperLook, onAngle: (Int) -> Unit): View {
        val turns = WallpaperCatalog.turns(look.wallpaper)
        val value = AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_angle_value, look.angle), 13f, p.textSecondary, lines = 1)
            .apply { fontFeatureSettings = "tnum" }
        return AppearanceUi.paddedCard(context).apply {
            alpha = if (turns) 1f else 0.45f
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_angle), 15f, p.text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(value)
            })
            addView(AppearanceUi.seekBar(
                context, AppearanceModel.ANGLE_STEPS, AppearanceModel.angleToProgress(look.angle), context.getString(R.string.appearance_wallpaper_angle),
                onMove = { at -> value.text = context.getString(R.string.appearance_wallpaper_angle_value, AppearanceModel.progressToAngle(at)) },
                onRelease = { at -> onAngle(AppearanceModel.progressToAngle(at)) }
            ).apply { isEnabled = turns }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
            addView(AppearanceUi.text(context, context.getString(
                when {
                    look.wallpaper == Wallpaper.GLOW -> R.string.appearance_wallpaper_angle_glow
                    turns -> R.string.appearance_wallpaper_angle_turns
                    else -> R.string.appearance_wallpaper_angle_pick
                }
            ), 13f, p.textSecondary))
        }
    }

    // ===================================================================================== photo

    private fun photoTab(
        context: Context, actions: AppearanceActions, look: WallpaperLook, pal: ThemePalette, density: Float, photo: Bitmap?, onPick: (Wallpaper) -> Unit
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (photo == null) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.card, p.hairline)
                setPadding(context.dp(16), context.dp(20), context.dp(16), context.dp(16))
                addView(AppearanceUi.glyphChip(context, Glyphs.IMAGE, p.accentSoft, p.accent, sizeDp = 52, glyphDp = 26, radiusDp = p.corners.chip))
                addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_photo_title), 15f, p.text, bold = true).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
                addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_photo_body), 13f, p.textSecondary).apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.3f)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
                addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_choose), 15f, p.onAccent, bold = true).apply {
                    gravity = Gravity.CENTER
                    minHeight = context.dp(48)
                    setPadding(context.dp(22), 0, context.dp(22), 0)
                    background = AppearanceUi.ripple(AppearanceUi.shape(context, p.accent, p.corners.button))
                    AppearanceUi.makeTappable(this, text.toString()) { actions.pickPhoto() }
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(14) })
            })
            return@apply
        }
        val photoTile = tile(context, Wallpaper.CUSTOM, look, pal, density, 100, photo, null) { onPick(Wallpaper.CUSTOM) }
        val choose = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(context).apply {
                background = AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.tile, p.hairline, strokeDp = 2)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(AppearanceUi.glyph(context, Glyphs.PLUS, p.textSecondary, 22))
                    addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_change), 11f, p.textSecondary, lines = 2).apply { gravity = Gravity.CENTER })
                }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            }, LinearLayout.LayoutParams(-1, context.dp(100)))
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_photo_replace), 13f, p.textTertiary, lines = 1).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
            background = AppearanceUi.ripple(AppearanceUi.shape(context, Color.TRANSPARENT, p.corners.tile))
            AppearanceUi.makeTappable(this, context.getString(R.string.appearance_wallpaper_change)) { actions.pickPhoto() }
        }
        addView(AppearanceUi.grid(context, listOf(photoTile, choose), columns = TILE_PHOTO_COLUMNS, gapDp = 8))
        addView(AppearanceUi.paddedCard(context, 12).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(AppearanceUi.titles(context, context.getString(R.string.appearance_wallpaper_custom), context.getString(R.string.appearance_wallpaper_photo_stays)),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_photo_remove), 14f, p.danger, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = context.dp(44)
                setPadding(context.dp(12), 0, context.dp(12), 0)
                AppearanceUi.makeTappable(this, text.toString()) { actions.removePhoto() }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
    }

    // ====================================================================================== crop

    private fun cropCard(
        context: Context, actions: AppearanceActions, spec: AppearanceSpec, look: WallpaperLook, pal: ThemePalette, photo: Bitmap?,
        onCrop: (WallDevice, WallpaperCrop) -> Unit
    ): View {
        val device = AppearanceModel.cropDevice(spec, actions.wallpaperUi.cropCar)
        val other = if (device == WallDevice.CAR) WallDevice.PHONE else WallDevice.CAR
        val image = if (look.wallpaper == Wallpaper.CUSTOM) photo else WallpaperScenes.bitmap(look.wallpaper, 600)
        val metrics = context.resources.displayMetrics
        val phoneAspect = metrics.widthPixels.toFloat() / metrics.heightPixels
        fun aspect(d: WallDevice) = if (d == WallDevice.CAR) AppearanceModel.CAR_ASPECT else phoneAspect
        fun cropOf(d: WallDevice) = if (d == WallDevice.CAR) look.carCrop else look.phoneCrop
        var live = cropOf(device)
        val showOther = spec.wallpaperTarget == WallpaperTarget.BOTH
        val view = CropView(context, image, p.accent).apply {
            contentDescription = context.getString(R.string.appearance_wallpaper_crop_preview)
            update(aspect(device), live, if (showOther) aspect(other) else null, cropOf(other))
        }
        val imageAspect = image?.let { it.width.toFloat() / it.height } ?: WallpaperCatalog.IMAGE_ASPECT
        val zoomValue = AppearanceUi.text(context, "${live.zoom}%", 13f, p.textSecondary, lines = 1).apply { fontFeatureSettings = "tnum" }
        val xValue = AppearanceUi.text(context, "${live.x}%", 13f, p.textSecondary, lines = 1).apply { fontFeatureSettings = "tnum" }
        val yValue = AppearanceUi.text(context, "${live.y}%", 13f, p.textSecondary, lines = 1).apply { fontFeatureSettings = "tnum" }
        lateinit var xBar: SeekBar
        lateinit var yBar: SeekBar

        /** Pan sliders do nothing while the view already shows the full width or height. */
        fun syncPan() {
            val canX = WallpaperCatalog.canPanX(imageAspect, aspect(device), live)
            val canY = WallpaperCatalog.canPanY(imageAspect, aspect(device), live)
            xBar.isEnabled = canX; xBar.alpha = if (canX) 1f else 0.45f
            yBar.isEnabled = canY; yBar.alpha = if (canY) 1f else 0.45f
        }

        fun moved() {
            zoomValue.text = "${live.zoom}%"; xValue.text = "${live.x}%"; yValue.text = "${live.y}%"
            view.update(aspect(device), live, if (showOther) aspect(other) else null, cropOf(other))
            syncPan()
        }

        fun slider(title: String, valueView: TextView, max: Int, progress: Int, onMove: (Int) -> Unit): Pair<View, SeekBar> {
            val bar = AppearanceUi.seekBar(context, max, progress, title, onMove = { at -> onMove(at); moved() }, onRelease = { onCrop(device, live) })
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(AppearanceUi.text(context, title, 15f, p.text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(valueView)
                })
                addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
            }
            return column to bar
        }

        val (zoomRow, _) = slider(context.getString(R.string.appearance_wallpaper_zoom), zoomValue, AppearanceModel.ZOOM_STEPS, AppearanceModel.zoomToProgress(live.zoom)) {
            live = live.copy(zoom = AppearanceModel.progressToZoom(it))
        }
        val (xRow, xb) = slider(context.getString(R.string.appearance_wallpaper_pan_x), xValue, 100, live.x) { live = live.copy(x = it) }
        val (yRow, yb) = slider(context.getString(R.string.appearance_wallpaper_pan_y), yValue, 100, live.y) { live = live.copy(y = it) }
        xBar = xb
        yBar = yb
        syncPan()

        return AppearanceUi.paddedCard(context).apply {
            if (showOther) {
                addView(AppearanceUi.segmented(
                    context, listOf(WallDevice.PHONE to AppearanceLabels.target(context, WallpaperTarget.PHONE), WallDevice.CAR to AppearanceLabels.target(context, WallpaperTarget.CAR)), device
                ) { picked -> actions.wallpaperUi.cropCar = picked == WallDevice.CAR; actions.refresh() })
            }
            addView(view, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = context.dp(if (showOther) 12 else 0)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            val here = context.getString(if (device == WallDevice.CAR) R.string.appearance_wallpaper_crop_car else R.string.appearance_wallpaper_crop_phone)
            val there = context.getString(if (other == WallDevice.CAR) R.string.appearance_wallpaper_crop_car else R.string.appearance_wallpaper_crop_phone)
            addView(AppearanceUi.text(context,
                if (showOther) context.getString(R.string.appearance_wallpaper_crop_caption, here, there) else context.getString(R.string.appearance_wallpaper_crop_caption_one, here),
                11f, p.textTertiary).apply { gravity = Gravity.CENTER; setPadding(0, context.dp(8), 0, context.dp(4)) })
            addView(zoomRow)
            addView(xRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
            addView(yRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_crop_reset), 14f, p.text, bold = true).apply {
                gravity = Gravity.CENTER
                minHeight = context.dp(44)
                background = ripple(p.hairline, context)
                AppearanceUi.makeTappable(this, text.toString()) { onCrop(device, WallpaperCrop.FULL) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        }
    }

    // ==================================================================================== effects

    private fun effectsCard(
        context: Context, actions: AppearanceActions, spec: AppearanceSpec, look: WallpaperLook, editLight: Boolean, pal: ThemePalette,
        photo: Bitmap?, onBlur: (Int) -> Unit, onDim: (Int) -> Unit
    ): View {
        val blurs = WallpaperCatalog.blurs(look.wallpaper)
        val blurValue = AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_blur_value, look.blur), 13f, p.textSecondary, lines = 1).apply { fontFeatureSettings = "tnum" }
        val dimValue = AppearanceUi.text(context, "${look.dim}%", 13f, p.textSecondary, lines = 1).apply { fontFeatureSettings = "tnum" }
        val lum = WallpaperCatalog.luminance(
            look.wallpaper, editLight, WallpaperCatalog.luminanceOf(pal.background),
            WallpaperPhoto.luminance(context) ?: 0.2
        )
        return AppearanceUi.paddedCard(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                alpha = if (blurs) 1f else 0.45f
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(AppearanceUi.text(context, context.getString(R.string.appearance_wallpaper_blur), 15f, p.text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(blurValue)
                })
                addView(AppearanceUi.seekBar(
                    context, AppearanceModel.BLUR_STEPS, AppearanceModel.blurToProgress(look.blur), context.getString(R.string.appearance_wallpaper_blur),
                    onMove = { at -> blurValue.text = context.getString(R.string.appearance_wallpaper_blur_value, AppearanceModel.progressToBlur(at)) },
                    onRelease = { at -> onBlur(AppearanceModel.progressToBlur(at)) }
                ).apply { isEnabled = blurs }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
                addView(AppearanceUi.text(context, context.getString(if (blurs) R.string.appearance_wallpaper_blur_hint else R.string.appearance_wallpaper_blur_off), 13f, p.textSecondary))
            })
            addView(AppearanceUi.hairline(context), LinearLayout.LayoutParams(-1, maxOf(1, context.dp(1))).apply { topMargin = context.dp(10); bottomMargin = context.dp(10) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(AppearanceUi.text(context, context.getString(R.string.appearance_dim), 15f, p.text, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(dimValue)
            })
            var liveDim = look.dim
            val ratioView = contrastRow(context, lum, look.dim, editLight)
            val contrastSlot = FrameLayout(context).apply { addView(ratioView) }
            addView(AppearanceUi.seekBar(
                context, AppearanceModel.DIM_STEPS, AppearanceModel.dimToProgress(look.dim), context.getString(R.string.appearance_dim),
                onMove = { at ->
                    liveDim = AppearanceModel.progressToDim(at)
                    dimValue.text = "$liveDim%"
                    contrastSlot.removeAllViews()
                    contrastSlot.addView(contrastRow(context, lum, liveDim, editLight))
                },
                onRelease = { at -> onDim(AppearanceModel.progressToDim(at)) }
            ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
            addView(contrastSlot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
            addView(AppearanceUi.hairline(context), LinearLayout.LayoutParams(-1, maxOf(1, context.dp(1))).apply { topMargin = context.dp(10) })
            addView(AppearanceUi.toggleRow(
                context,
                context.getString(R.string.appearance_blur),
                context.getString(R.string.appearance_blur_caption),
                spec.blurBehindCards
            ) { actions.change { it.copy(blurBehindCards = !it.blurBehindCards) } }.apply { setPadding(0, context.dp(6), 0, 0) })
        }
    }

    /** The estimated text contrast on this wallpaper at this dim: a pill (Excellent, 12.1:1) and one line of advice. */
    private fun contrastRow(context: Context, luminance: Double, dim: Int, light: Boolean): View {
        val ratio = WallpaperCatalog.contrast(luminance, dim, light)
        val level = WallpaperCatalog.legibility(ratio)
        val tint = when (level) {
            WallpaperCatalog.Legibility.EXCELLENT, WallpaperCatalog.Legibility.GOOD -> p.good
            WallpaperCatalog.Legibility.FAIR -> p.warn
            WallpaperCatalog.Legibility.LOW -> p.danger
        }
        val label = AppearanceLabels.legibility(context, level) + " · " + String.format(java.util.Locale.ROOT, "%.1f:1", ratio)
        val tip = context.getString(
            if (level == WallpaperCatalog.Legibility.EXCELLENT || level == WallpaperCatalog.Legibility.GOOD) R.string.appearance_wallpaper_contrast_ok
            else R.string.appearance_wallpaper_contrast_poor
        )
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AppearanceUi.shape(context, p.raised, AppearanceModel.smallRadii(p.corners).chipSmall)
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
            addView(AppearanceUi.text(context, label, 11f, tint, bold = true, lines = 1).apply {
                gravity = Gravity.CENTER
                fontFeatureSettings = "tnum"
                setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
                background = AppearanceUi.shape(context, ThemeResolver.withAlpha(tint, 0x26), 14)
            })
            addView(AppearanceUi.text(context, tip, 13f, p.textSecondary), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = context.dp(10) })
            contentDescription = "$label. $tip"
        }
    }

    /**
     * The whole picture with the part a screen shows outlined in the accent colour, everything
     * else dimmed, and (for Both) the other screen's part dashed. Redraws as the sliders move.
     */
    private class CropView(context: Context, private val image: Bitmap?, private val accent: Int) : View(context) {
        private var viewAspect = 1f
        private var crop = WallpaperCrop.FULL
        private var otherAspect: Float? = null
        private var otherCrop = WallpaperCrop.FULL
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val aspect get() = image?.let { it.width.toFloat() / it.height } ?: WallpaperCatalog.IMAGE_ASPECT

        fun update(aspect: Float, crop: WallpaperCrop, otherAspect: Float?, otherCrop: WallpaperCrop) {
            this.viewAspect = aspect
            this.crop = crop
            this.otherAspect = otherAspect
            this.otherCrop = otherCrop
            invalidate()
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec).coerceAtMost(context.dp(320))
            setMeasuredDimension(w, (w / aspect).roundToInt())
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val box = RectF(0f, 0f, w, h)
            if (image != null) canvas.drawBitmap(image, null, box, paint) else {
                paint.color = p.raised
                canvas.drawRect(box, paint)
            }
            fun rectOf(a: Float, c: WallpaperCrop): RectF {
                val o = WallpaperCatalog.outline(aspect, a, c)
                return RectF(o.left * w, o.top * h, (o.left + o.width) * w, (o.top + o.height) * h)
            }
            val main = rectOf(viewAspect, crop)
            paint.style = Paint.Style.FILL
            paint.color = 0x94000000.toInt()
            canvas.drawRect(0f, 0f, w, main.top, paint)
            canvas.drawRect(0f, main.bottom, w, h, paint)
            canvas.drawRect(0f, main.top, main.left, main.bottom, paint)
            canvas.drawRect(main.right, main.top, w, main.bottom, paint)
            otherAspect?.let { a ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dp(2).toFloat()
                paint.color = 0x8CFFFFFF.toInt()
                paint.pathEffect = DashPathEffect(floatArrayOf(context.dp(6).toFloat(), context.dp(4).toFloat()), 0f)
                canvas.drawRect(rectOf(a, otherCrop), paint)
                paint.pathEffect = null
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = context.dp(2).toFloat()
            paint.color = accent
            canvas.drawRect(main, paint)
        }
    }
}
