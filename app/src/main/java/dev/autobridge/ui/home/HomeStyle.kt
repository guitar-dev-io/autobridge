package dev.autobridge.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.design.AppearanceSpec
import dev.autobridge.design.Corners
import dev.autobridge.design.ThemeHolder
import dev.autobridge.design.ThemePalette
import dev.autobridge.ui.PhoneHomeLayout

/**
 * What one Home pass draws with: the resolved palette, the Appearance choices it reads (icon
 * style, labels) and the effective font scale. Read once per composition; any Appearance change
 * recreates the activity, so nothing here has to observe the store.
 */
class HomeLook(val palette: ThemePalette, val spec: AppearanceSpec, val fontScale: Float) {
    val background = Color(palette.background)
    val text = Color(palette.text)
    val textSecondary = Color(palette.textSecondary)
    val textTertiary = Color(palette.textTertiary)
    val accent = Color(palette.accent)
    val onAccent = Color(palette.onAccent)
    val accentSoft = Color(palette.accentSoft)
    val raised = Color(palette.raised)
    val hairline = Color(palette.hairline)
    val surface = Color(palette.surface)
    val good = Color(palette.good)
    val danger = Color(palette.danger)
    val cardFill = Color(palette.cardFill)
    val cardEdge = Color(palette.cardEdge)

    private val corners: Corners get() = palette.corners
    val cardShape = RoundedCornerShape(corners.card.dp)
    val tileShape = RoundedCornerShape(corners.tile.dp)
    val buttonShape = RoundedCornerShape(corners.button.dp)
    val chipShape = RoundedCornerShape(corners.chip.dp)
    /** The design's smaller chip (compact rows) and artwork radii, which [Corners] does not list. */
    val smallChipShape = RoundedCornerShape(when (corners) { Corners.SOFT -> 11.dp; Corners.STANDARD -> 9.dp; Corners.SHARP -> 6.dp })
    val artShape = RoundedCornerShape(when (corners) { Corners.SOFT -> 16.dp; Corners.STANDARD -> 12.dp; Corners.SHARP -> 6.dp })

    val largeText: Boolean get() = PhoneHomeLayout.isLargeText(fontScale)

    fun chip(item: PhoneHomeLayout.Item): PhoneHomeLayout.ChipColors =
        PhoneHomeLayout.chipColors(item, spec.iconStyle, palette)
}

@Composable
fun rememberHomeLook(): HomeLook =
    HomeLook(ThemeHolder.palette, ThemeHolder.spec, LocalDensity.current.fontScale)

/** A card in the chosen card style: its fill and a 1 dp edge (an Elevated card's edge is its fill). */
fun Modifier.homeCard(look: HomeLook, shape: Shape = look.cardShape): Modifier =
    this.background(look.cardFill, shape).border(1.dp, look.cardEdge, shape)

/** A section's icon in its chip, coloured by the icon style. */
@Composable
fun IconChip(look: HomeLook, item: PhoneHomeLayout.Item, icon: Int, size: Dp, iconSize: Dp, shape: Shape) {
    val colors = look.chip(item)
    Box(Modifier.size(size).background(Color(colors.fill), shape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = Color(colors.glyph), modifier = Modifier.size(iconSize))
    }
}

/** The design's small upper-case section label, with an optional text action on the right. */
@Composable
fun HomeSectionHeader(look: HomeLook, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            color = look.textTertiary,
            fontSize = 11.sp,
            letterSpacing = 0.66.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            TextButton(onClick = onAction) {
                Text(action, color = look.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
