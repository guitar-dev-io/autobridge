package dev.autobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
 * Android Auto status, Send to Car with its recent sends, then Quick Launch.
 *
 * Send to Car is the same [CommandField] as the Control tab, so a URL, a search query or plain
 * text all go through the one [dev.autobridge.remote.CommandParser] / command bus. Recent is read
 * from [CommandHistoryStore], the single command history.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeDashboard(
    tiles: List<HomeTileUi>,
    onOpenConnection: () -> Unit,
    onEditQuickLaunch: () -> Unit,
    onOpenController: () -> Unit,
    onMirror: () -> Unit,
    onBridgeDuo: (() -> Unit)?,
    /** A newer release's version name when one was found; null hides the update card. */
    updateVersion: String? = null,
    onOpenUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {}
) {
    val history by CommandHistoryStore.entries.collectAsState()
    val lastResult = rememberLastCommandResult()
    var lastSubmittedText by remember { mutableStateOf("") }
    val recents = PhoneHomeLayout.recentSends(history)

    Column(
        Modifier.fillMaxWidth().background(ComposeTokens.Ink).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (updateVersion != null) UpdateCard(updateVersion, onOpenUpdate, onDismissUpdate)

        AndroidAutoStatusCard(
            title = stringResource(R.string.control_android_auto),
            onClick = onOpenConnection,
            showPill = true,
            homeStyle = true,
            actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardActionButton(
                        stringResource(R.string.home_mirror_action),
                        icon = R.drawable.ic_tile_mirror,
                        primary = true,
                        onClick = onMirror,
                        modifier = Modifier.weight(1f)
                    )
                    if (onBridgeDuo != null) {
                        CardActionButton(
                            stringResource(R.string.home_bridge_duo_action),
                            icon = R.drawable.ic_browser_split,
                            primary = false,
                            onClick = onBridgeDuo,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        )

        // Send to Car comes before Quick Launch: sending something to the car is what Home is
        // opened for most, so the field sits right under the connection card.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
            filled = true,
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
            // Recent sends as chips right under the field, so sending the same page again is one
            // tap from where it was typed.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recents.forEach { recent -> RecentChip(recent) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
    }
}

/** "Version X is available": opens the update dialog; ✕ hides it for that release. */
@Composable
private fun UpdateCard(version: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val dismissDescription = stringResource(R.string.home_update_dismiss)
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        color = ComposeTokens.Accent.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth().border(1.dp, ComposeTokens.Accent.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).background(ComposeTokens.Accent, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { Text("⬇", color = ComposeTokens.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    stringResource(R.string.home_update_title, version),
                    color = ComposeTokens.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(stringResource(R.string.home_update_caption), color = ComposeTokens.TextMuted, fontSize = 12.5.sp)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = dismissDescription }) {
                Text("✕", color = ComposeTokens.TextMuted, fontSize = 16.sp)
            }
        }
    }
}

/** One recent send under the field: outlined pill, tap sends the same command again. */
@Composable
private fun RecentChip(recent: PhoneHomeLayout.RecentSend) {
    val sendAgainDescription = stringResource(R.string.home_recent_send_again, recent.label)
    Surface(
        onClick = {
            AutoBridgeCommandBus.send(
                AutoBridgeCommand(type = recent.type, payload = recent.payload, source = CommandSource.MOBILE)
            )
        },
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = Modifier.height(36.dp)
            .border(1.dp, ComposeTokens.Hairline, RoundedCornerShape(18.dp))
            .semantics { contentDescription = sendAgainDescription }
    ) {
        Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                recent.label,
                color = RecentChipText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val RecentChipText = Color(0xFFD5DAE0)
private val DuoButtonColor = Color(0xFF22344F)
private val DuoButtonText = Color(0xFFDCEBFF)

/** Mirror / Bridge Duo button on the Home Android Auto card. [primary] fills with the accent. */
@Composable
private fun CardActionButton(label: String, icon: Int, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (primary) ComposeTokens.Ink else DuoButtonText
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (primary) ComposeTokens.Accent else DuoButtonColor,
        modifier = modifier.height(48.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    color = tint,
                    fontSize = 15.sp,
                    fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun QuickLaunchTile(tile: HomeTileUi, modifier: Modifier) {
    val accent = Color(tile.accent)
    Surface(
        onClick = tile.onClick,
        shape = RoundedCornerShape(20.dp),
        color = ComposeTokens.Surface,
        modifier = modifier.height(100.dp).semantics { contentDescription = tile.title }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(40.dp).background(accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(tile.icon), contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Text(
                tile.title,
                color = ComposeTokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
