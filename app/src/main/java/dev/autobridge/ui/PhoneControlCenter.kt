package dev.autobridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import dev.autobridge.core.model.Feature
import dev.autobridge.core.model.RuntimeContext
import dev.autobridge.core.model.VehicleState
import dev.autobridge.core.policy.FeaturePolicy
import dev.autobridge.core.state.RuntimeContextStore

/**
 * Small, non-scrolling Compose slice embedded in the existing phone control center.
 *
 * The projection, launcher, and settings callbacks stay in MainActivity. This component only
 * renders shared runtime state and delegates actions, so it cannot bypass FeaturePolicy or create
 * a second MediaProjection/mirror lifecycle. It is intentionally bounded because the parent phone
 * screen already owns the ScrollView.
 */
@Composable
fun PhoneControlCenter(
    onStartMirror: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenTouch: () -> Unit,
    onOpenDeveloper: () -> Unit
) {
    val runtime by RuntimeContextStore.context.collectAsState()
    val mirrorAllowed = FeaturePolicy.app.isAvailable(Feature.MIRROR, runtime)
    val developerAllowed = FeaturePolicy.app.isAvailable(Feature.DEVELOPER, runtime)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Your drive, connected.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Everything you need, within reach.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton("Apps", "Your shortcuts", "01", onOpenApps, modifier = Modifier.weight(1f))
                ControlButton("Profiles", "Make it yours", "02", onOpenProfiles, modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton("Touch", "Input controls", "03", onOpenTouch, modifier = Modifier.weight(1f))
                ControlButton(
                    "Developer",
                    "Tools & diagnostics",
                    "04",
                    onOpenDeveloper,
                    enabled = developerAllowed,
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = onStartMirror,
                enabled = mirrorAllowed,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) {
                Text(if (mirrorAllowed) "Start mirror" else FeaturePolicy.app.denialMessage(Feature.MIRROR, runtime))
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = vehicleLabel(runtime),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (runtime.vehicleState == VehicleState.PARKED) Color(0xff79dfbb)
                            else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${runtime.mode} • ${runtime.environment}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    subtitle: String,
    number: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 124.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
            )
        }
    }
}

private fun vehicleLabel(runtime: RuntimeContext): String = when (runtime.vehicleState) {
    VehicleState.PARKED -> "PARKED"
    VehicleState.MOVING -> "MOVING — locked"
    VehicleState.UNKNOWN -> "UNKNOWN — locked"
}

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
