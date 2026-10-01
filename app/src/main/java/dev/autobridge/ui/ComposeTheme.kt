package dev.autobridge.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Compose theme for the phone's Compose screens (Control, History, Agent & Commands,
 * Car & Connection, Home dashboard). Formerly lived beside the Control Center composable,
 * which was removed when its actions moved to the Control tab and Settings.
 */

@Composable
fun AutoBridgePhoneTheme(content: @Composable () -> Unit) {
    // Mirrors AutoBridgeDesign so the Compose slices sit on the same surface stack as the rest of
    // the phone app instead of their own teal-navy palette.
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ComposeTokens.Accent,
            onPrimary = ComposeTokens.Ink,
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
    val Ink = Color(AutoBridgeDesign.INK)
    val Surface = Color(AutoBridgeDesign.SURFACE)
    val SurfaceRaised = Color(AutoBridgeDesign.SURFACE_RAISED)
    val Hairline = Color(AutoBridgeDesign.HAIRLINE)
    val Text = Color(AutoBridgeDesign.TEXT)
    val TextMuted = Color(AutoBridgeDesign.TEXT_MUTED)
    val Accent = Color(AutoBridgeDesign.ACCENT)
    val AccentSoft = Color(AutoBridgeDesign.ACCENT_SOFT)
    val Danger = Color(AutoBridgeDesign.DANGER)
    val Ok = Color(AutoBridgeDesign.ACCENT_FILES)
    val Warn = Color(AutoBridgeDesign.ACCENT_RADIO)
}
