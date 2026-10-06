package dev.autobridge.ui

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.R
import dev.autobridge.bridge.AutoBridgeSessionManager
import dev.autobridge.bridge.BridgePlaybackState
import dev.autobridge.bridge.BridgeSource
import dev.autobridge.bridge.EngineKind
import dev.autobridge.browser.BrowserInputResolver
import dev.autobridge.browser.BrowserResumePoint
import dev.autobridge.browser.SearchEngineStore
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.entertainment.WebBookmarkStore
import dev.autobridge.remote.AutoBridgeCommand
import dev.autobridge.remote.AutoBridgeCommandBus
import dev.autobridge.remote.AutoBridgeStateRepository
import dev.autobridge.remote.CommandFailureReason
import dev.autobridge.remote.CommandHistoryStore
import dev.autobridge.remote.CommandParser
import dev.autobridge.remote.CommandResult
import dev.autobridge.remote.CommandSource
import dev.autobridge.remote.CommandStatus
import dev.autobridge.remote.CommandType
import dev.autobridge.remote.MirrorStatus
import dev.autobridge.remote.QuickCommandStore
import dev.autobridge.remote.RemoteSettingsStore
import dev.autobridge.remotestream.RemoteStreamConfig

// Every colour here comes from ComposeTokens, which mirrors AutoBridgeDesign, so these screens sit
// on the same surface stack as the launcher, the library and the player.
private val AccentGreen = ComposeTokens.Ok
private val CardColor = ComposeTokens.Surface
private val CardAltColor = ComposeTokens.SurfaceRaised
private val Accent = ComposeTokens.Accent
private val TextPrimary = ComposeTokens.Text
private val TextMuted = ComposeTokens.TextMuted

/** Which of the two things the single input row does with what is typed. */
private enum class ControlInputMode { OPEN_SEARCH, TYPE }

/** The sections the Queue/Recent/Favorites group is split into; one is shown at a time. */
private enum class ControlTab(val labelRes: Int) {
    QUEUE(R.string.bridge_controller_tab_queue),
    RECENT(R.string.bridge_controller_tab_recent),
    FAVORITES(R.string.bridge_controller_tab_favorites)
}

/**
 * One Control screen: on-the-car transport, one input with an Open/search ↔ Type-into-car mode,
 * Quick Actions, and Queue / Recent / Favorites — the former Control tab, the Bridge controller
 * dialog and the duplicate "Open controller" Activity, merged into the single screen Home's
 * Android Auto card and the Remote section now both open.
 *
 * The on-the-car card, input, tabs and transport all read and command
 * [AutoBridgeSessionManager] — exactly what the retired Bridge controller used — so nothing about
 * how a link reaches the car changes, only where the controls live. Quick Actions and Disconnect
 * still go through [AutoBridgeCommandBus]: the command types they send (open mirror, reload, stop
 * mirroring…) have no bridge equivalent.
 */
