package dev.autobridge.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.autobridge.remote.AutoBridgeCommand
import dev.autobridge.remote.AutoBridgeCommandBus
import dev.autobridge.remote.CommandFailureReason
import dev.autobridge.remote.CommandResult
import dev.autobridge.remote.CommandSource
import dev.autobridge.remote.CommandStatus
import dev.autobridge.remote.CommandType
import dev.autobridge.ui.PhoneHomeLayout
import dev.autobridge.ui.submitCommand

/**
 * Home's Send to Car field, palette-drawn so it reads in light mode too. Submits through the one
 * command path ([submitCommand]), exactly like Control's field.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeSendField(look: HomeLook, placeholder: String, onSubmitted: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    fun send() {
        val sent = text
        if (submitCommand(sent)) {
            onSubmitted(sent)
            text = ""
        }
    }
    val sendDescription = stringResource(R.string.home_v2_send)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.bringIntoViewRequester(bringIntoView)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(placeholder, color = look.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            shape = look.buttonShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = look.surface,
                unfocusedContainerColor = look.surface,
                focusedTextColor = look.text,
                unfocusedTextColor = look.text,
                cursorColor = look.accent,
                unfocusedBorderColor = look.hairline,
                focusedBorderColor = look.accent
            ),
            // The keyboard takes a moment to slide up and shrink the page; scroll once it has, or
            // the field stays under it.
            modifier = Modifier.weight(1f).onFocusEvent { if (it.isFocused) scope.launch { delay(350); bringIntoView.bringIntoView() } }
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = { send() },
            color = look.accent,
            shape = look.buttonShape,
            modifier = Modifier.size(56.dp).semantics { contentDescription = sendDescription }
        ) {
            Box(contentAlignment = Alignment.Center) { Text("➤", fontSize = 20.sp, color = look.onAccent) }
        }
    }
}

/** "Sent" / "Couldn't send" under the field, with Retry and, when not connected, How to connect. */
@Composable
fun HomeResultBanner(look: HomeLook, result: CommandResult, onRetry: () -> Unit, onOpenConnection: () -> Unit) {
    val ok = result.status == CommandStatus.SUCCESS
    Surface(color = look.raised, shape = look.buttonShape) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(if (ok) R.string.control_result_sent else R.string.control_result_failed),
                color = if (ok) look.good else look.danger,
                fontWeight = FontWeight.SemiBold
            )
            Text(result.message, color = look.textSecondary, fontSize = 13.sp)
            if (!ok) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.control_retry), color = look.accent, fontSize = 13.sp)
                    }
                    if (result.reason == CommandFailureReason.NOT_CONNECTED) {
                        TextButton(onClick = onOpenConnection) {
                            Text(stringResource(R.string.control_how_to_connect), color = look.accent, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

/** The design's Recent row: one scrolling line of pills, each re-sending what it names. */
@Composable
fun HomeRecentRow(look: HomeLook, recents: List<PhoneHomeLayout.RecentSend>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        recents.forEach { RecentChip(look, it) }
    }
}

@Composable
private fun RecentChip(look: HomeLook, recent: PhoneHomeLayout.RecentSend) {
    val sendAgainDescription = stringResource(R.string.home_recent_send_again, recent.label)
    val pill = RoundedCornerShape(999.dp)
    Surface(
        onClick = {
            AutoBridgeCommandBus.send(AutoBridgeCommand(type = recent.type, payload = recent.payload, source = CommandSource.MOBILE))
        },
        shape = pill,
        color = look.cardFill,
        modifier = Modifier.heightIn(min = 40.dp)
            .border(1.dp, look.hairline, pill)
            .semantics { contentDescription = sendAgainDescription }
    ) {
        Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(if (recent.type == CommandType.OPEN_URL) R.drawable.ic_tile_web else R.drawable.ic_tile_remote),
                contentDescription = null,
                tint = look.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(recent.label, color = look.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
