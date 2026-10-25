package dev.autobridge.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.ui.PhoneHomeLayout

/** One place Home opens, as the three layouts draw it. */
data class HomeEntry(
    val item: PhoneHomeLayout.Item,
    val title: String,
    val caption: String,
    val icon: Int,
    val onClick: () -> Unit
)

/** Dashboard's 2x2 cards: chip on top, title and caption at the bottom. */
@Composable
fun DashboardCards(look: HomeLook, cards: List<HomeEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { entry -> DashboardCard(look, entry, Modifier.weight(1f)) }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DashboardCard(look: HomeLook, entry: HomeEntry, modifier: Modifier) {
    Surface(
        onClick = entry.onClick,
        color = Color.Transparent,
        shape = look.cardShape,
        modifier = modifier.heightIn(min = 118.dp)
    ) {
        Column(
            Modifier.homeCard(look).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconChip(look, entry.item, entry.icon, size = 40.dp, iconSize = 20.dp, shape = look.chipShape)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, color = look.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.caption, color = look.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Dashboard's quick-launch row: bare chips with a small label, evenly spread. */
@Composable
fun QuickLaunchRow(look: HomeLook, entries: List<HomeEntry>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { entry ->
            Surface(
                onClick = entry.onClick,
                color = Color.Transparent,
                shape = look.tileShape,
                modifier = Modifier.weight(1f).semantics { contentDescription = entry.title }
            ) {
                Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconChip(look, entry.item, entry.icon, size = 52.dp, iconSize = 22.dp, shape = look.tileShape)
                    Text(
                        entry.title,
                        color = look.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/** Classic grid: three columns of tiles, two at large text; labels per Show tile labels. */
@Composable
fun ClassicGrid(look: HomeLook, entries: List<HomeEntry>) {
    val columns = PhoneHomeLayout.gridColumns(look.fontScale)
    val height = PhoneHomeLayout.tileHeightDp(look.fontScale).dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { entry -> ClassicTile(look, entry, Modifier.weight(1f).height(height)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ClassicTile(look: HomeLook, entry: HomeEntry, modifier: Modifier) {
    Surface(
        onClick = entry.onClick,
        color = Color.Transparent,
        shape = look.tileShape,
        modifier = modifier.semantics { contentDescription = entry.title }
    ) {
        Column(
            Modifier.fillMaxSize().homeCard(look, look.tileShape).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            IconChip(look, entry.item, entry.icon, size = 48.dp, iconSize = 24.dp, shape = look.chipShape)
            if (look.spec.showTileLabels) {
                Text(
                    entry.title,
                    color = look.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Compact: one card of rows, hairlines between them. */
@Composable
fun CompactList(look: HomeLook, entries: List<HomeEntry>) {
    Column(Modifier.fillMaxWidth().clip(look.cardShape).homeCard(look)) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(look.hairline))
            Surface(onClick = entry.onClick, color = Color.Transparent, shape = RectangleShape) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconChip(look, entry.item, entry.icon, size = 36.dp, iconSize = 18.dp, shape = look.smallChipShape)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(entry.title, color = look.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.caption, color = look.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", color = look.textTertiary, fontSize = 22.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