@Composable
fun ControlScreen(
    context: android.content.Context,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenConnection: () -> Unit
) {
    // Mirrors BridgeControllerActivity's onCreate/onResume: idempotent, safe to repeat every time
    // this screen is opened.
    LaunchedEffect(Unit) {
        AutoBridgeSessionManager.initialize(context)
        AutoBridgeSessionManager.refresh()
    }

    val bridgeState by AutoBridgeSessionManager.state.collectAsState()
    val legacyState by AutoBridgeStateRepository.state.collectAsState()
    val quickCommands by QuickCommandStore.items.collectAsState()
    val settings by RemoteSettingsStore.settings.collectAsState()

    var mode by remember { mutableStateOf(ControlInputMode.OPEN_SEARCH) }
    var input by remember { mutableStateOf("") }
    var editingQuickActions by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(ControlTab.QUEUE) }
    // Bumped after any queue/favorite write so the lists below re-read: the queue and favorites
    // are SharedPreferences-backed stores with no flow of their own, the same pattern the retired
    // BridgeControllerActivity used.
    var revision by remember { mutableIntStateOf(0) }

    val queue = remember(revision) { AutoBridgeSessionManager.queueItems(context) }
    val recents = remember(revision) { AutoBridgeSessionManager.recents(context) }
    val favorites = remember(revision) { WebBookmarkStore.list(context) }

    fun sendRaw(raw: String) {
        val url = BrowserInputResolver.resolveBrowserInput(raw, SearchEngineStore.engine(context))
        if (url == null) {
            Toast.makeText(context, R.string.bridge_controller_nothing_to_send, Toast.LENGTH_SHORT).show()
            return
        }
        val result = AutoBridgeSessionManager.sendToCar(
            context,
            BridgeSource(url = url, origin = BridgeSource.Origin.PHONE)
        )
        val message = when (result) {
            is AutoBridgeSessionManager.SendResult.Opened -> context.getString(R.string.bridge_controller_sent)
            AutoBridgeSessionManager.SendResult.Pending -> context.getString(R.string.bridge_controller_queued_for_connect)
            is AutoBridgeSessionManager.SendResult.Refused -> context.getString(result.error.messageRes)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        revision++
    }

    fun sendTyped() {
        val ok = AutoBridgeSessionManager.sendKeyboard(context, input, settings.autoSubmitText)
        Toast.makeText(
            context,
            if (ok) context.getString(R.string.bridge_controller_text_sent)
            else context.getString(R.string.bridge_controller_text_failed),
            Toast.LENGTH_SHORT
        ).show()
        if (ok) input = ""
    }

    fun onSendClicked() {
        if (mode == ControlInputMode.OPEN_SEARCH) {
            sendRaw(input)
            input = ""
        } else {
            sendTyped()
        }
    }

    fun addToQueue(url: String, title: String = "") {
        val size = AutoBridgeSessionManager.queueAdd(context, BridgeSource(url, title))
        Toast.makeText(
            context,
            if (size == null) context.getString(R.string.browser_already_queued)
            else context.getString(R.string.bridge_controller_queued),
            Toast.LENGTH_SHORT
        ).show()
        revision++
    }

    Column(Modifier.fillMaxSize().background(ComposeTokens.Ink)) {
        PhoneHeader(
            title = stringResource(R.string.control_title),
            onBack = onBack,
            action = HeaderButton("↺", stringResource(R.string.control_history_action), onOpenHistory)
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { OnTheCarCard(bridgeState, onOpenConnection) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SegmentChip(
                        stringResource(R.string.control_mode_open_search),
                        selected = mode == ControlInputMode.OPEN_SEARCH,
                        modifier = Modifier.weight(1f)
                    ) { mode = ControlInputMode.OPEN_SEARCH }
                    SegmentChip(
                        stringResource(R.string.control_mode_type),
                        selected = mode == ControlInputMode.TYPE,
                        modifier = Modifier.weight(1f)
                    ) { mode = ControlInputMode.TYPE }
                }
            }

            item {
                BridgeInputField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(
                        if (mode == ControlInputMode.OPEN_SEARCH) R.string.bridge_controller_input_label
                        else R.string.bridge_controller_keyboard_label
                    ),
                    onSend = ::onSendClicked
                )
            }

            if (mode == ControlInputMode.TYPE) {
                item {
                    ToggleRow(
                        stringResource(R.string.control_auto_submit_label),
                        stringResource(R.string.control_auto_submit_hint),
                        settings.autoSubmitText
                    ) { checked -> RemoteSettingsStore.update(context) { s -> s.copy(autoSubmitText = checked) } }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.control_section_quick_actions), Modifier.weight(1f))
                    TextButton(onClick = { editingQuickActions = !editingQuickActions }) {
                        Text(
                            stringResource(
                                if (editingQuickActions) R.string.control_quick_actions_done
                                else R.string.control_quick_actions_edit
                            ),
                            color = Accent,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            // Same persisted, user-customisable quick commands as before, all routed through the bus.
            quickCommands.chunked(3).forEach { rowItems ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { qc ->
                            QuickCard(
                                icon = qc.icon,
                                label = qc.label,
                                removable = editingQuickActions,
                                onRemove = { QuickCommandStore.remove(context, qc.id) },
                                modifier = Modifier.weight(1f)
                            ) {
                                AutoBridgeCommandBus.send(
                                    AutoBridgeCommand(type = qc.type, payload = qc.payload, source = CommandSource.MOBILE)
                                )
                            }
                        }
                        repeat(3 - rowItems.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlTab.entries.forEach { entry ->
                        val label = stringResource(entry.labelRes)
                        SegmentChip(
                            if (entry == ControlTab.QUEUE && queue.isNotEmpty()) "$label · ${queue.size}" else label,
                            selected = tab == entry,
                            modifier = Modifier.weight(1f)
                        ) { tab = entry }
                    }
                }
            }

            when (tab) {
                ControlTab.QUEUE -> {
                    if (queue.isEmpty()) {
                        item { EmptyRow(stringResource(R.string.bridge_controller_queue_empty)) }
                    } else {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    AutoBridgeSessionManager.next(context)
                                    revision++
                                }) {
                                    Text(stringResource(R.string.bridge_controller_play_next), color = Accent)
                                }
                                TextButton(onClick = {
                                    AutoBridgeSessionManager.queueClear(context)
                                    revision++
                                }) {
                                    Text(stringResource(R.string.bridge_controller_clear_queue), color = ComposeTokens.Danger)
                                }
                            }
                        }
                        items(queue, key = { it.url }) { item ->
                            LinkRow(
                                title = item.title.ifBlank { item.url },
                                subtitle = item.url,
                                actionLabel = stringResource(R.string.bridge_controller_remove),
                                onClick = { sendRaw(item.url) },
                                onAction = {
                                    AutoBridgeSessionManager.queueRemove(context, item.url)
                                    revision++
                                }
                            )
                        }
                    }
                }

                ControlTab.RECENT -> {
                    if (recents.isEmpty()) {
                        item { EmptyRow(stringResource(R.string.bridge_controller_recent_empty)) }
                    } else {
                        items(recents, key = { "${it.kind}:${it.data ?: it.title}:${it.timestampMs}" }) { entry ->
                            val url = entry.data
                            LinkRow(
                                title = entry.title,
                                subtitle = entry.subtitle ?: url.orEmpty(),
                                actionLabel = stringResource(R.string.bridge_controller_queue_action),
                                onClick = { url?.let { sendRaw(it) } },
                                onAction = { url?.let { addToQueue(it, entry.title) } }
                            )
                        }
                    }
                }

                ControlTab.FAVORITES -> {
                    if (favorites.isEmpty()) {
                        item { EmptyRow(stringResource(R.string.bridge_controller_favorites_empty)) }
                    } else {
                        items(favorites, key = { it.url }) { bookmark ->
                            LinkRow(
                                title = bookmark.title,
                                subtitle = bookmark.url,
                                actionLabel = stringResource(R.string.bridge_controller_queue_action),
                                onClick = { sendRaw(bookmark.url) },
                                onAction = { addToQueue(bookmark.url, bookmark.title) }
                            )
                        }
                    }
                }
            }

            item { RemoteStreamCard() }

            item { Box(Modifier.size(8.dp)) }
        }

        // Only offer Disconnect while the car is actually mirroring; hidden otherwise. Pinned
        // below the scrolling content, same place image 04 puts it.
        if (legacyState.mirrorStatus == MirrorStatus.ACTIVE) {
            DisconnectButton {
                AutoBridgeCommandBus.send(AutoBridgeCommand(type = CommandType.STOP_MIRROR, source = CommandSource.MOBILE))
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
            title = stringResource(R.string.history_title),
            subtitle = stringResource(R.string.history_subtitle),
            onBack = onBack,
            action = HeaderButton("⌫", stringResource(R.string.history_clear)) { CommandHistoryStore.clear(context) }
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (history.isEmpty()) Text(stringResource(R.string.history_empty), color = TextMuted, fontSize = 13.sp)
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
        PhoneHeader(
            title = stringResource(R.string.agent_screen_title),
            subtitle = stringResource(R.string.agent_screen_subtitle),
            onBack = onBack
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionLabel(stringResource(R.string.agent_section_commands))
            Card {
                ToggleRow(
                    stringResource(R.string.agent_toggle_show_confirmation),
                    stringResource(R.string.agent_toggle_show_confirmation_caption),
                    settings.showConfirmation
                ) {
                    RemoteSettingsStore.update(context) { s -> s.copy(showConfirmation = it) }
                }
                ToggleRow(
                    stringResource(R.string.agent_toggle_haptic),
                    stringResource(R.string.agent_toggle_haptic_caption),
                    settings.hapticFeedback
                ) {
                    RemoteSettingsStore.update(context) { s -> s.copy(hapticFeedback = it) }
                }
                ToggleRow(
                    stringResource(R.string.agent_toggle_auto_submit),
                    stringResource(R.string.agent_toggle_auto_submit_caption),
                    settings.autoSubmitText
                ) {
                    RemoteSettingsStore.update(context) { s -> s.copy(autoSubmitText = it) }
                }
            }
            SectionLabel(stringResource(R.string.agent_section_agent))
            Card {
                ToggleRow(
                    stringResource(R.string.agent_toggle_prefer_agent),
                    stringResource(R.string.agent_toggle_prefer_agent_caption),
                    settings.preferAgentForUnknown
                ) {
                    RemoteSettingsStore.update(context) { s -> s.copy(preferAgentForUnknown = it) }
                }
                ToggleRow(
                    stringResource(R.string.agent_toggle_resume_last),
                    stringResource(R.string.agent_toggle_resume_last_caption),
                    settings.resumeLastFeature
                ) {
                    RemoteSettingsStore.update(context) { s -> s.copy(resumeLastFeature = it) }
                }
            }
            SectionLabel(stringResource(R.string.agent_section_history))
            Card {
                ToggleRow(
                    stringResource(R.string.agent_toggle_remember_history),
                    stringResource(R.string.agent_toggle_remember_history_caption),
                    settings.rememberHistory
                ) {
                    RemoteSettingsStore.update(context) { s -> s.copy(rememberHistory = it) }
                }
            }
            Card(onClick = onOpenHistory) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_command_history), color = TextPrimary, fontSize = 15.sp)
                        Text(stringResource(R.string.agent_command_history_caption), color = TextMuted, fontSize = 12.sp)
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
 *
 * [showPill] swaps the trailing chevron for a status pill ("Parked" / "Driving" / "Not
 * connected") — Home's reading of the card, where the pill is the at-a-glance signal rather than
 * a "tap for more" hint. [actions], when given, renders below the status row inside the same card
 * — Home's Mirror / Bridge Duo buttons. Clicks on them take priority over [onClick] (Compose
 * always resolves the innermost clickable first), so the card can stay tappable to open
 * Car & Connection everywhere outside the button row.
 */
@Composable
fun AndroidAutoStatusCard(
    title: String,
    caption: String? = null,
    onClick: (() -> Unit)?,
    showPill: Boolean = false,
    actions: (@Composable () -> Unit)? = null
) {
    val runtime by RuntimeContextStore.context.collectAsState()
    val connectedTemplate = stringResource(R.string.conn_connected_format)
    val labels = ConnectionStatusText.Labels(
        notConnected = stringResource(R.string.conn_not_connected),
        parked = stringResource(R.string.conn_parked),
        driving = stringResource(R.string.conn_driving),
        checking = stringResource(R.string.conn_checking),
        connectedFormat = { String.format(connectedTemplate, it) }
    )
    val status = ConnectionStatusText.of(runtime, labels)
    val body: @Composable () -> Unit = {
        Column {
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
                if (showPill) {
                    StatusPill(status.pill, status.connected)
                } else if (onClick != null) {
                    Text("›", color = TextMuted, fontSize = 22.sp)
                }
            }
            if (actions != null) {
                Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) { actions() }
            }
        }
    }
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Surface(onClick = onClick, color = CardColor, shape = shape, modifier = Modifier.fillMaxWidth()) { body() }
    } else {
        Surface(color = CardColor, shape = shape, modifier = Modifier.fillMaxWidth()) { body() }
    }
}

