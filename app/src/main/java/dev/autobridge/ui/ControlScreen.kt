package dev.autobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.R
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.remote.AutoBridgeCommand
import dev.autobridge.remote.AutoBridgeCommandBus
import dev.autobridge.remote.AutoBridgeState
import dev.autobridge.remote.AutoBridgeStateRepository
import dev.autobridge.remote.CommandHistoryStore
import dev.autobridge.remote.CommandParser
import dev.autobridge.remote.CommandResult
import dev.autobridge.remote.CommandSource
import dev.autobridge.remote.CommandStatus
import dev.autobridge.remote.CommandType
import dev.autobridge.remote.QuickCommandStore
import dev.autobridge.remote.RemoteScreen
import dev.autobridge.remote.RemoteSettingsStore
import dev.autobridge.remote.TextInjectionController

// Every colour here comes from ComposeTokens, which mirrors AutoBridgeDesign, so these screens sit
// on the same surface stack as the launcher, the library and the player.
private val AccentGreen = ComposeTokens.Ok
private val CardColor = ComposeTokens.Surface
private val CardAltColor = ComposeTokens.SurfaceRaised
private val Accent = ComposeTokens.Accent
private val TextPrimary = ComposeTokens.Text
private val TextMuted = ComposeTokens.TextMuted

/**
 * The Control tab (formerly "Remote"). One scrolling page instead of the old
 * Status / Control / History / Options sub-tabs:
 *
 *  - Android Auto status (the old Status tab, reduced to the user-facing line),
 *  - command / URL input and Quick Actions (the old Control tab),
 *  - Current Screen and Send Text to Car,
 *  - History behind the header button ([CommandHistoryScreen]),
 *  - Options moved to Settings > Agent & Commands ([AgentCommandsScreen]).
 *
 * Everything still flows through the SAME [AutoBridgeCommandBus] / router as Android Auto and the
 * Agent; this screen only renders shared state and submits commands.
 */
@Composable
fun ControlScreen(
    context: android.content.Context,
    onOpenHistory: () -> Unit,
    onOpenConnection: () -> Unit
) {
    val state by AutoBridgeStateRepository.state.collectAsState()
    val quickCommands by QuickCommandStore.items.collectAsState()
    val settings by RemoteSettingsStore.settings.collectAsState()
    var sendText by remember { mutableStateOf("") }
    val lastResult = rememberLastCommandResult()

    Column(Modifier.fillMaxSize().background(ComposeTokens.Ink)) {
        PhoneHeader(
            title = "Control",
            action = HeaderButton("↺", "History", onOpenHistory)
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AndroidAutoStatusCard(title = "Android Auto", onClick = onOpenConnection)

            SectionLabel("Command or URL")
            CommandField(placeholder = "open google.com")
            lastResult.value?.let { ResultBanner(it) }

            SectionLabel("Quick Actions")
            // Same persisted, user-customisable quick commands as before, all routed through the bus.
            quickCommands.chunked(3).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { qc ->
                        QuickCard(icon = qc.icon, label = qc.label, modifier = Modifier.weight(1f)) {
                            AutoBridgeCommandBus.send(
                                AutoBridgeCommand(type = qc.type, payload = qc.payload, source = CommandSource.MOBILE)
                            )
                        }
                    }
                    repeat(3 - rowItems.size) { Box(Modifier.weight(1f)) }
                }
            }

            SectionLabel("Current Screen")
            CurrentScreenCard(state)

            SectionLabel("Send Text to Car")
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
}

/* ----------------------------- HISTORY (child page of Control) ----------------------------- */

