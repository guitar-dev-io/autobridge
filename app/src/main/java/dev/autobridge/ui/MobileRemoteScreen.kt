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

private val AccentGreen = Color(0xff2ee879)
private val AccentAmber = Color(0xffffbf5f)
private val CardColor = Color(0xff0d2029)
private val CardAltColor = Color(0xff122b37)
private val BorderColor = Color(0xff1f3a47)

/**
 * Tabs of the Mobile Remote (spec §18). Hosted by MainActivity so it slots into the existing phone
 * navigation without replacing it.
 */
enum class RemoteTab { HOME, REMOTE, COMMANDS, SETTINGS }

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

    Column(Modifier.fillMaxSize().background(Color(0xff07131b))) {
        RemoteHeader(state.androidAutoConnected, onSettings = { onSelectTab(RemoteTab.SETTINGS) })

        Box(Modifier.weight(1f)) {
            when (tab) {
                RemoteTab.HOME -> HomeTabContent(state)
                RemoteTab.REMOTE -> RemoteTabContent(context, state)
                RemoteTab.COMMANDS -> CommandsTabContent(context)
                RemoteTab.SETTINGS -> SettingsTabContent(context)
            }
        }

        RemoteBottomNav(tab, onSelectTab)
    }
}

@Composable
private fun RemoteHeader(connected: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("AutoBridge", color = Color(0xfff1f6fb), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Your Car. Smarter.", color = Color(0xff8ea5b5), fontSize = 13.sp)
        }
        TextButton(onClick = onSettings) { Text("⚙", fontSize = 20.sp, color = Color(0xff159cff)) }
    }
    val statusColor = if (connected) AccentGreen else Color(0xff8ea5b5)
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
        Modifier.fillMaxSize().verticalScrollCompat(scroll).padding(16.dp),
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
        Text(state.currentStatusLabel(), color = Color(0xff159cff), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
        Text(detail, color = Color(0xff8ea5b5), fontSize = 13.sp)
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
        Modifier.fillMaxSize().verticalScrollCompat(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionLabel("พิมพ์คำสั่งหรือข้อความ")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                placeholder = { Text("เปิด google.com", color = Color(0xff5f7787)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    submitCommand(commandText); commandText = ""
                }),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { submitCommand(commandText); commandText = "" }) {
                Text("➤", fontSize = 20.sp, color = Color(0xff159cff))
            }
        }

        lastResult?.let { ResultBanner(it) }

        SectionLabel("คำสั่งด่วน")
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

        SectionLabel("สถานะปัจจุบัน")
        CurrentStatusCard(state)

        SectionLabel("ส่งข้อความไปยังหน้าจอรถ")
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    placeholder = { Text("ภูสอยดาว", color = Color(0xff5f7787)) },
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
                }) { Text("Send", color = Color(0xff159cff)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.autoSubmitText,
                    onCheckedChange = { RemoteSettingsStore.update(context) { s -> s.copy(autoSubmitText = it) } }
                )
                Text("ส่งแล้วกด Enter อัตโนมัติ", color = Color(0xff8ea5b5), fontSize = 13.sp)
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
    Surface(color = if (ok) Color(0xff10331f) else Color(0xff3a1414), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (ok) "✓ คำสั่งสำเร็จ" else "✕ ไม่สามารถทำคำสั่งได้",
                color = if (ok) AccentGreen else Color(0xffff8a8a),
                fontWeight = FontWeight.SemiBold
            )
            Text(result.message, color = Color(0xffc7d6e0), fontSize = 13.sp)
        }
    }
}

/* ----------------------------- COMMANDS (HISTORY) TAB ----------------------------- */

@Composable
private fun CommandsTabContent(context: android.content.Context) {
    val history by CommandHistoryStore.entries.collectAsState()
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScrollCompat(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Recent Commands", Modifier.weight(1f))
            TextButton(onClick = { CommandHistoryStore.clear(context) }) {
                Text("Clear", color = Color(0xff159cff))
            }
        }
        if (history.isEmpty()) {
            Text("ยังไม่มีคำสั่งล่าสุด", color = Color(0xff8ea5b5), fontSize = 13.sp)
        }
        history.forEach { entry ->
            Card(onClick = {
                // Run again through the same bus (new id so dedup does not block the re-run).
                AutoBridgeCommandBus.send(
                    AutoBridgeCommand(type = entry.type, payload = entry.payload, source = CommandSource.MOBILE)
                )
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry.success) "✓" else "✕", color = if (entry.success) AccentGreen else Color(0xffff8a8a))
                    Text(
                        entry.label,
                        color = Color(0xfff1f6fb),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 10.dp).weight(1f)
                    )
                    Text(formatTime(entry.timestamp), color = Color(0xff8ea5b5), fontSize = 12.sp)
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
        Modifier.fillMaxSize().verticalScrollCompat(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SectionLabel("AutoBridge Remote Settings")
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

@Composable
private fun RemoteBottomNav(tab: RemoteTab, onSelect: (RemoteTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xff091a23)).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        NavItem("⌂", "Home", tab == RemoteTab.HOME) { onSelect(RemoteTab.HOME) }
        NavItem("➤", "Remote", tab == RemoteTab.REMOTE) { onSelect(RemoteTab.REMOTE) }
        NavItem("≣", "Commands", tab == RemoteTab.COMMANDS) { onSelect(RemoteTab.COMMANDS) }
        NavItem("⚙", "Settings", tab == RemoteTab.SETTINGS) { onSelect(RemoteTab.SETTINGS) }
    }
}

@Composable
private fun NavItem(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val color = if (active) Color(0xff159cff) else Color(0xff8ea5b5)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickableCompat(onClick).padding(vertical = 4.dp)
    ) {
        Text(icon, color = color, fontSize = 18.sp)
        Text(label, color = color, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
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
            Text(icon, fontSize = 22.sp)
            Text(label, color = Color(0xfff1f6fb), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color(0xff8ea5b5), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun StatusLine(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) AccentGreen else Color(0xff8ea5b5))
        Text(label, color = Color(0xfff1f6fb), fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Text(value, color = Color(0xff8ea5b5), fontSize = 13.sp)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xfff1f6fb), fontSize = 14.sp, modifier = Modifier.weight(1f))
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