/** A small accent dot plus one word — [AndroidAutoStatusCard]'s Home-only connection pill. */
@Composable
private fun StatusPill(label: String, connected: Boolean) {
    val color = if (connected) AccentGreen else TextMuted
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(
                label,
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/**
 * Command / URL / search / text box. Submits through [submitCommand] — the one command path —
 * so Home's "Send to Car" field behaves the same way it always has.
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

/**
 * Success/failure feedback for a sent command. [result] already carries a cause-specific message
 * from [dev.autobridge.remote.AutoBridgeCommandRouter] (e.g. "Android Auto is not connected" vs.
 * "That URL is not valid"), so this never relabels a non-connection failure as a connection
 * problem. [onRetry] resends the same input when available; [onOpenConnection] is only offered
 * when the failure reason is actually [CommandFailureReason.NOT_CONNECTED].
 */
@Composable
fun ResultBanner(
    result: CommandResult,
    onRetry: (() -> Unit)? = null,
    onOpenConnection: (() -> Unit)? = null
) {
    val ok = result.status == CommandStatus.SUCCESS
    Surface(color = CardAltColor, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(if (ok) R.string.control_result_sent else R.string.control_result_failed),
                color = if (ok) AccentGreen else ComposeTokens.Danger,
                fontWeight = FontWeight.SemiBold
            )
            Text(result.message, color = TextMuted, fontSize = 13.sp)
            if (!ok && (onRetry != null || (onOpenConnection != null && result.reason == CommandFailureReason.NOT_CONNECTED))) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.control_retry), color = Accent, fontSize = 13.sp)
                        }
                    }
                    if (onOpenConnection != null && result.reason == CommandFailureReason.NOT_CONNECTED) {
                        TextButton(onClick = onOpenConnection) {
                            Text(stringResource(R.string.control_how_to_connect), color = Accent, fontSize = 13.sp)
                        }
                    }
                }
            }
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

