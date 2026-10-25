package dev.autobridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import dev.autobridge.design.ThemeHolder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.autobridge.R
import dev.autobridge.core.state.RuntimeContextStore
import dev.autobridge.mycar.MyCarFacts.Tone

/** One tappable settings row: accent icon badge, title, caption and a chevron. */
data class SettingsLink(
    val icon: Int,
    val title: String,
    val caption: String,
    val onClick: () -> Unit
)

/** A My Car tool: its icon and colour, the tool's name, and one line read from its own data. */
data class MyCarTile(
    val icon: Int,
    val accent: Color,
    val title: String,
    val value: String,
    val caption: String?,
    val tone: Tone,
    val onClick: () -> Unit
)

/**
 * One figure card above the tools. [progress] draws the bar under the value (0..1) and is null
 * when nothing real can fill it: a fuel average or an odometer is not a share of anything.
 */
data class MyCarStat(
    val label: String,
    val value: String,
    val caption: String?,
    val progress: Float?,
    val tone: Tone
)

/**
 * Everything My Car shows that comes from storage or the phone, read again each time the page
 * resumes, so a fill-up logged in the fuel page is on its tile on the way back. "—" stands for a
 * reading the phone did not give; nothing is estimated.
 */
data class MyCarState(
    val vehicleTitle: String,
    val profileLabel: String,
    /** The four figures above the tools: only ones the stored data supports. */
    val stats: List<MyCarStat>,
    val tiles: List<MyCarTile>,
    val battery: String,
    val temperature: String,
    val setupCaption: String,
    val autoStart: Boolean
)

/**
 * Settings > My Car (formerly Car & Connection): the connected car, the car tools that used to be
 * a list under Settings > Utilities, and the connection rows. Every tile opens the tool's own page;
 * this page only summarises. The vehicle card reads the same `RuntimeContextStore` flow as Home and
 * Control, so it updates live when the head unit disconnects or reconnects.
 */
