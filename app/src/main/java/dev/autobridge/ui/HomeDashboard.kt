package dev.autobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.R
import dev.autobridge.remote.AutoBridgeCommand
import dev.autobridge.remote.AutoBridgeCommandBus
import dev.autobridge.remote.CommandHistoryStore
import dev.autobridge.remote.CommandSource

/** One Quick Launch tile as the view layer needs it. */
data class HomeTileUi(val title: String, val icon: Int, val accent: Int, val onClick: () -> Unit)

/**
 * Body of the phone Home (below the AutoBridge header, above the mini player):
 * Android Auto status, Quick Launch, Send to Car and Recent.
 *
 * Send to Car is the same [CommandField] as the Control tab, so a URL, a search query or plain
 * text all go through the one [dev.autobridge.remote.CommandParser] / command bus. Recent is read
 * from [CommandHistoryStore], the single command history.
 */
@Composable
fun HomeDashboard(
    tiles: List<HomeTileUi>,
    onOpenConnection: () -> Unit,
    onEditQuickLaunch: () -> Unit,
    onOpenController: () -> Unit,
    onMirror: () -> Unit,
    onBridgeDuo: (() -> Unit)?
) {
    val history by CommandHistoryStore.entries.collectAsState()
    val lastResult = rememberLastCommandResult()
    var lastSubmittedText by remember { mutableStateOf("") }
    val recents = PhoneHomeLayout.recentSends(history)

    Column(
        Modifier.fillMaxWidth().background(ComposeTokens.Ink).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AndroidAutoStatusCard(
            title = stringResource(R.string.control_android_auto),
            onClick = onOpenConnection,
            showPill = true,
            actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardActionButton(
                        stringResource(R.string.home_mirror_action),
                        primary = true,
                        onClick = onMirror,
                        modifier = Modifier.weight(1f)
                    )
                    if (onBridgeDuo != null) {
                        CardActionButton(
                            stringResource(R.string.home_bridge_duo_action),
                            primary = false,
                            onClick = onBridgeDuo,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.home_quick_launch), Modifier.weight(1f))
            TextButton(onClick = onEditQuickLaunch) {
                Text(stringResource(R.string.home_quick_launch_edit), color = ComposeTokens.Accent, fontSize = 13.sp)
            }
        }
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile -> QuickLaunchTile(tile, Modifier.weight(1f)) }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.home_send_to_car), Modifier.weight(1f))
            // The full controller: queue, recents, favorites and the transport bar. The field
            // below stays because one-shot sending is the common case and should not need a
            // navigation step; this is the way in when the driver wants to manage what is queued.
            TextButton(onClick = onOpenController) {
                Text(stringResource(R.string.home_open_controller), color = ComposeTokens.Accent, fontSize = 13.sp)
            }
        }
        CommandField(
            placeholder = stringResource(R.string.home_send_placeholder),
            onSubmitted = { lastSubmittedText = it }
        )
        lastResult.value?.let {
            ResultBanner(
                it,
                onRetry = { submitCommand(lastSubmittedText) },
                onOpenConnection = onOpenConnection
            )
        }

        if (recents.isNotEmpty()) {
            SectionLabel(stringResource(R.string.home_recent))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recents.forEach { recent ->
                    val sendAgainDescription = stringResource(R.string.home_recent_send_again, recent.label)
                    Surface(
                        onClick = {
                            AutoBridgeCommandBus.send(
                                AutoBridgeCommand(type = recent.type, payload = recent.payload, source = CommandSource.MOBILE)
                            )
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = ComposeTokens.Surface,
                        modifier = Modifier.border(1.dp, ComposeTokens.Hairline, RoundedCornerShape(18.dp))
                            .semantics { contentDescription = sendAgainDescription }
                    ) {
                        Text(
                            // Leading glyph marks the chip as "tap to send again", not just a log
                            // of what happened — same resend glyph used elsewhere in the app.
                            "↻ ${recent.label}",
                            color = ComposeTokens.Text,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Mirror / Bridge Duo button on the Home Android Auto card. [primary] fills with the accent. */
@Composable
private fun CardActionButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (primary) ComposeTokens.Accent else ComposeTokens.SurfaceRaised,
        modifier = modifier.height(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (primary) ComposeTokens.Ink else ComposeTokens.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuickLaunchTile(tile: HomeTileUi, modifier: Modifier) {
    val accent = Color(tile.accent)
    Surface(
        onClick = tile.onClick,
        shape = RoundedCornerShape(18.dp),
        color = ComposeTokens.Surface,
        modifier = modifier.height(96.dp).semantics { contentDescription = tile.title }
    ) {
        Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(42.dp)
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(tile.icon), contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Text(
                tile.title,
                color = ComposeTokens.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
