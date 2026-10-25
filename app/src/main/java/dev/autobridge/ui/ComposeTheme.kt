package dev.autobridge.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.autobridge.design.ThemeHolder

/*
 * Compose theme for the phone's Compose screens (Control, History, Agent & Commands,
 * Car & Connection, Home dashboard). Formerly lived beside the Control Center composable,
 * which was removed when its actions moved to the Control tab and Settings.
 */

@Composable
fun AutoBridgePhoneTheme(content: @Composable () -> Unit) {
    // Mirrors AutoBridgeDesign so the Compose slices sit on the same surface stack as the rest of
    // the phone app instead of their own teal-navy palette.
    val light = ThemeHolder.palette.isLight
    MaterialTheme(
        colorScheme = if (light) lightColorScheme(
            primary = ComposeTokens.Accent,
            onPrimary = Color(ThemeHolder.palette.onAccent),
            background = ComposeTokens.Ink,
            onBackground = ComposeTokens.Text,
            surface = ComposeTokens.Surface,
            onSurface = ComposeTokens.Text,
            surfaceVariant = ComposeTokens.SurfaceRaised,
            onSurfaceVariant = ComposeTokens.TextMuted,
            outline = ComposeTokens.Hairline,
            outlineVariant = ComposeTokens.Hairline
        ) else darkColorScheme(
            primary = ComposeTokens.Accent,
            onPrimary = Color(ThemeHolder.palette.onAccent),
            background = ComposeTokens.Ink,
            onBackground = ComposeTokens.Text,
            surface = ComposeTokens.Surface,
            onSurface = ComposeTokens.Text,
            surfaceVariant = ComposeTokens.SurfaceRaised,
            onSurfaceVariant = ComposeTokens.TextMuted,
            outline = ComposeTokens.Hairline,
            outlineVariant = ComposeTokens.Hairline
        ),
        typography = appTypography(),
        content = content
    )
}

/**
 * One weight of the app's typeface as a Compose font. Handing Compose a bare [Typeface] makes it
 * ignore every `fontWeight`; one font per weight lets it pick the right one, and each carries the
 * Thai letterforms chosen in Appearance, which Compose cannot attach to a family itself.
 */
@OptIn(ExperimentalTextApi::class)
private class FaceFont(override val weight: FontWeight, val face: Typeface) :
    AndroidFont(FontLoadingStrategy.Blocking, FaceLoader, FontVariation.Settings()) {
    override val style: FontStyle = FontStyle.Normal
}

@OptIn(ExperimentalTextApi::class)
private object FaceLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? = (font as FaceFont).face
    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? = (font as FaceFont).face
}

/**
 * Material's type scale in the app's chosen family ([AppFonts]), so Text without an explicit family
 * draws in the Appearance font, Latin and Thai. Only the family changes: sizes stay as written at
 * each call site, and the platform scales them by the font scale.
 */
@Composable
private fun appTypography(): Typography {
    val base = AppFonts.base() ?: return Typography()
    val family = FontFamily(AppFonts.WEIGHTS.map { FaceFont(FontWeight(it), Typeface.create(base, it, false)) })
    val t = Typography()
    return t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = family),
        displayMedium = t.displayMedium.copy(fontFamily = family),
        displaySmall = t.displaySmall.copy(fontFamily = family),
        headlineLarge = t.headlineLarge.copy(fontFamily = family),
        headlineMedium = t.headlineMedium.copy(fontFamily = family),
        headlineSmall = t.headlineSmall.copy(fontFamily = family),
        titleLarge = t.titleLarge.copy(fontFamily = family),
        titleMedium = t.titleMedium.copy(fontFamily = family),
        titleSmall = t.titleSmall.copy(fontFamily = family),
        bodyLarge = t.bodyLarge.copy(fontFamily = family),
        bodyMedium = t.bodyMedium.copy(fontFamily = family),
        bodySmall = t.bodySmall.copy(fontFamily = family),
        labelLarge = t.labelLarge.copy(fontFamily = family),
        labelMedium = t.labelMedium.copy(fontFamily = family),
        labelSmall = t.labelSmall.copy(fontFamily = family)
    )
}

/**
 * `AutoBridgeDesign`'s tokens as Compose colours.
 *
 * The design system is written against Android views (the app is mostly programmatic Views), so
 * this is the one place the same values are restated for the Compose slices. Keeping them here
 * rather than inline in each composable means a token change lands everywhere at once.
 */
object ComposeTokens {
    val Ink: Color get() = Color(AutoBridgeDesign.INK)
    val Surface: Color get() = Color(AutoBridgeDesign.SURFACE)
    val SurfaceRaised: Color get() = Color(AutoBridgeDesign.SURFACE_RAISED)
    val Hairline: Color get() = Color(AutoBridgeDesign.HAIRLINE)
    val Text: Color get() = Color(AutoBridgeDesign.TEXT)
    val TextMuted: Color get() = Color(AutoBridgeDesign.TEXT_MUTED)
    val Accent: Color get() = Color(AutoBridgeDesign.ACCENT)
    val AccentSoft: Color get() = Color(AutoBridgeDesign.ACCENT_SOFT)
    val Danger: Color get() = Color(AutoBridgeDesign.DANGER)
    val Ok: Color get() = Color(AutoBridgeDesign.ACCENT_ONLINE)
    val Warn: Color get() = Color(AutoBridgeDesign.ACCENT_RADIO)
}