/** The old History tab as its own page, opened from the Control header. Same store, same re-run. */
@Composable
fun CommandHistoryScreen(context: android.content.Context, onBack: () -> Unit) {
    val history by CommandHistoryStore.entries.collectAsState()
    Column(Modifier.fillMaxSize().background(ComposeTokens.Ink)) {
        PhoneHeader(
            title = "History",
            subtitle = "Recent commands",
            onBack = onBack,
            action = HeaderButton("⌫", "Clear history") { CommandHistoryStore.clear(context) }
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (history.isEmpty()) Text("No commands yet.", color = TextMuted, fontSize = 13.sp)
            history.forEach { entry ->
                Card(onClick = {
                    // Run again through the same bus (new id so dedup does not block the re-run).
                    AutoBridgeCommandBus.send(
                        AutoBridgeCommand(type = entry.type, payload = entry.payload, source = CommandSource.MOBILE)
                    )
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (entry.success) "✓" else "✕",
                            color = if (entry.success) AccentGreen else ComposeTokens.Danger
                        )
                        Text(
                            entry.label,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 10.dp).weight(1f)
                        )
                        Text(formatTime(entry.timestamp), color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/* ----------------------------- AGENT & COMMANDS (Settings child) ----------------------------- */

/**
 * Settings > Agent & Commands: the six options that used to be the Remote's Options tab. They
 * still read and write [RemoteSettingsStore] under the same preference keys, so nothing resets.
 */
@Composable
fun AgentCommandsScreen(
    context: android.content.Context,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val settings by RemoteSettingsStore.settings.collectAsState()
    Column(Modifier.fillMaxSize().background(ComposeTokens.Ink)) {
        PhoneHeader(title = "Agent & Commands", subtitle = "Commands, history and automation", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionLabel("Commands")
            Card {
                ToggleRow("Show command confirmation", "Show the result after each command", settings.showConfirmation) {
                    RemoteSettingsStore.update(context) { s -> s.copy(showConfirmation = it) }
                }
                ToggleRow("Haptic feedback", "Vibrate when a command is sent", settings.hapticFeedback) {
                    RemoteSettingsStore.update(context) { s -> s.copy(hapticFeedback = it) }
                }
                ToggleRow("Auto submit text", "Press Enter after Send Text to Car", settings.autoSubmitText) {
                    RemoteSettingsStore.update(context) { s -> s.copy(autoSubmitText = it) }
                }
            }
            SectionLabel("Agent")
            Card {
                ToggleRow("Prefer Agent for unknown text", "Hand unrecognised text to the Agent", settings.preferAgentForUnknown) {
                    RemoteSettingsStore.update(context) { s -> s.copy(preferAgentForUnknown = it) }
                }
                ToggleRow("Resume last feature", "Reopen what was running last time", settings.resumeLastFeature) {
                    RemoteSettingsStore.update(context) { s -> s.copy(resumeLastFeature = it) }
                }
            }
            SectionLabel("History")
            Card {
                ToggleRow("Remember command history", "Keep the last 20 commands", settings.rememberHistory) {
                    RemoteSettingsStore.update(context) { s -> s.copy(rememberHistory = it) }
                }
            }
            Card(onClick = onOpenHistory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Command history", color = TextPrimary, fontSize = 15.sp)
                        Text("View and re-run recent commands", color = TextMuted, fontSize = 12.sp)
                    }
                    Text("›", color = TextMuted, fontSize = 22.sp)
                }
            }
        }
    }
}

/* ----------------------------- SHARED PIECES ----------------------------- */

/**
 * Android Auto connection card. Reads [RuntimeContextStore] directly — the single source of truth
 * for connected / parked — and words it with [ConnectionStatusText], so Home, Control and
 * Car & Connection always agree and never show internal values like REAL_CAR.
 */
@Composable
fun AndroidAutoStatusCard(title: String, caption: String? = null, onClick: (() -> Unit)?) {
    val runtime by RuntimeContextStore.context.collectAsState()
    val status = ConnectionStatusText.of(runtime)
    val body: @Composable () -> Unit = {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp)
                    .background(ComposeTokens.Ok.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .border(1.dp, ComposeTokens.Ok.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.ic_tile_car),
                    contentDescription = null,
                    tint = if (status.connected) AccentGreen else TextMuted,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    (if (status.connected) "● " else "○ ") + status.summary,
                    color = if (status.connected) AccentGreen else TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.semantics { contentDescription = "$title: ${status.summary}" }
                )
                if (!caption.isNullOrBlank()) Text(caption, color = TextMuted, fontSize = 12.sp)
            }
            if (onClick != null) Text("›", color = TextMuted, fontSize = 22.sp)
        }
    }
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Surface(onClick = onClick, color = CardColor, shape = shape, modifier = Modifier.fillMaxWidth()) { body() }
    } else {
        Surface(color = CardColor, shape = shape, modifier = Modifier.fillMaxWidth()) { body() }
    }
}

