package dev.autobridge.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
        content = content
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
