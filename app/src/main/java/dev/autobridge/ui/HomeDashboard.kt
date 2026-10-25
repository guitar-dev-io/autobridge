package dev.autobridge.ui

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.R
import dev.autobridge.design.HomeLayout
import dev.autobridge.design.ThemeResolver
import dev.autobridge.design.Wallpaper
import dev.autobridge.remote.CommandHistoryStore
import dev.autobridge.settings.AppearanceStore
import dev.autobridge.ui.home.ClassicGrid
import dev.autobridge.ui.home.CompactList
import dev.autobridge.ui.home.DashboardCards
import dev.autobridge.ui.home.HomeConnectionCard
import dev.autobridge.ui.home.HomeEntry
import dev.autobridge.ui.home.HomeLook
import dev.autobridge.ui.home.HomeNowPlayingHero
import dev.autobridge.ui.home.HomeRadioCard
import dev.autobridge.ui.home.HomeRecentRow
import dev.autobridge.ui.home.HomeResultBanner
import dev.autobridge.ui.home.HomeSectionHeader
import dev.autobridge.ui.home.HomeSendField
import dev.autobridge.ui.home.HomeWallpaper
import dev.autobridge.ui.home.QuickLaunchRow
import dev.autobridge.ui.home.WallpaperDrawable
import dev.autobridge.ui.home.homeCard
import dev.autobridge.ui.home.rememberHomeLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One Quick Launch tile as the view layer needs it. */
data class HomeTileUi(val title: String, val icon: Int, val accent: Int, val onClick: () -> Unit)

/**
 * Home's radio card: the station on the air when [live], else the one listened to last.
 * [song] is the song and artist when the stream names them; [logo] is null until it has loaded.
 */
data class ResumeRadioUi(
    val station: String,
    val logo: android.graphics.Bitmap? = null,
    val live: Boolean = false,
    val playing: Boolean = false,
    val song: String? = null
)

/**
 * Body of the phone Home (below the AutoBridge header, above the mini player), in the layout
 * chosen under Settings > Appearance > Home layout:
 *
 * - Dashboard: a now-playing hero, 2x2 cards (Radio, TV, My Car, Displays) and a quick-launch row;
 * - Classic grid: every place as a tile, three columns (two at 130% text and up);
 * - Compact: one card of rows, with the bottom mini player.
 *
 * Every layout keeps the Android Auto card on top, then Send to Car with its result and Recent
 * sends. [tiles] are MainActivity's six Quick Launch tiles in [PhoneHomeLayout.Tile] order; Mirror,
 * My Car and Displays come from [onMirror], [onOpenConnection] and [onBridgeDuo].
 *
 * Send to Car is the one command path ([submitCommand]), and Recent is read from
 * [CommandHistoryStore], the single command history. The chosen wallpaper is painted behind the
 * whole Home page, header included.
 */
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
    onDismissUpdate: () -> Unit = {},
    /** The radio card's station; null when none was ever played. */
    resumeRadio: ResumeRadioUi? = null,
    /** The card: opens the radio player (starting the last station when nothing is on). */
    onOpenRadio: () -> Unit = {},
    /** The card's round button: pause/resume what is on, or play the last station. */
    onToggleRadio: () -> Unit = {}
) {
    val look = rememberHomeLook()
    val history by CommandHistoryStore.entries.collectAsState()
    val lastResult = rememberLastCommandResult()
    var lastSubmittedText by remember { mutableStateOf("") }
    val recents = PhoneHomeLayout.recentSends(history)
    val entries = homeEntries(tiles, resumeRadio, onOpenConnection, onMirror, onBridgeDuo)
    val displays = onBridgeDuo != null
    val layout = look.spec.homeLayout

    HomeWallpaperBehindPage(look)

    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (updateVersion != null) UpdateCard(look, updateVersion, onOpenUpdate, onDismissUpdate)

        HomeConnectionCard(look, onOpen = onOpenConnection, onMirror = onMirror, onBridgeDuo = onBridgeDuo)

        val quickLaunchLabel = stringResource(R.string.home_quick_launch)
        val editLabel = stringResource(R.string.home_quick_launch_edit)
        when (layout) {
            HomeLayout.DASHBOARD -> {
                HomeNowPlayingHero(
                    look,
                    radio = resumeRadio,
                    onOpenRadio = onOpenRadio,
                    onToggleRadio = onToggleRadio,
                    onOpenMusic = entries.getValue(PhoneHomeLayout.Item.MUSIC).onClick
                )
                DashboardCards(look, PhoneHomeLayout.dashboardCards(displays).map { entries.getValue(it) })
                HomeSectionHeader(look, quickLaunchLabel, editLabel, onEditQuickLaunch)
                QuickLaunchRow(look, PhoneHomeLayout.dashboardQuickLaunch(displays).map { entries.getValue(it) })
            }
            HomeLayout.CLASSIC -> {
                // The station on the air: the mini player leaves radio to this card.
                if (resumeRadio != null) HomeRadioCard(look, resumeRadio, onOpenRadio, onToggleRadio)
                HomeSectionHeader(look, quickLaunchLabel, editLabel, onEditQuickLaunch)
                ClassicGrid(look, PhoneHomeLayout.allItems(displays).map { entries.getValue(it) })
            }
            HomeLayout.COMPACT -> {
                if (resumeRadio != null) HomeRadioCard(look, resumeRadio, onOpenRadio, onToggleRadio)
                HomeSectionHeader(look, quickLaunchLabel, editLabel, onEditQuickLaunch)
                CompactList(look, PhoneHomeLayout.allItems(displays).map { entries.getValue(it) })
            }
        }

        // The full controller (queue, recents, favorites, transport) is one tap away; the field
        // stays because one-shot sending is the common case and should not need a navigation step.
        HomeSectionHeader(look, stringResource(R.string.home_send_to_car), stringResource(R.string.home_open_controller), onOpenController)
        HomeSendField(look, stringResource(R.string.home_send_placeholder)) { lastSubmittedText = it }
        lastResult.value?.let {
            HomeResultBanner(look, it, onRetry = { submitCommand(lastSubmittedText) }, onOpenConnection = onOpenConnection)
        }
        if (recents.isNotEmpty()) {
            HomeSectionHeader(look, stringResource(R.string.home_recent))
            HomeRecentRow(look, recents)
        }
    }
}

