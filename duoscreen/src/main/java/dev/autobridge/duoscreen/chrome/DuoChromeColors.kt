package dev.autobridge.duoscreen.chrome

import dev.autobridge.design.CarLook
import dev.autobridge.design.ThemeResolver

/**
 * The colours Duo Screen's chrome paints with, from the car's Appearance ([CarLook]): the gaps and
 * bar on the page colour, buttons on the raised control colour, the focused pane's edge, the
 * selected preset and Done on the accent, and the Arrange veil in the page colour so the cards
 * over the panes read in light mode as well as dark. App pixels inside the panes are the apps'
 * own and are never tinted.
 *
 * Pure ARGB ints so the mapping is tested on the JVM; [DuoScreenChromeRenderer] builds one per
 * look and reuses it while the look stands.
 */
data class DuoChromeColors(
    val surface: Int,
    val button: Int,
    val buttonPressed: Int,
    val icon: Int,
    val grip: Int,
    val accent: Int,
    val onAccent: Int,
    /** Laid over a pane while arranging, under its card. */
    val dim: Int,
    val chip: Int,
    val chipSelected: Int,
    val chipText: Int,
    val chipTextSelected: Int,
    val chipGlyph: Int,
    val chipGlyphOn: Int,
    val chipGlyphFill: Int,
    val swap: Int,
    val swapTarget: Int,
    val text: Int,
    val textMuted: Int,
    /** The card's own button: the inverse of the card, text colour fill with page-colour label. */
    val cardButton: Int,
    val cardButtonText: Int,
    val cardButtonPressed: Int,
    val tile: Int,
    /** The floating toolbar over the seam: nearly opaque, with a soft drop below it. */
    val toolbar: Int,
    val toolbarShadow: Int,
    /** The setup card's hairline edge and the soft accent disc behind its lock. */
    val cardEdge: Int,
    val accentSoft: Int
) {
    companion object {
        fun of(look: CarLook): DuoChromeColors {
            val p = look.palette
            val light = p.isLight
            val swap = CarLook.mix(p.surface, p.accent, if (light) 0.22f else 0.30f)
            return DuoChromeColors(
                surface = p.background,
                button = look.control,
                buttonPressed = look.controlPressed,
                icon = p.text,
                grip = CarLook.mix(p.raised, p.text, 0.30f),
                accent = p.accent,
                onAccent = p.onAccent,
                dim = ThemeResolver.withAlpha(p.background, 0xB8),
                chip = look.control,
                chipSelected = look.selectedFill,
                chipText = p.text,
                chipTextSelected = look.selectedText,
                chipGlyph = p.textTertiary,
                chipGlyphOn = CarLook.mix(p.accent, p.text, 0.35f),
                chipGlyphFill = p.textSecondary,
                swap = swap,
                swapTarget = ThemeResolver.withAlpha(swap, 0xB8),
                text = p.text,
                textMuted = p.textSecondary,
                cardButton = p.text,
                cardButtonText = p.background,
                cardButtonPressed = CarLook.mix(p.text, p.background, 0.18f),
                tile = p.raised,
                toolbar = ThemeResolver.withAlpha(p.surface, 0xF2),
                toolbarShadow = if (light) 0x29000000 else 0x66000000,
                cardEdge = ThemeResolver.withAlpha(p.text, 0x1F),
                accentSoft = ThemeResolver.withAlpha(p.accent, 0x2E)
            )
        }
    }
}
