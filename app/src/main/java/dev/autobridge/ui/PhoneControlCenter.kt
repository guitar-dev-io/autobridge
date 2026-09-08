package dev.autobridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "CONTROL CENTER",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton("Apps", onOpenApps, modifier = Modifier.weight(1f))
                ControlButton("Profiles", onOpenProfiles, modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlButton("Touch", onOpenTouch, modifier = Modifier.weight(1f))
                ControlButton(
                    "Developer",
                    onOpenDeveloper,
                    enabled = developerAllowed,
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = onStartMirror,
                enabled = mirrorAllowed,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (mirrorAllowed) "Start mirror" else FeaturePolicy.app.denialMessage(Feature.MIRROR, runtime))
            }
            Text(
                text = "${runtime.mode} • ${runtime.environment} • ${vehicleLabel(runtime)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Text(label)
    }
}

private fun vehicleLabel(runtime: RuntimeContext): String = when (runtime.vehicleState) {
    VehicleState.PARKED -> "PARKED"
    VehicleState.MOVING -> "MOVING — locked"
    VehicleState.UNKNOWN -> "UNKNOWN — locked"
}

@Composable
fun AutoBridgePhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xff159cff),
            onPrimary = Color.White,
            surface = Color(0xff0d2029),
            onSurface = Color(0xfff1f6fb),
            onSurfaceVariant = Color(0xff8ea5b5)
        ),
        content = content
    )
}
