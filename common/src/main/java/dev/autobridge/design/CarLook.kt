package dev.autobridge.design

import android.content.res.Resources

/**
 * What Settings > Appearance asks of the car screens drawn on the host surface besides the home
 * (radio, the car browser's chrome and keyboard, Duo Screen's chrome), resolved to the roles they
 * paint with. The car home has its own, richer look (`HomeDashboardLook` in :app) for its layouts,
 * icons and wallpaper; this one carries only what every other car surface needs.
 *
 * The palette is [ThemeHolder.carPalette], which is already the default theme when "Use this look
 * on Android Auto" is off, and the card style, corners and fonts follow the same switch. Text
 * follows the phone's size up to [TextScaleCurve.CAR_CAP].
 *
 * Pure data (ARGB ints and enums), so a renderer and its tests see the same values. A screen keeps
 * the look it drew with and compares it against [current] before a frame: a different look means
 * the user changed something on the phone, and the screen lays out (when text size or font moved)
 * and repaints.
 *
 * The roles follow the redesign's car screens: the page on [background], cards in the chosen card
 * style ([card], [cardEdge]), round controls on [control], the one primary action on [accent] with
 * an [onAccent] glyph, and a selected tile on [selectedFill] with an [accent] edge. Station art,
 * logos and video keep their own colours; none of that comes from here.
 */
data class CarLook(
    val palette: ThemePalette,
    val cardStyle: CardStyle,
    val corners: Corners,
    /** The font scale car text is drawn at, already capped. */
    val textScale: Float,
    /** The chosen families. Text widths depend on them, so a change lays the screen out again. */
    val font: FontChoice = FontChoice.INTER,
    val thai: ThaiStyle = ThaiStyle.SYSTEM
) {
    val isLight: Boolean get() = palette.isLight

    // --- Surfaces ---------------------------------------------------------------------------
    val background: Int get() = palette.background
    /** An opaque card colour, for whatever has to sit on something solid (a sheet, a key tray). */
    val surface: Int get() = palette.surface
    val hairline: Int get() = palette.hairline

    /** Card fill for the chosen [cardStyle]: opaque, transparent (outlined) or translucent (glass). */
    val card: Int get() = palette.cardFill
    /** Drawn as a 1dp edge only when it differs from [card] (outlined and glass cards). */
    val cardEdge: Int get() = palette.cardEdge
    val hasCardEdge: Boolean get() = palette.cardEdge != palette.cardFill
    /** A pressed card: the card lifted towards the text colour, still in its own style. */
    val cardPressed: Int get() {
        val alpha = (palette.cardFill ushr 24) and 0xFF
        val lifted = mix(palette.surface, palette.text, 0.13f)
        // An outlined card has no fill; its pressed state still needs one to show the press.
        return if (alpha == 0) ThemeResolver.withAlpha(lifted, 0x99) else (alpha shl 24) or (lifted and 0x00FFFFFF)
    }

    /** Round controls (back, search, previous, next), tabs at rest, the address pill. */
    val control: Int get() = palette.raised
    val controlPressed: Int get() = mix(palette.raised, palette.text, 0.14f)
    /** A disabled control's glyph and label: still legible, clearly not live. */
    val controlDisabled: Int get() = mix(palette.raised, palette.text, if (isLight) 0.50f else 0.38f)

    // --- Text -------------------------------------------------------------------------------
    val text: Int get() = palette.text
    val textSecondary: Int get() = palette.textSecondary
    val textTertiary: Int get() = palette.textTertiary

    // --- Accent -----------------------------------------------------------------------------
    val accent: Int get() = palette.accent
    val onAccent: Int get() = palette.onAccent
    val accentPressed: Int get() = mix(palette.accent, palette.onAccent, 0.18f)
    /** [accent] mixed into the card colour, opaque: a selected tile, an "on" pill. */
    val selectedFill: Int get() = mix(palette.surface, palette.accent, if (isLight) 0.12f else 0.15f)
    /** Selected text on [selectedFill]. On light the accent is already the darker shade. */
    val selectedText: Int get() = palette.accent

    // --- Status -----------------------------------------------------------------------------
    /** "Live" and stop: the theme's red, darker on light. */
    val live: Int get() = palette.danger
    val good: Int get() = palette.good
    val warn: Int get() = palette.warn
    val skeleton: Int get() = palette.skeleton
    val scrollThumb: Int get() = ThemeResolver.withAlpha(palette.text, 0x66)
    /** Laid over the page under a sheet. */
    val scrim: Int get() = if (isLight) 0x52000000 else 0x7A000000

    // --- Radii (dp) -------------------------------------------------------------------------
    val cardRadius: Float get() = corners.card.toFloat()
    val tileRadius: Float get() = corners.tile.toFloat()
    val buttonRadius: Float get() = corners.button.toFloat()
    /** An icon chip or a logo inside a card, scaled from the design's 52dp chip. */
    fun chipRadius(size: Float): Float = corners.chip * size / DESIGN_CHIP

    /** [base] dp of text at this look's [textScale]. */
    fun text(base: Float): Float = TextScaleCurve.scaled(base, textScale)

    companion object {
        private const val DESIGN_CHIP = 52f

        /** The look for [spec] and the car [palette] resolved from it; [systemFontScale] is Android's own. */
        fun of(spec: AppearanceSpec, palette: ThemePalette, systemFontScale: Float): CarLook {
            val car = if (spec.carSameLook) spec else AppearanceSpec.DEFAULT
            return CarLook(
                palette = palette,
                cardStyle = car.cardStyle,
                corners = car.corners,
                textScale = TextScaleCurve.carScale(spec, systemFontScale),
                font = car.font,
                thai = car.thai
            )
        }

        /** The look the app is set to right now. Cheap: a few field reads, no I/O. */
        fun current(): CarLook =
            of(ThemeHolder.spec, ThemeHolder.carPalette, systemFontScale())

        /** Android's font size; 1 where there are no system resources (JVM tests). */
        private fun systemFontScale(): Float =
            runCatching { Resources.getSystem().configuration.fontScale }.getOrNull() ?: 1f

        /** The default look: what an unset store, and the tests, draw with. */
        val DEFAULT: CarLook = of(AppearanceSpec.DEFAULT, ThemeResolver.resolveCar(AppearanceSpec.DEFAULT), 1f)

        /** [from] moved [t] (0..1) of the way to [to], per channel; always opaque. */
        fun mix(from: Int, to: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val a = (from shr shift) and 0xFF
                val b = (to shr shift) and 0xFF
                return ((a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255)) shl shift
            }
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }

        /** WCAG contrast ratio of two opaque colours, for the tests that keep car text readable. */
        fun contrast(a: Int, b: Int): Double {
            fun lum(c: Int): Double {
                fun f(v: Int): Double {
                    val s = v / 255.0
                    return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
                }
                return 0.2126 * f((c shr 16) and 0xFF) + 0.7152 * f((c shr 8) and 0xFF) + 0.0722 * f(c and 0xFF)
            }
            val la = lum(a)
            val lb = lum(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }
    }
}
