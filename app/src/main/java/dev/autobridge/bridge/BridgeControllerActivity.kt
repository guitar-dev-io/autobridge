package dev.autobridge.bridge

import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.R
import dev.autobridge.browser.BrowserInputResolver
import dev.autobridge.browser.BrowserPlayQueue
import dev.autobridge.browser.BrowserResumePoint
import dev.autobridge.browser.SearchEngineStore
import dev.autobridge.entertainment.WebBookmarkStore
import dev.autobridge.remotestream.RemoteStreamConfig
import dev.autobridge.ui.AutoBridgePhoneTheme
import dev.autobridge.ui.ComposeTokens

/**
 * The phone half of the bridge: a controller, not a second screen.
 *
 * Everything that needs a keyboard, a scrollable list or an unhurried look lives here — typing a
 * URL, searching, picking from recents and favorites, reordering what plays next — and the car
 * gets only the result. That is the division the whole change is built on, and it is why this
 * screen carries a transport bar at the bottom: once something is on the car, the phone is where
 * it is driven from, so play/pause/seek have to be a thumb away rather than behind a navigation
 * step.
 *
 * It renders from [AutoBridgeSessionManager]'s single state flow, so nothing here can disagree
 * with the car about what is playing — the two surfaces read the same value.
 */
class BridgeControllerActivity : androidx.activity.ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutoBridgeSessionManager.initialize(this)
        setContentView(
            ComposeView(this).apply {
                // ComponentActivity, and DisposeOnDetachedFromWindow, for the same reason
                // MainActivity uses both: a plain Activity installs no ViewTreeLifecycleOwner, and
                // ComposeView throws on attach without one.
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    AutoBridgePhoneTheme { BridgeControllerScreen(onClose = { finish() }) }
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        AutoBridgeSessionManager.refresh()
    }
}

/**
 * The sections the controller is split into; one is shown at a time to keep the list short.
 *
 * The label is a string *id* rather than the text: an enum constant is built once per process, so
 * a label captured at class-init would keep whatever language was in force then and survive a
 * language change unchanged — the same reason [dev.autobridge.browser.FloatingButtonAction] holds
 * ids.
 */
private enum class ControllerTab(val labelRes: Int) {
    QUEUE(R.string.bridge_controller_tab_queue),
    RECENT(R.string.bridge_controller_tab_recent),
    FAVORITES(R.string.bridge_controller_tab_favorites)
}

