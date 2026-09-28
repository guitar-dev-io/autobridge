package dev.autobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.remote.AutoBridgeCommand
import dev.autobridge.remote.AutoBridgeCommandBus
import dev.autobridge.remote.AutoBridgeStateRepository
import dev.autobridge.remote.CommandHistoryStore
import dev.autobridge.remote.CommandParser
import dev.autobridge.remote.CommandResult
import dev.autobridge.remote.CommandSource
import dev.autobridge.remote.CommandStatus
import dev.autobridge.remote.CommandType
import dev.autobridge.remote.MirrorStatus
import dev.autobridge.remote.QuickCommandStore
import dev.autobridge.remote.RemoteScreen
import dev.autobridge.remote.RemoteSettingsStore
import dev.autobridge.remote.TextInjectionController

// Every colour here comes from ComposeTokens, which mirrors AutoBridgeDesign, so this screen sits
// on the same surface stack as the launcher, the library and the player.
private val AccentGreen = ComposeTokens.Ok
private val CardColor = ComposeTokens.Surface
private val CardAltColor = ComposeTokens.SurfaceRaised
private val Accent = ComposeTokens.Accent
private val TextPrimary = ComposeTokens.Text
private val TextMuted = ComposeTokens.TextMuted

/**
 * Tabs of the Mobile Remote (spec §18). Hosted by MainActivity so it slots into the existing phone
 * navigation without replacing it.
 */
enum class RemoteTab(val label: String) {
    HOME("Status"),
    REMOTE("Control"),
    COMMANDS("History"),
    SETTINGS("Options")
}

/**
 * The AutoBridge Mobile Remote. Everything the user does here flows through the SAME
 * [AutoBridgeCommandBus] / router as Android Auto and the Agent — this screen only renders shared
 * state and submits commands, so it cannot bypass FeaturePolicy or duplicate feature logic.
 */
@Composable
fun MobileRemoteScreen(
    context: android.content.Context,
    tab: RemoteTab,
    onSelectTab: (RemoteTab) -> Unit
) {
    val state by AutoBridgeStateRepository.state.collectAsState()

    Column(Modifier.fillMaxSize().background(ComposeTokens.Ink)) {
        RemoteHeader(state.androidAutoConnected, onSettings = { onSelectTab(RemoteTab.SETTINGS) })

        // These are sub-tabs of the Remote feature, not app-level destinations. They used to be a
        // second bottom bar, which stacked directly on top of MainActivity's global navigation:
        // two bars, both starting with "Home / Remote", meaning different things. As a segmented
        // control under the header the hierarchy reads correctly and the content gets its height
        // back.
        RemoteTabStrip(tab, onSelectTab)

        Box(Modifier.weight(1f)) {
            when (tab) {
                RemoteTab.HOME -> HomeTabContent(state)
                RemoteTab.REMOTE -> RemoteTabContent(context, state)
                RemoteTab.COMMANDS -> CommandsTabContent(context)
                RemoteTab.SETTINGS -> SettingsTabContent(context)
            }
        }
    }
}

@Composable
private fun RemoteHeader(connected: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("AutoBridge", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Your Car. Smarter.", color = TextMuted, fontSize = 13.sp)
        }
        TextButton(onClick = onSettings) { Text("⚙", fontSize = 20.sp, color = Accent) }
    }
    val statusColor = if (connected) AccentGreen else TextMuted
    val statusText = if (connected) "● Connected to Car · Android Auto Active" else "○ Android Auto not connected"
    Text(
        statusText,
        color = statusColor,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
    )
}

/* ----------------------------- HOME TAB ----------------------------- */

@Composable
private fun HomeTabContent(state: dev.autobridge.remote.AutoBridgeState) {
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScrollCompat(scroll)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionLabel("CONNECTION")
        Card {
            StatusLine("Phone", if (state.androidAutoConnected) "connected" else "disconnected", state.androidAutoConnected)
            StatusLine("Android Auto", if (state.androidAutoConnected) "connected" else "not connected", state.androidAutoConnected)
            StatusLine("Mirror", state.mirrorStatus.name.lowercase(), state.mirrorStatus == MirrorStatus.ACTIVE)
        }
        SectionLabel("CURRENT SCREEN")
        CurrentStatusCard(state)
    }
}

