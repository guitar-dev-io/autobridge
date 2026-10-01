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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onEditQuickLaunch: () -> Unit
) {
    val history by CommandHistoryStore.entries.collectAsState()
    val lastResult = rememberLastCommandResult()
    val recents = PhoneHomeLayout.recentSends(history)

    Column(
        Modifier.fillMaxWidth().background(ComposeTokens.Ink).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AndroidAutoStatusCard(title = "Android Auto", onClick = onOpenConnection)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Quick Launch", Modifier.weight(1f))
            TextButton(onClick = onEditQuickLaunch) { Text("Edit", color = ComposeTokens.Accent, fontSize = 13.sp) }
        }
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile -> QuickLaunchTile(tile, Modifier.weight(1f)) }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }

        SectionLabel("Send to Car")
        CommandField(placeholder = "Search or paste URL, or type text…")
        lastResult.value?.let { ResultBanner(it) }

        if (recents.isNotEmpty()) {
            SectionLabel("Recent")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recents.forEach { recent ->
                    Surface(
                        onClick = {
                            AutoBridgeCommandBus.send(
                                AutoBridgeCommand(type = recent.type, payload = recent.payload, source = CommandSource.MOBILE)
                            )
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = ComposeTokens.Surface,
                        modifier = Modifier.border(1.dp, ComposeTokens.Hairline, RoundedCornerShape(18.dp))
                            .semantics { contentDescription = "Send ${recent.label} again" }
                    ) {
                        Text(
                            recent.label,
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