/**
 * "On the car": what is on screen now (engine + title + host), progress and transport — the
 * former NowPlayingCard and TransportBar from the retired BridgeControllerActivity, combined into
 * one card per image 04. All commands go through [AutoBridgeSessionManager], same as before.
 */
@Composable
private fun OnTheCarCard(state: AutoBridgeSessionManager.SessionState, onOpenConnection: () -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardColor,
        modifier = Modifier.fillMaxWidth().border(1.dp, ComposeTokens.Hairline, RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!state.connected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.bridge_controller_disconnected),
                        color = ComposeTokens.Warn,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenConnection) {
                        Text(stringResource(R.string.control_how_to_connect), color = Accent, fontSize = 12.sp)
                    }
                }
            } else {
                Text(
                    stringResource(R.string.control_on_the_car) + " · " + engineLabel(context, state.engine),
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp
                )
            }
            Text(
                state.source?.displayTitle ?: stringResource(R.string.bridge_car_nothing_playing),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val detail = buildString {
                append(context.getString(playbackLabel(state.playback)))
                if (state.source != null) append(" · ").append(state.source.displayHost)
                if (state.durationMs > 0) {
                    append(" · ")
                        .append(BrowserResumePoint.clock(state.positionMs))
                        .append(" / ")
                        .append(BrowserResumePoint.clock(state.durationMs))
                }
            }
            Text(detail, color = TextMuted, fontSize = 13.sp)
            state.error?.let {
                Text(context.getString(it.messageRes), color = ComposeTokens.Danger, fontSize = 13.sp)
            }
            state.pending?.let { pending ->
                // A held request opens by itself on the next connect, so there has to be a way to
                // say "not that one" — otherwise the only way to clear it is to send something
                // else, and a link shared by mistake follows the driver into the car.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.bridge_controller_pending, pending.displayHost),
                        color = ComposeTokens.Warn,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { AutoBridgeSessionManager.clearPending(context) }) {
                        Text(
                            stringResource(R.string.bridge_controller_cancel_pending),
                            color = ComposeTokens.Danger,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            TransportRow(state)
        }
    }
}