/**
 * Command / URL / search / text box. Submits through [submitCommand] — the one command path —
 * so Home's "Send to Car" and Control's command field behave identically.
 */
@Composable
fun CommandField(placeholder: String, onSubmitted: (String) -> Unit = {}) {
    var text by remember { mutableStateOf("") }
    fun send() {
        val sent = text
        if (submitCommand(sent)) {
            onSubmitted(sent)
            text = ""
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(placeholder, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = { send() },
            color = Accent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(52.dp).semantics { contentDescription = "Send" }
        ) {
            Box(contentAlignment = Alignment.Center) { Text("➤", fontSize = 20.sp, color = ComposeTokens.Ink) }
        }
    }
}

/**
 * Parses free text with the shared [CommandParser] (URL, search, command, or plain text for the
 * Agent) and sends it on the bus. Returns false when there was nothing to send.
 */
fun submitCommand(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val command = CommandParser.parse(trimmed, CommandSource.MOBILE) ?: return false
    AutoBridgeCommandBus.send(command)
    return true
}

/** Latest SUCCESS / FAILED result from the bus, for the inline "✓ / ✕" feedback line. */
@Composable
fun rememberLastCommandResult(): androidx.compose.runtime.MutableState<CommandResult?> {
    val last = remember { mutableStateOf<CommandResult?>(null) }
    LaunchedEffect(Unit) {
        AutoBridgeCommandBus.results.collect { result ->
            if (result.status == CommandStatus.SUCCESS || result.status == CommandStatus.FAILED) {
                last.value = result
            }
        }
    }
    return last
}

@Composable
fun ResultBanner(result: CommandResult) {
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

/** A trailing round header button (glyph + accessible label). */
data class HeaderButton(val glyph: String, val description: String, val onClick: () -> Unit)

/**
 * Compose twin of [AutoBridgeDesign.header]: same paddings, 26sp bold title, quiet caption, a
 * circular ‹ only when the page is a child, and an optional round action on the right.
 */
@Composable
fun PhoneHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    action: HeaderButton? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 8.dp, top = 14.dp, end = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            RoundGlyph("‹", "Back", 30, onBack)
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action != null) RoundGlyph(action.glyph, action.description, 20, action.onClick)
    }
}

@Composable
private fun RoundGlyph(glyph: String, description: String, size: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CardColor,
        modifier = Modifier.size(44.dp).semantics { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) { Text(glyph, color = TextPrimary, fontSize = size.sp) }
    }
}

@Composable
private fun CurrentScreenCard(state: AutoBridgeState) {
    Card {
        Text(state.currentStatusLabel(), color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val detail = when (state.currentScreen) {
            RemoteScreen.BROWSER -> state.currentUrl ?: state.browserTitle ?: "—"
            RemoteScreen.MIRROR -> state.mirrorStatus.name.lowercase().replaceFirstChar { it.uppercase() }
            RemoteScreen.MEDIA -> buildString {
                append(state.mediaTitle ?: "—")
                append(if (state.mediaPlaying) " · Playing" else " · Paused")
            }
            RemoteScreen.AGENT -> if (state.agentReady) "Ready" else "Busy"
            // The title already says "Not connected"; the detail says what to do about it.
            else -> if (state.androidAutoConnected) "Idle" else "Open AutoBridge on the car screen"
        }
        Text(detail, color = TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = TextMuted,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(top = 6.dp, start = 4.dp)
    )
}

@Composable
private fun ToggleRow(label: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 15.sp)
            Text(caption, color = TextMuted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
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

private fun formatTime(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
