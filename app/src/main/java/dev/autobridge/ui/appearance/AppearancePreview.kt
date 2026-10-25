package dev.autobridge.ui.appearance

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.autobridge.R
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.ThemeHolder
import dev.autobridge.design.ThemePalette
import dev.autobridge.design.Wallpaper
import dev.autobridge.ui.AutoBridgeDesign.dp
import dev.autobridge.ui.appearance.AppearanceModel.Category
import dev.autobridge.ui.appearance.AppearanceUi.p

/**
 * The live preview at the top of every Appearance page: a mini phone home and a mini car home in
 * the current palette, wallpaper and dim, icon style, card style and corners, then a line of text
 * and the two button kinds. The car side uses the car's palette, which is the defaults when "Use
 * this look on Android Auto" is off.
 */
internal object AppearancePreview {

    private val PHONE_TILES = listOf(
        Category.BROWSER to Glyphs.BROWSER, Category.MUSIC to Glyphs.MUSIC, Category.RADIO to Glyphs.RADIO,
        Category.TV to Glyphs.TV, Category.MIRROR to Glyphs.MIRROR, Category.MY_CAR to Glyphs.MY_CAR
    )

    fun build(context: Context, spec: AppearanceSpec, summary: String): View {
        val small = AppearanceModel.smallRadii(spec.corners)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppearanceUi.shape(context, p.raised, p.corners.card)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        }
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(AppearanceUi.text(context, context.getString(R.string.appearance_live_preview).uppercase(), 11f, p.textTertiary, bold = true, lines = 1)
                .apply { letterSpacing = 0.06f }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(AppearanceUi.text(context, summary, 11f, p.textTertiary, lines = 1).apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(8) })
        }, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        row.addView(miniPhone(context, spec, small), LinearLayout.LayoutParams(context.dp(100), context.dp(172)))
        row.addView(rightColumn(context, spec), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = context.dp(10) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        root.contentDescription = context.getString(R.string.appearance_live_preview) + ": " + summary
        return root
    }

    private fun miniPhone(context: Context, spec: AppearanceSpec, small: AppearanceModel.SmallRadii): View {
        val palette = p
        val frame = FrameLayout(context).apply {
            background = WallpaperDrawable(
                wallpaper = if (AppearanceModel.wallpaperOnPhone(spec)) spec.wallpaper else Wallpaper.NONE,
                palette = palette,
                density = context.resources.displayMetrics.density,
                radiusPx = context.dp(16).toFloat(),
                dimPercent = if (AppearanceModel.wallpaperOnPhone(spec)) spec.wallpaperDim else 0,
                photo = if (spec.wallpaper == Wallpaper.CUSTOM) WallpaperPhoto.preview(context) else null
            )
            foreground = AppearanceUi.shape(context, Color.TRANSPARENT, 16, palette.hairline)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(8), context.dp(10), context.dp(8), context.dp(8))
        }
        column.addView(AppearanceUi.text(context, context.getString(R.string.app_name), 10f, palette.text, bold = true, lines = 1).apply {
            // The mock is a fixed-size drawing: its text must not grow with the font scale.
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10f)
        })
        val tiles = PHONE_TILES.map { (category, glyph) ->
            FrameLayout(context).apply {
                background = AppearanceUi.shape(context, palette.cardFill, small.mini, palette.cardEdge)
                val chip = AppearanceModel.chip(spec.iconStyle, category, palette)
                addView(FrameLayout(context).apply {
                    background = AppearanceUi.shape(context, chip.fill, small.miniChip)
                    addView(GlyphView(context, glyph, chip.glyph, 2.2f), FrameLayout.LayoutParams(context.dp(12), context.dp(12), Gravity.CENTER))
                }, FrameLayout.LayoutParams(context.dp(20), context.dp(20), Gravity.CENTER))
                minimumHeight = context.dp(36)
            }
        }
        column.addView(AppearanceUi.grid(context, tiles, columns = 2, gapDp = 5), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
        frame.addView(column, FrameLayout.LayoutParams(-1, -2))
        return frame
    }

    private fun rightColumn(context: Context, spec: AppearanceSpec): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(miniCar(context, spec), LinearLayout.LayoutParams(-1, context.dp(120)))
        addView(AppearanceUi.text(context, context.getString(R.string.appearance_preview_line), 15f, p.text).apply {
            setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(previewButton(context, context.getString(R.string.appearance_preview_primary), primary = true))
            addView(previewButton(context, context.getString(R.string.appearance_preview_secondary), primary = false),
                LinearLayout.LayoutParams(-2, context.dp(28)).apply { marginStart = context.dp(6) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
    }

    private fun previewButton(context: Context, label: String, primary: Boolean): View =
        AppearanceUi.text(context, label, 11f, if (primary) p.onAccent else p.text, bold = true, lines = 1).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(12), 0, context.dp(12), 0)
            background = AppearanceUi.shape(context, if (primary) p.accent else Color.TRANSPARENT, p.corners.button,
                if (primary) Color.TRANSPARENT else p.hairline)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(-2, context.dp(28))
        }

    private fun miniCar(context: Context, spec: AppearanceSpec): View {
        val carSpec = AppearanceModel.carSpec(spec)
        val palette: ThemePalette = ThemeHolder.carPalette
        val small = AppearanceModel.smallRadii(carSpec.corners)
        val showWall = AppearanceModel.wallpaperOnCar(spec) || !spec.carSameLook
        val frame = FrameLayout(context).apply {
            background = WallpaperDrawable(
                wallpaper = if (showWall) carSpec.wallpaper else Wallpaper.NONE,
                palette = palette,
                density = context.resources.displayMetrics.density,
                radiusPx = context.dp(12).toFloat(),
                dimPercent = if (showWall) carSpec.wallpaperDim else 0,
                photo = if (carSpec.wallpaper == Wallpaper.CUSTOM) WallpaperPhoto.preview(context) else null
            )
            // The head unit's black bezel.
            foreground = AppearanceUi.shape(context, Color.TRANSPARENT, 12, 0xFF05070A.toInt(), strokeDp = 4)
            setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        fun card(): FrameLayout = FrameLayout(context).apply {
            background = AppearanceUi.shape(context, palette.cardFill, small.mini, palette.cardEdge)
        }
        fun tile(category: Category, glyph: String): View = card().apply {
            val chip = AppearanceModel.chip(carSpec.iconStyle, category, palette)
            addView(FrameLayout(context).apply {
                background = AppearanceUi.shape(context, chip.fill, small.miniChip)
                addView(GlyphView(context, glyph, chip.glyph, 2.2f), FrameLayout.LayoutParams(context.dp(11), context.dp(11), Gravity.CENTER))
            }, FrameLayout.LayoutParams(context.dp(18), context.dp(18), Gravity.CENTER))
        }
        val now = card().apply {
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
                addView(View(context).apply { background = AppearanceUi.shape(context, palette.accentSoft, 6) },
                    LinearLayout.LayoutParams(context.dp(26), context.dp(26)))
                addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
                addView(AppearanceUi.text(context, context.getString(R.string.appearance_preview_track), 8f, palette.text, bold = true, lines = 1).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 8f)
                })
                // Playback progress: the bar fills 60% of the card.
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(View(context).apply { background = AppearanceUi.shape(context, palette.accent, 2) },
                        LinearLayout.LayoutParams(0, -1, 0.6f))
                    addView(View(context), LinearLayout.LayoutParams(0, -1, 0.4f))
                }, LinearLayout.LayoutParams(-1, context.dp(3)).apply { topMargin = context.dp(4) })
            }
            addView(inner, FrameLayout.LayoutParams(-1, -1))
        }
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(now, LinearLayout.LayoutParams(0, -1, 1.4f))
            listOf(
                listOf(Category.RADIO to Glyphs.RADIO, Category.BROWSER to Glyphs.BROWSER),
                listOf(Category.TV to Glyphs.TV, Category.MY_CAR to Glyphs.MY_CAR)
            ).forEach { pair ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tile(pair[0].first, pair[0].second), LinearLayout.LayoutParams(-1, 0, 1f))
                    addView(tile(pair[1].first, pair[1].second), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(5) })
                }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = context.dp(5) })
            }
        }
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        return frame
    }
}