/**
 * Play/pause, skip and a seek bar. The slider is disabled when the engine reports no duration —
 * the live-stream and still-loading case, where a scrubber that moves but seeks nowhere is worse
 * than one that is plainly unavailable.
 */
@Composable
private fun TransportRow(state: AutoBridgeSessionManager.SessionState) {
    val context = LocalContext.current
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val seekable = state.durationMs > 0

    Column {
        Slider(
            value = scrubbing ?: if (seekable) {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else 0f,
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                scrubbing?.let { fraction -> AutoBridgeSessionManager.seekTo((fraction * state.durationMs).toLong()) }
                scrubbing = null
            },
            enabled = seekable,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = context.getString(R.string.bridge_controller_seek)
            }
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransportButton("⏮", R.string.bridge_controller_previous) { AutoBridgeSessionManager.previous() }
            TransportButton("⏪", R.string.bridge_controller_back_10) { AutoBridgeSessionManager.seekBy(-10_000L) }
            TransportButton(
                if (state.isPlaying) "⏸" else "▶",
                if (state.isPlaying) R.string.bridge_controller_pause else R.string.bridge_controller_play
            ) { AutoBridgeSessionManager.togglePlayPause() }
            TransportButton("⏩", R.string.bridge_controller_forward_10) { AutoBridgeSessionManager.seekBy(10_000L) }
            TransportButton("⏭", R.string.bridge_controller_next) { AutoBridgeSessionManager.next(context) }
        }
    }
}