@Composable
fun CarConnectionScreen(
    onBack: () -> Unit,
    load: () -> MyCarState,
    onOpenProfiles: () -> Unit,
    onOpenSetup: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    links: List<SettingsLink>
) {
    var state by remember { mutableStateOf(load()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state = load()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val runtime by RuntimeContextStore.context.collectAsState()

    Column(Modifier.fillMaxSize().background(ComposeTokens.Ink)) {
        PhoneHeader(
            title = stringResource(R.string.car_connection_title),
            subtitle = stringResource(R.string.car_connection_subtitle),
            onBack = onBack
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AndroidAutoStatusCard(
                title = state.vehicleTitle,
                caption = stringResource(R.string.my_car_profile_caption, state.profileLabel),
                onClick = onOpenProfiles
            )

            StatGrid(state.stats)

            SectionLabel(stringResource(R.string.my_car_section_tools))
            ToolRows(state.tiles)

            SectionLabel(stringResource(R.string.my_car_section_connection))
            ConnectionStrip(
                listOf(
                    stringResource(R.string.control_android_auto) to stringResource(
                        if (runtime.connected) R.string.my_car_aa_connected else R.string.my_car_aa_not_connected
                    ),
                    stringResource(R.string.my_car_phone_battery) to state.battery,
                    stringResource(R.string.my_car_temperature) to state.temperature
                ),
                highlightFirst = runtime.connected
            )
            SettingsLinkRow(
                SettingsLink(R.drawable.ic_tile_car, stringResource(R.string.setup_title), state.setupCaption, onOpenSetup)
            )
            SwitchRow(
                icon = R.drawable.ic_tile_remote,
                title = stringResource(R.string.setup_autostart_title),
                caption = stringResource(if (state.autoStart) R.string.setup_autostart_on else R.string.setup_autostart_off),
                checked = state.autoStart,
                onChange = { onAutoStartChange(it); state = load() }
            )
            links.forEach { SettingsLinkRow(it) }
        }
    }
}

private fun cardShape(): RoundedCornerShape = RoundedCornerShape(ThemeHolder.palette.corners.card.dp)

private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.NORMAL -> ComposeTokens.Text
    Tone.WARN -> ComposeTokens.Warn
    Tone.ALERT -> ComposeTokens.Danger
    Tone.EMPTY -> ComposeTokens.TextMuted
}

/** The figure cards: label, big value, a bar when there is one, and a quiet line. Two across, one when narrow. */
@Composable
private fun StatGrid(stats: List<MyCarStat>) {
    if (stats.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 300.dp) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { StatCard(it, Modifier.weight(1f).fillMaxHeight()) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StatCard(stat: MyCarStat, modifier: Modifier) {
    val p = ThemeHolder.palette
    val tone = toneColor(stat.tone)
    val bar = when (stat.tone) {
        Tone.WARN -> ComposeTokens.Warn
        Tone.ALERT -> ComposeTokens.Danger
        else -> ComposeTokens.Accent
    }
    Column(
        modifier
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(stat.label, stat.value, stat.caption).joinToString(", ")
            }
            .background(Color(p.cardFill), cardShape())
            .border(1.dp, Color(p.cardEdge), cardShape())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            stat.label.uppercase(),
            color = ComposeTokens.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // A figure is large; "nothing logged yet" is a quiet line, not a headline.
        val empty = stat.tone == Tone.EMPTY
        Text(
            stat.value,
            color = tone,
            fontSize = if (empty) 15.sp else 20.sp,
            fontWeight = if (empty) FontWeight.Medium else FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        stat.progress?.let { share ->
            Box(Modifier.fillMaxWidth().height(4.dp).background(ComposeTokens.Hairline, RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).background(bar, RoundedCornerShape(4.dp)))
            }
        }
        if (!stat.caption.isNullOrBlank()) {
            Text(stat.caption, color = ComposeTokens.TextMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The car tools as one card of rows, each with its icon chip, its figure and a chevron; every row opens the tool. */
@Composable
private fun ToolRows(tiles: List<MyCarTile>) {
    val p = ThemeHolder.palette
    Column(
        Modifier.fillMaxWidth()
            .background(Color(p.cardFill), cardShape())
            .border(1.dp, Color(p.cardEdge), cardShape())
            .clip(cardShape())
    ) {
        tiles.forEachIndexed { index, tile ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(ComposeTokens.Hairline))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(onClick = tile.onClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull(tile.title, tile.value, tile.caption).joinToString(", ")
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(tile.accent.copy(alpha = 0.16f), RoundedCornerShape(ThemeHolder.palette.corners.chip.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        painterResource(tile.icon), contentDescription = null, tint = tile.accent, modifier = Modifier.size(20.dp)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(tile.title, color = ComposeTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(tile.value, tile.caption?.takeIf { it.isNotBlank() }).joinToString(" · "),
                        color = toneColor(tile.tone).let { if (tile.tone == Tone.NORMAL) ComposeTokens.TextMuted else it },
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text("›", color = ComposeTokens.TextMuted, fontSize = 22.sp)
            }
        }
    }
}

/** Android Auto, phone battery and temperature side by side: readings only, nothing to tap. */
@Composable
private fun ConnectionStrip(cells: List<Pair<String, String>>, highlightFirst: Boolean) {
    Surface(color = ComposeTokens.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 12.dp, horizontal = 6.dp)) {
            cells.forEachIndexed { index, (label, value) ->
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(label, color = ComposeTokens.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        value,
                        color = if (index == 0 && highlightFirst) ComposeTokens.Ok else ComposeTokens.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(icon: Int, title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        onClick = { onChange(!checked) },
        color = ComposeTokens.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Column(Modifier.padding(start = 12.dp, end = 8.dp).weight(1f)) {
                Text(title, color = ComposeTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(caption, color = ComposeTokens.TextMuted, fontSize = 12.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = ComposeTokens.Accent)
            )
        }
    }
}

@Composable
private fun IconBadge(icon: Int) {
    Box(
        Modifier.size(42.dp)
            .background(ComposeTokens.Accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .border(1.dp, ComposeTokens.Accent.copy(alpha = 0.28f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            painterResource(icon),
            contentDescription = null,
            tint = ComposeTokens.Accent,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun SettingsLinkRow(link: SettingsLink) {
    Surface(
        onClick = link.onClick,
        color = ComposeTokens.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(link.icon)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(link.title, color = ComposeTokens.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(link.caption, color = ComposeTokens.TextMuted, fontSize = 12.sp)
            }
            Text("›", color = ComposeTokens.TextMuted, fontSize = 22.sp)
        }
    }
}
