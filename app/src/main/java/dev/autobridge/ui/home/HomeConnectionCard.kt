package dev.autobridge.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.ui.ConnectionStatusText

/**
 * Home's Android Auto card, drawn from the palette so it follows light mode and the card style:
 * the connection in words ([ConnectionStatusText], the same wording as Control and My Car), a
 * status pill, and Home's Mirror / Multi-app buttons. Tapping the card opens Car & Connection.
 *
 * At large text the pill moves under the status line, so "Android Auto" keeps one line.
 */
@Composable
fun HomeConnectionCard(
    look: HomeLook,
    onOpen: () -> Unit,
    onMirror: () -> Unit,
    onBridgeDuo: (() -> Unit)?
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
    val title = stringResource(R.string.control_android_auto)
    Surface(onClick = onOpen, color = Color.Transparent, shape = look.cardShape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.homeCard(look).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(look.accentSoft, look.chipShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_tile_car), contentDescription = null, tint = look.accent, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        title,
                        color = look.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        status.summary,
                        color = look.textSecondary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { contentDescription = "$title: ${status.summary}" }
                    )
                    if (look.largeText) {
                        Spacer(Modifier.size(6.dp))
                        StatusPill(look, status.pill, status.connected)
                    }
                }
                if (!look.largeText) StatusPill(look, status.pill, status.connected)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardButton(look, stringResource(R.string.home_mirror_action), R.drawable.ic_tile_mirror, primary = true, onClick = onMirror, modifier = Modifier.weight(1f))
                if (onBridgeDuo != null) {
                    CardButton(look, stringResource(R.string.home_bridge_duo_action), R.drawable.ic_browser_split, primary = false, onClick = onBridgeDuo, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatusPill(look: HomeLook, label: String, connected: Boolean) {
    val color = if (connected) look.good else look.textSecondary
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(999.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Mirror / Multi-app: [primary] fills with the accent, the other sits on the raised surface. */
@Composable
private fun CardButton(look: HomeLook, label: String, icon: Int, primary: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val ink = if (primary) look.onAccent else look.text
    Surface(
        onClick = onClick,
        shape = look.buttonShape,
        color = if (primary) look.accent else look.raised,
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