@Composable
private fun TransportButton(glyph: String, descriptionRes: Int, onClick: () -> Unit) {
    val description = stringResource(descriptionRes)
    TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        Text(glyph, color = TextPrimary, fontSize = 22.sp)
    }
}

/**
 * The playback state as a word the user reads. Not `enum.name`: that produced "Idle"/"PLAYING" in
 * the middle of an otherwise translated line, which reads as a bug in every locale but English.
 */
private fun playbackLabel(state: BridgePlaybackState): Int = when (state) {
    BridgePlaybackState.IDLE -> R.string.bridge_state_idle
    BridgePlaybackState.LOADING -> R.string.bridge_state_loading
    BridgePlaybackState.PLAYING -> R.string.bridge_state_playing
    BridgePlaybackState.PAUSED -> R.string.bridge_state_paused
    BridgePlaybackState.ENDED -> R.string.bridge_state_ended
    BridgePlaybackState.ERROR -> R.string.bridge_state_error
}

private fun engineLabel(context: android.content.Context, kind: EngineKind): String = context.getString(
    when (kind) {
        EngineKind.NATIVE -> R.string.bridge_engine_native
        EngineKind.BROWSER -> R.string.bridge_engine_browser
        EngineKind.REMOTE_STREAM -> R.string.bridge_engine_remote
        EngineKind.UNSUPPORTED -> R.string.bridge_engine_none
    }
)

/**
 * One input, two jobs: paste a link / search text to open on the car, or type into whatever the
 * car is already showing. [placeholder] carries the mode; [onSend] is the same action for the Go
 * IME key and the arrow button.
 */
@Composable
private fun BridgeInputField(value: String, onValueChange: (String) -> Unit, placeholder: String, onSend: () -> Unit) {
    val sendDesc = stringResource(R.string.bridge_controller_send)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onSend,
            color = Accent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(52.dp).semantics { contentDescription = sendDesc }
        ) {
            Box(contentAlignment = Alignment.Center) { Text("➤", fontSize = 20.sp, color = ComposeTokens.Ink) }
        }
    }
}

/** A segmented pill: the Open/search ↔ Type mode toggle and the Queue/Recent/Favorites tabs. */
@Composable
private fun SegmentChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) ComposeTokens.AccentSoft else CardColor,
        modifier = modifier.border(
            1.dp,
            if (selected) Accent else ComposeTokens.Hairline,
            RoundedCornerShape(14.dp)
        )
    ) {
        Text(
            label,
            color = if (selected) Accent else TextMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)
        )
    }
}