@Composable
private fun CurrentStatusCard(state: dev.autobridge.remote.AutoBridgeState) {
    Card {
        Text(state.currentStatusLabel(), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val detail = when (state.currentScreen) {
            RemoteScreen.BROWSER -> state.currentUrl ?: state.browserTitle ?: "—"
            RemoteScreen.MIRROR -> state.mirrorStatus.name
            RemoteScreen.MEDIA -> buildString {
                append(state.mediaTitle ?: "—")
                append(if (state.mediaPlaying) " · Playing" else " · Paused")
            }
            RemoteScreen.AGENT -> if (state.agentReady) "Ready" else "Busy"
            else -> if (state.androidAutoConnected) "Idle" else "Not connected"
        }
        Text(detail, color = TextMuted, fontSize = 13.sp)
    }
}

/* ----------------------------- REMOTE TAB ----------------------------- */

@Composable
private fun RemoteTabContent(context: android.content.Context, state: dev.autobridge.remote.AutoBridgeState) {
    val quickCommands by QuickCommandStore.items.collectAsState()
    val settings by RemoteSettingsStore.settings.collectAsState()
    var commandText by remember { mutableStateOf("") }
    var sendText by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<CommandResult?>(null) }

    // Observe acknowledgements to show the "✓ / ✕" feedback line.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        AutoBridgeCommandBus.results.collect { result ->
            if (result.status == CommandStatus.SUCCESS || result.status == CommandStatus.FAILED) {
                lastResult = result
            }
        }
    }

    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScrollCompat(scroll)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionLabel("COMMAND OR TEXT")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                placeholder = { Text("open google.com", color = TextMuted) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    submitCommand(commandText); commandText = ""
                }),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { submitCommand(commandText); commandText = "" }) {
                Text("➤", fontSize = 20.sp, color = Accent)
            }
        }

        lastResult?.let { ResultBanner(it) }

        SectionLabel("QUICK COMMANDS")
        // Quick commands laid out in rows of 4, all routed through the same bus.
        quickCommands.chunked(4).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { qc ->
                    QuickCard(
                        icon = qc.icon,
                        label = qc.label,
                        modifier = Modifier.weight(1f)
                    ) {
                        AutoBridgeCommandBus.send(
                            AutoBridgeCommand(type = qc.type, payload = qc.payload, source = CommandSource.MOBILE)
                        )
                    }
                }
                repeat(4 - rowItems.size) { Box(Modifier.weight(1f)) }
            }
        }

        SectionLabel("CURRENT SCREEN")
        CurrentStatusCard(state)

        SectionLabel("SEND TEXT TO THE CAR")
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    placeholder = { Text("Type text to send", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    AutoBridgeCommandBus.send(
                        AutoBridgeCommand(
                            type = CommandType.SEND_TEXT_TO_SCREEN,
                            payload = sendText,
                            source = CommandSource.MOBILE,
                            extras = mapOf(
                                "target" to TextInjectionController.Target.BROWSER_SEARCH.name,
                                "autoSubmit" to settings.autoSubmitText.toString()
                            )
                        )
                    )
                    sendText = ""
                }) { Text("Send", color = Accent) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.autoSubmitText,
                    onCheckedChange = { RemoteSettingsStore.update(context) { s -> s.copy(autoSubmitText = it) } }
                )
                Text("Press Enter automatically after sending", color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

private fun submitCommand(text: String) {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    val command = CommandParser.parse(trimmed, CommandSource.MOBILE) ?: return
    AutoBridgeCommandBus.send(command)
}

@Composable
private fun ResultBanner(result: CommandResult) {
    val ok = result.status == CommandStatus.SUCCESS
    Surface(color = CardAltColor, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (ok) "Command sent" else "Command failed",
                color = if (ok) AccentGreen else ComposeTokens.Danger,
                fontWeight = FontWeight.SemiBold
            )
            Text(result.message, color = TextMuted, fontSize = 13.sp)
        }
    }
}