/**
 * Every [PhoneHomeLayout.Item] with its title, caption, icon and action. MainActivity builds
 * [tiles] from [PhoneHomeLayout.Tile.entries], so they pair up by position.
 */
@Composable
private fun homeEntries(
    tiles: List<HomeTileUi>,
    radio: ResumeRadioUi?,
    onOpenConnection: () -> Unit,
    onMirror: () -> Unit,
    onBridgeDuo: (() -> Unit)?
): Map<PhoneHomeLayout.Item, HomeEntry> {
    val captions = mapOf(
        PhoneHomeLayout.Item.BROWSER to stringResource(R.string.home_v2_caption_browser),
        PhoneHomeLayout.Item.MUSIC to stringResource(R.string.home_v2_caption_music),
        PhoneHomeLayout.Item.RADIO to (radio?.station ?: stringResource(R.string.home_v2_caption_radio)),
        PhoneHomeLayout.Item.TV to stringResource(R.string.home_v2_caption_tv),
        PhoneHomeLayout.Item.YOUTUBE to stringResource(R.string.home_v2_caption_youtube),
        PhoneHomeLayout.Item.MIRROR to stringResource(R.string.home_v2_caption_mirror),
        PhoneHomeLayout.Item.MY_CAR to stringResource(R.string.home_v2_caption_my_car),
        PhoneHomeLayout.Item.DISPLAYS to stringResource(R.string.home_v2_caption_displays),
        PhoneHomeLayout.Item.MORE to stringResource(R.string.home_v2_caption_more)
    )
    val map = LinkedHashMap<PhoneHomeLayout.Item, HomeEntry>()
    PhoneHomeLayout.Tile.entries.zip(tiles).forEach { (tile, ui) ->
        val item = PhoneHomeLayout.itemFor(tile)
        map[item] = HomeEntry(item, ui.title, captions.getValue(item), ui.icon, ui.onClick)
    }
    fun extra(item: PhoneHomeLayout.Item, title: String, icon: Int, onClick: () -> Unit) {
        map[item] = HomeEntry(item, title, captions.getValue(item), icon, onClick)
    }
    extra(PhoneHomeLayout.Item.MIRROR, stringResource(R.string.home_mirror_action), R.drawable.ic_tile_mirror, onMirror)
    extra(PhoneHomeLayout.Item.MY_CAR, stringResource(R.string.settings_car_connection), R.drawable.ic_tile_car, onOpenConnection)
    extra(PhoneHomeLayout.Item.DISPLAYS, stringResource(R.string.home_v2_tile_displays), R.drawable.ic_browser_split, onBridgeDuo ?: onMirror)
    return map
}

/**
 * Paints the wallpaper (Settings > Appearance > Wallpaper, with its dim) on the Home page's root
 * view, so it sits behind the header and the mini player too. The page is rebuilt for every
 * screen, so no other screen inherits it. "Your photo" is read off the main thread and falls back
 * to the plain background when the file is missing or unreadable.
 */
@Composable
private fun HomeWallpaperBehindPage(look: HomeLook) {
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(look.spec, look.palette) {
        val file = AppearanceStore.customWallpaperFile(context)
        var wallpaper = HomeWallpaper.forPhone(look.spec, customExists = file.isFile)
        val photo = if (wallpaper == Wallpaper.CUSTOM) {
            withContext(Dispatchers.IO) { WallpaperDrawable.decodePhoto(file, view.resources.displayMetrics.widthPixels) }
        } else null
        if (wallpaper == Wallpaper.CUSTOM && photo == null) wallpaper = Wallpaper.NONE
        pageRoot(view)?.background = WallpaperDrawable(
            wallpaper,
            look.palette,
            HomeWallpaper.dimColor(wallpaper, look.spec.wallpaperDim, look.palette.isLight),
            view.resources.displayMetrics.density,
            photo
        )
    }
}

private fun pageRoot(view: View): View? {
    var current: View? = view
    while (current != null) {
        if (current.getTag(R.id.autobridge_page_root) != null) return current
        current = current.parent as? View
    }
    return null
}

/** "Version X is available": opens the update dialog; ✕ hides it for that release. */
@Composable
private fun UpdateCard(look: HomeLook, version: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val dismissDescription = stringResource(R.string.home_update_dismiss)
    val soft = ThemeResolver.withAlpha(look.palette.accent, 0x24)
    Surface(
        onClick = onOpen,
        shape = look.cardShape,
        color = androidx.compose.ui.graphics.Color(soft),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).homeCard(look, look.chipShape),
                contentAlignment = Alignment.Center
            ) { Text("⬇", color = look.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(stringResource(R.string.home_update_title, version), color = look.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.home_update_caption), color = look.textSecondary, fontSize = 12.5.sp)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = dismissDescription }) {
                Text("✕", color = look.textSecondary, fontSize = 16.sp)
            }
        }
    }
}