@Composable
private fun LinkRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardColor,
        modifier = Modifier.fillMaxWidth().border(1.dp, ComposeTokens.Hairline, RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onClick) {
                Text(stringResource(R.string.bridge_controller_send), color = Accent, fontSize = 13.sp)
            }
            TextButton(onClick = onAction) {
                Text(actionLabel, color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
}

/**
 * The experimental remote-stream host setting. It lives at the bottom of Control rather than in
 * the app's main settings because it is only meaningful next to the thing it affects: the user
 * turning this on is about to send a link and watch which engine picks it up. Off by default, and
 * the router skips the remote branch entirely while it is off, so an unconfigured install never
 * waits on a host that was never set up (see [dev.autobridge.remotestream.RemoteStreamConfig]).
 */
@Composable
private fun RemoteStreamCard() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(RemoteStreamConfig.current(context)) }
    var endpoint by remember { mutableStateOf(config.endpoint) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardColor,
        modifier = Modifier.fillMaxWidth().border(1.dp, ComposeTokens.Hairline, RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.bridge_remote_title),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(stringResource(R.string.bridge_remote_caption), color = TextMuted, fontSize = 12.sp)
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.bridge_remote_endpoint_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    RemoteStreamConfig.setEndpoint(context, endpoint)
                    config = RemoteStreamConfig.current(context)
                })
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    RemoteStreamConfig.setEndpoint(context, endpoint)
                    config = RemoteStreamConfig.current(context)
                    Toast.makeText(
                        context,
                        if (RemoteStreamConfig.normalizeEndpoint(endpoint) == null)
                            context.getString(R.string.bridge_remote_endpoint_invalid)
                        else context.getString(R.string.bridge_remote_saved),
                        Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Text(stringResource(R.string.bridge_remote_save), color = Accent)
                }
                TextButton(onClick = {
                    RemoteStreamConfig.setEnabled(context, !config.enabled)
                    config = RemoteStreamConfig.current(context)
                }) {
                    Text(
                        if (config.enabled) stringResource(R.string.bridge_remote_disable)
                        else stringResource(R.string.bridge_remote_enable),
                        color = if (config.enabled) ComposeTokens.Danger else Accent
                    )
                }
            }
            Text(
                if (config.isUsable) stringResource(R.string.bridge_remote_ready) else stringResource(R.string.bridge_remote_off),
                color = if (config.isUsable) AccentGreen else TextMuted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun DisconnectButton(onClick: () -> Unit) {
    val disconnectDesc = stringResource(R.string.control_disconnect_desc)
    Surface(
        onClick = onClick,
        color = ComposeTokens.Danger.copy(alpha = 0.16f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
            .border(1.dp, ComposeTokens.Danger.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .semantics { contentDescription = disconnectDesc }
    ) {
        Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.control_disconnect),
                color = ComposeTokens.Danger,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * One Quick Action chip. In [removable] (Edit) mode it stops sending on tap and shows a small ✕
 * badge instead, wired to [onRemove] — [QuickCommandStore.remove] under the same key the chip row
 * already reads.
 */
@Composable
private fun QuickCard(
    icon: String,
    label: String,
    removable: Boolean = false,
    onRemove: () -> Unit = {},
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(modifier) {
        Surface(
            onClick = if (removable) ({}) else onClick,
            shape = RoundedCornerShape(16.dp),
            color = CardAltColor,
            modifier = Modifier.fillMaxWidth().height(84.dp)
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
        if (removable) {
            val removeDesc = stringResource(R.string.control_quick_action_remove, label)
            Surface(
                onClick = onRemove,
                shape = CircleShape,
                color = ComposeTokens.Danger,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                    .semantics { contentDescription = removeDesc }
            ) {
                Box(contentAlignment = Alignment.Center) { Text("✕", fontSize = 12.sp, color = ComposeTokens.Ink) }
            }
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