/* ----------------------------- COMMANDS (HISTORY) TAB ----------------------------- */

@Composable
private fun CommandsTabContent(context: android.content.Context) {
    val history by CommandHistoryStore.entries.collectAsState()
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScrollCompat(scroll)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("RECENT COMMANDS", Modifier.weight(1f))
            TextButton(onClick = { CommandHistoryStore.clear(context) }) {
                Text("Clear", color = Accent)
            }
        }
        if (history.isEmpty()) {
            Text("No commands yet.", color = TextMuted, fontSize = 13.sp)
        }
        history.forEach { entry ->
            Card(onClick = {
                // Run again through the same bus (new id so dedup does not block the re-run).
                AutoBridgeCommandBus.send(
                    AutoBridgeCommand(type = entry.type, payload = entry.payload, source = CommandSource.MOBILE)
                )
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry.success) "✓" else "✕", color = if (entry.success) AccentGreen else ComposeTokens.Danger)
                    Text(
                        entry.label,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 10.dp).weight(1f)
                    )
                    Text(formatTime(entry.timestamp), color = TextMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

/* ----------------------------- SETTINGS TAB ----------------------------- */

@Composable
private fun SettingsTabContent(context: android.content.Context) {
    val settings by RemoteSettingsStore.settings.collectAsState()
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScrollCompat(scroll)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SectionLabel("REMOTE OPTIONS")
        ToggleRow("Show command confirmation", settings.showConfirmation) {
            RemoteSettingsStore.update(context) { s -> s.copy(showConfirmation = it) }
        }
        ToggleRow("Haptic feedback", settings.hapticFeedback) {
            RemoteSettingsStore.update(context) { s -> s.copy(hapticFeedback = it) }
        }
        ToggleRow("Auto submit text", settings.autoSubmitText) {
            RemoteSettingsStore.update(context) { s -> s.copy(autoSubmitText = it) }
        }
        ToggleRow("Remember command history", settings.rememberHistory) {
            RemoteSettingsStore.update(context) { s -> s.copy(rememberHistory = it) }
        }
        ToggleRow("Prefer Agent for unknown text", settings.preferAgentForUnknown) {
            RemoteSettingsStore.update(context) { s -> s.copy(preferAgentForUnknown = it) }
        }
        ToggleRow("Resume last feature", settings.resumeLastFeature) {
            RemoteSettingsStore.update(context) { s -> s.copy(resumeLastFeature = it) }
        }
    }
}

/* ----------------------------- SHARED WIDGETS ----------------------------- */

/** Segmented control for the Remote's own tabs, sitting directly under the header. */
@Composable
private fun RemoteTabStrip(tab: RemoteTab, onSelect: (RemoteTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(CardColor, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RemoteTab.entries.forEach { entry ->
            TabChip(entry.label, tab == entry, Modifier.weight(1f)) { onSelect(entry) }
        }
    }
}

@Composable
private fun TabChip(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(
                if (active) Accent else Color.Transparent,
                RoundedCornerShape(11.dp)
            )
            .clickableCompat(onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) ComposeTokens.Ink else TextMuted,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun QuickCard(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = CardAltColor,
        modifier = modifier.height(84.dp)
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(icon, fontSize = 20.sp, color = Accent)
            Text(
                label,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) AccentGreen else TextMuted)
        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Text(value, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Surface(onClick = onClick, color = CardColor, shape = shape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
    } else {
        Surface(color = CardColor, shape = shape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
        }
    }
}

private fun formatTime(ts: Long): String {
    val date = java.util.Date(ts)
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(date)
}

/* Small compatibility helpers so call sites read cleanly. */
private fun Modifier.clickableCompat(onClick: () -> Unit): Modifier =
    this.clickable { onClick() }

private fun Modifier.verticalScrollCompat(state: ScrollState): Modifier =
    this.verticalScroll(state)