@Composable
private fun BridgeControllerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val state by AutoBridgeSessionManager.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var keyboardText by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(ControllerTab.QUEUE) }
    // Bumped after any store write so the lists below re-read. The queue, favorites and recents
    // are SharedPreferences-backed and have no flow of their own; a revision counter is a great
    // deal less machinery than giving three stores one each for a screen that is rarely open.
    var revision by remember { mutableIntStateOf(0) }

    val queue = remember(revision) { AutoBridgeSessionManager.queueItems(context) }
    val recents = remember(revision) { AutoBridgeSessionManager.recents(context) }
    val favorites = remember(revision) { WebBookmarkStore.list(context) }

    fun resolve(raw: String): String? =
        BrowserInputResolver.resolveBrowserInput(raw, SearchEngineStore.engine(context))

    fun send(raw: String) {
        val url = resolve(raw)
        if (url == null) {
            Toast.makeText(context, R.string.bridge_controller_nothing_to_send, Toast.LENGTH_SHORT).show()
            return
        }
        val result = AutoBridgeSessionManager.sendToCar(
            context,
            BridgeSource(url = url, origin = BridgeSource.Origin.PHONE)
        )
        val message = when (result) {
            is AutoBridgeSessionManager.SendResult.Opened ->
                context.getString(R.string.bridge_controller_sent)
            AutoBridgeSessionManager.SendResult.Pending ->
                context.getString(R.string.bridge_controller_queued_for_connect)
            is AutoBridgeSessionManager.SendResult.Refused ->
                context.getString(result.error.messageRes)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        input = ""
        revision++
    }

    Column(Modifier.fillMaxSize().background(ComposeTokens.Ink)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.bridge_controller_title),
                color = ComposeTokens.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.bridge_controller_close), color = ComposeTokens.Accent)
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { NowPlayingCard(state) }

            item {
                SendField(
                    value = input,
                    onValueChange = { input = it },
                    onSend = { send(input) },
                    onQueue = {
                        val url = resolve(input)
                        if (url == null) {
                            Toast.makeText(context, R.string.bridge_controller_nothing_to_send, Toast.LENGTH_SHORT).show()
                        } else {
                            val size = AutoBridgeSessionManager.queueAdd(context, BridgeSource(url))
                            Toast.makeText(
                                context,
                                if (size == null) context.getString(R.string.browser_already_queued)
                                else context.getString(R.string.bridge_controller_queued),
                                Toast.LENGTH_SHORT
                            ).show()
                            input = ""
                            revision++
                        }
                    }
                )
            }

            item {
                KeyboardField(
                    value = keyboardText,
                    onValueChange = { keyboardText = it },
                    onSend = { submit ->
                        val ok = AutoBridgeSessionManager.sendKeyboard(context, keyboardText, submit)
                        Toast.makeText(
                            context,
                            if (ok) context.getString(R.string.bridge_controller_text_sent)
                            else context.getString(R.string.bridge_controller_text_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                        if (ok) keyboardText = ""
                    }
                )
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControllerTab.entries.forEach { entry ->
                        TabChip(
                            label = stringResource(entry.labelRes),
                            selected = tab == entry,
                            badge = if (entry == ControllerTab.QUEUE && queue.isNotEmpty()) queue.size else null,
                            modifier = Modifier.weight(1f)
                        ) { tab = entry }
                    }
                }
            }

            when (tab) {
                ControllerTab.QUEUE -> {
                    if (queue.isEmpty()) {
                        item { EmptyRow(stringResource(R.string.bridge_controller_queue_empty)) }
                    } else {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    AutoBridgeSessionManager.next(context)
                                    revision++
                                }) {
                                    Text(stringResource(R.string.bridge_controller_play_next), color = ComposeTokens.Accent)
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
                                onClick = { send(item.url) },
                                onAction = {
                                    AutoBridgeSessionManager.queueRemove(context, item.url)
                                    revision++
                                }
                            )
                        }
                    }
                }

                ControllerTab.RECENT -> {
                    if (recents.isEmpty()) {
                        item { EmptyRow(stringResource(R.string.bridge_controller_recent_empty)) }
                    } else {
                        items(recents, key = { "${it.kind}:${it.data ?: it.title}:${it.timestampMs}" }) { entry ->
                            val url = entry.data
                            LinkRow(
                                title = entry.title,
                                subtitle = entry.subtitle ?: url.orEmpty(),
                                actionLabel = stringResource(R.string.bridge_controller_queue_action),
                                onClick = { url?.let { send(it) } },
                                onAction = {
                                    url?.let {
                                        AutoBridgeSessionManager.queueAdd(context, BridgeSource(it, entry.title))
                                        revision++
                                    }
                                }
                            )
                        }
                    }
                }

                ControllerTab.FAVORITES -> {
                    if (favorites.isEmpty()) {
                        item { EmptyRow(stringResource(R.string.bridge_controller_favorites_empty)) }
                    } else {
                        items(favorites, key = { it.url }) { bookmark ->
                            LinkRow(
                                title = bookmark.title,
                                subtitle = bookmark.url,
                                actionLabel = stringResource(R.string.bridge_controller_queue_action),
                                onClick = { send(bookmark.url) },
                                onAction = {
                                    AutoBridgeSessionManager.queueAdd(
                                        context,
                                        BridgeSource(bookmark.url, bookmark.title)
                                    )
                                    revision++
                                }
                            )
                        }
                    }
                }
            }

            item { RemoteStreamCard() }

            item { Box(Modifier.size(8.dp)) }
        }

        TransportBar(state)
    }
}

@Composable
private fun stringResource(id: Int): String = LocalContext.current.getString(id)

/**
 * What the car is doing, in the words the user would use.
 *
 * The engine is named because it is the single most useful thing to see when something looks
 * wrong: "this opened in the browser when I expected the player" is diagnosable at a glance
 * instead of through a log.
 */
@Composable
private fun NowPlayingCard(state: AutoBridgeSessionManager.SessionState) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ComposeTokens.Surface,
        modifier = Modifier.fillMaxWidth().border(1.dp, ComposeTokens.Hairline, RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (state.connected) context.getString(R.string.bridge_controller_connected)
                else context.getString(R.string.bridge_controller_disconnected),
                color = if (state.connected) ComposeTokens.Ok else ComposeTokens.Warn,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                state.source?.displayTitle ?: context.getString(R.string.bridge_car_nothing_playing),
                color = ComposeTokens.Text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val detail = buildString {
                append(context.getString(playbackLabel(state.playback)))
                if (state.source != null) {
                    append(" · ").append(engineLabel(context, state.engine))
                }
                if (state.durationMs > 0) {
                    append(" · ")
                        .append(BrowserResumePoint.clock(state.positionMs))
                        .append(" / ")
                        .append(BrowserResumePoint.clock(state.durationMs))
                }
            }
            Text(detail, color = ComposeTokens.TextMuted, fontSize = 13.sp)
            state.error?.let {
                Text(context.getString(it.messageRes), color = ComposeTokens.Danger, fontSize = 13.sp)
            }
            state.pending?.let { pending ->
                // A held request opens by itself on the next connect, so there has to be a way to
                // say "not that one" — otherwise the only way to clear it is to send something
                // else, and a link shared by mistake follows the driver into the car.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        context.getString(R.string.bridge_controller_pending, pending.displayHost),
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
        }
    }
}

