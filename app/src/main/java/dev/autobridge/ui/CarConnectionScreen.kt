package dev.autobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.R
import dev.autobridge.core.state.RuntimeContextStore

/** One tappable settings row: accent icon badge, title, caption and a chevron. */
data class SettingsLink(
    val icon: Int,
    val title: String,
    val caption: String,
    val onClick: () -> Unit
)

/**
 * Settings > Car & Connection. Replaces the old root "Devices" tab.
 *
 * The vehicle card reads the same [RuntimeContextStore] flow as Home and Control (single source of
 * truth for connected / parked), so it updates live when the head unit disconnects or reconnects.
 * The old Devices tab printed a hard-coded "REAL_CAR (Parked)"; that internal wording now only
 * appears under Advanced > Debug.
 */
@Composable
fun CarConnectionScreen(onBack: () -> Unit, links: List<SettingsLink>) {
    val runtime by RuntimeContextStore.context.collectAsState()
    Column(Modifier.fillMaxSize().background(ComposeTokens.Ink)) {
        PhoneHeader(
            title = stringResource(R.string.car_connection_title),
            subtitle = stringResource(R.string.car_connection_subtitle),
            onBack = onBack
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionLabel(stringResource(R.string.car_connection_current_vehicle))
            AndroidAutoStatusCard(
                title = runtime.vehicleProfile?.name ?: stringResource(R.string.car_connection_my_car),
                caption = stringResource(R.string.control_android_auto),
                onClick = null
            )
            SectionLabel(stringResource(R.string.car_connection_settings_section))
            links.forEach { SettingsLinkRow(it) }
        }
    }
}

@Composable
fun SettingsLinkRow(link: SettingsLink) {
    Surface(
        onClick = link.onClick,
        color = ComposeTokens.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp)
                    .background(ComposeTokens.Accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .border(1.dp, ComposeTokens.Accent.copy(alpha = 0.28f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(link.icon),
                    contentDescription = null,
                    tint = ComposeTokens.Accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(link.title, color = ComposeTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(link.caption, color = ComposeTokens.TextMuted, fontSize = 12.sp)
            }
            Text("›", color = ComposeTokens.TextMuted, fontSize = 22.sp)
        }
    }
}