/**
 * The playback state as a word the user reads.
 *
 * Not `enum.name`: that produced "Idle"/"PLAYING" in the middle of an otherwise translated line,
 * which reads as a bug in every locale but English.
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

@Composable
private fun SendField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onQueue: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.bridge_controller_input_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSend() })
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSend,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ComposeTokens.Accent)
            ) { Text(stringResource(R.string.bridge_controller_send)) }
            Button(
                onClick = onQueue,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = ComposeTokens.SurfaceRaised)
            ) { Text(stringResource(R.string.bridge_controller_add_queue), color = ComposeTokens.Text) }
        }
    }
}

@Composable
private fun KeyboardField(value: String, onValueChange: (String) -> Unit, onSend: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.bridge_controller_keyboard_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend(true) })
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onSend(false) }) {
                Text(stringResource(R.string.bridge_controller_type), color = ComposeTokens.Accent)
            }
            TextButton(onClick = { onSend(true) }) {
                Text(stringResource(R.string.bridge_controller_type_submit), color = ComposeTokens.Accent)
            }
        }
    }
}

/**
 * Play/pause, skip, and a seek bar, pinned to the bottom.
 *
 * The slider is disabled when the engine reports no duration, which is the live-stream and
 * still-loading case: a scrubber that moves but seeks nowhere is worse than one that is plainly
 * unavailable.
 */
@Composable
private fun TransportBar(state: AutoBridgeSessionManager.SessionState) {
    val context = LocalContext.current
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val seekable = state.durationMs > 0

    Surface(color = ComposeTokens.Surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Slider(
                value = scrubbing ?: if (seekable) {
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                } else 0f,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { fraction ->
                        AutoBridgeSessionManager.seekTo((fraction * state.durationMs).toLong())
                    }
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
                TransportButton("⏮", R.string.bridge_controller_previous) {
                    AutoBridgeSessionManager.previous()
                }
                TransportButton("⏪", R.string.bridge_controller_back_10) {
                    AutoBridgeSessionManager.seekBy(-10_000L)
                }
                TransportButton(
                    if (state.isPlaying) "⏸" else "▶",
                    if (state.isPlaying) R.string.bridge_controller_pause else R.string.bridge_controller_play
                ) { AutoBridgeSessionManager.togglePlayPause() }
                TransportButton("⏩", R.string.bridge_controller_forward_10) {
                    AutoBridgeSessionManager.seekBy(10_000L)
                }
                TransportButton("⏭", R.string.bridge_controller_next) {
                    AutoBridgeSessionManager.next(context)
                }
            }
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
        Text(glyph, color = ComposeTokens.Text, fontSize = 22.sp)
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    badge: Int?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) ComposeTokens.AccentSoft else ComposeTokens.Surface,
        modifier = modifier.border(
            1.dp,
            if (selected) ComposeTokens.Accent else ComposeTokens.Hairline,
            RoundedCornerShape(14.dp)
        )
    ) {
        Text(
            if (badge != null) "$label ($badge)" else label,
            color = if (selected) ComposeTokens.Accent else ComposeTokens.TextMuted,
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
        color = ComposeTokens.Surface,
        modifier = Modifier.fillMaxWidth().border(1.dp, ComposeTokens.Hairline, RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = ComposeTokens.Text,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    color = ComposeTokens.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onClick) {
                Text(stringResource(R.string.bridge_controller_send), color = ComposeTokens.Accent, fontSize = 13.sp)
            }
            TextButton(onClick = onAction) {
                Text(actionLabel, color = ComposeTokens.TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, color = ComposeTokens.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
}

/**
 * The experimental remote-stream host setting.
 *
 * It lives at the bottom of the controller rather than in the app's main settings because it is
 * only meaningful next to the thing it affects: the user turning this on is about to send a link
 * and watch which engine picks it up. Off by default, and the router skips the remote branch
 * entirely while it is off, so an unconfigured install never waits on a host that was never set
 * up (see [dev.autobridge.remotestream.RemoteStreamConfig]).
 */
@Composable
private fun RemoteStreamCard() {
    val context = LocalContext.current
    var config by remember { mutableStateOf(RemoteStreamConfig.current(context)) }
    var endpoint by remember { mutableStateOf(config.endpoint) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ComposeTokens.Surface,
        modifier = Modifier.fillMaxWidth().border(1.dp, ComposeTokens.Hairline, RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.bridge_remote_title),
                color = ComposeTokens.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.bridge_remote_caption),
                color = ComposeTokens.TextMuted,
                fontSize = 12.sp
            )
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
                    Text(stringResource(R.string.bridge_remote_save), color = ComposeTokens.Accent)
                }
                TextButton(onClick = {
                    RemoteStreamConfig.setEnabled(context, !config.enabled)
                    config = RemoteStreamConfig.current(context)
                }) {
                    Text(
                        if (config.enabled) stringResource(R.string.bridge_remote_disable)
                        else stringResource(R.string.bridge_remote_enable),
                        color = if (config.enabled) ComposeTokens.Danger else ComposeTokens.Accent
                    )
                }
            }
            Text(
                if (config.isUsable) stringResource(R.string.bridge_remote_ready)
                else stringResource(R.string.bridge_remote_off),
                color = if (config.isUsable) ComposeTokens.Ok else ComposeTokens.TextMuted,
                fontSize = 12.sp
            )
        }
    }
}
