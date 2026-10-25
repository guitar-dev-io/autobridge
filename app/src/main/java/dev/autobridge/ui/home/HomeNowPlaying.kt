package dev.autobridge.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.autobridge.R
import dev.autobridge.media.MediaPlaybackClient
import dev.autobridge.ui.PhoneHomeLayout
import dev.autobridge.ui.ResumeRadioUi
import kotlinx.coroutines.delay

/** What the media session has loaded, read once a second for Dashboard's hero card. */
private data class SessionMedia(
    val title: String,
    val subtitle: String?,
    val playRequested: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val video: Boolean,
    val art: ByteArray?
)

/**
 * Dashboard's now-playing hero, which is also Home's player in that layout (the bottom mini
 * player stays hidden there, see [dev.autobridge.ui.MiniPlayer]):
 *
 * - the radio station on the air, or the one played last, from [radio] (MainActivity's reading);
 * - otherwise whatever else the media session has loaded (a song, a TV channel), read through its
 *   own [MediaPlaybackClient];
 * - otherwise an empty card that opens Music.
 */
@Composable
fun HomeNowPlayingHero(
    look: HomeLook,
    radio: ResumeRadioUi?,
    onOpenRadio: () -> Unit,
    onToggleRadio: () -> Unit,
    onOpenMusic: () -> Unit
) {
    val context = LocalContext.current
    val client = remember { MediaPlaybackClient(context.applicationContext) }
    var media by remember { mutableStateOf<SessionMedia?>(null) }
    DisposableEffect(client) {
        client.connect()
        onDispose { client.disconnect() }
    }
    LaunchedEffect(client) {
        while (true) {
            media = client.currentTitle?.takeIf { client.isConnected }?.let { title ->
                val now = dev.autobridge.radio.RadioNowPlaying.of(client.currentItemTitle, title, client.currentArtist)
                val player = client.player
                SessionMedia(
                    title = now.song ?: title,
                    subtitle = listOfNotNull(now.artist, now.station.takeIf { now.song != null }).joinToString(" · ").ifBlank { null },
                    playRequested = client.isPlayRequested,
                    positionMs = player?.currentPosition ?: 0L,
                    durationMs = player?.duration?.takeIf { it > 0 } ?: 0L,
                    video = client.hasVideo,
                    art = client.currentArtworkData
                )
            }
            delay(1_000L)
        }
    }

    val session = media
    when {
        // The radio card's own reading wins while a station is on the air: it knows the logo.
        radio != null && (radio.live || session == null) -> RadioHero(look, radio, onOpenRadio, onToggleRadio)
        session != null -> MediaHero(look, session, client) {
            context.startActivity(
                dev.autobridge.library.PlayerActivity.intent(
                    context = context,
                    url = "",
                    title = client.currentTitle.orEmpty(),
                    subtitle = client.currentArtist.orEmpty(),
                    video = session.video
                )
            )
        }
        else -> EmptyHero(look, onOpenMusic)
    }
}

@Composable
private fun HeroCard(look: HomeLook, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().homeCard(look).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) { content() }
}

/** The tappable top row: artwork, the small label, title and subtitle. */
@Composable
private fun HeroHeader(
    look: HomeLook,
    label: String,
    title: String,
    subtitle: String?,
    onOpen: () -> Unit,
    art: @Composable () -> Unit
) {
    Surface(onClick = onOpen, color = Color.Transparent, shape = look.artShape) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(look.artShape), contentAlignment = Alignment.Center) { art() }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(label.uppercase(), color = look.textTertiary, fontSize = 11.sp, letterSpacing = 0.66.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, color = look.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = look.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun GlyphArt(look: HomeLook, item: PhoneHomeLayout.Item, icon: Int) {
    val colors = look.chip(item)
    Box(Modifier.fillMaxSize().background(Color(colors.fill)), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = Color(colors.glyph), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun BitmapArt(bitmap: Bitmap) {
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
}

@Composable
private fun RadioHero(look: HomeLook, radio: ResumeRadioUi, onOpen: () -> Unit, onToggle: () -> Unit) {
    HeroCard(look) {
        HeroHeader(
            look,
            label = stringResource(if (radio.live) R.string.home_radio_live else R.string.home_resume_radio),
            title = radio.station,
            subtitle = radio.song ?: stringResource(if (radio.live) R.string.home_radio_open else R.string.home_resume_tap),
            onOpen = onOpen
        ) {
            if (radio.logo != null) BitmapArt(radio.logo) else {
                Box(
                    Modifier.fillMaxSize().background(Color(dev.autobridge.car.CarStationTile.colorFor(radio.station))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(dev.autobridge.car.CarStationTile.label(radio.station), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        Controls(look, playing = radio.playing, onToggle = onToggle)
    }
}

@Composable
private fun MediaHero(look: HomeLook, media: SessionMedia, client: MediaPlaybackClient, onOpen: () -> Unit) {
    val art = remember(media.art) {
        media.art?.takeIf { it.isNotEmpty() }?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    HeroCard(look) {
        HeroHeader(
            look,
            label = stringResource(R.string.home_v2_now_playing_phone),
            title = media.title,
            subtitle = media.subtitle,
            onOpen = onOpen
        ) {
            if (art != null) BitmapArt(art) else GlyphArt(look, PhoneHomeLayout.Item.MUSIC, R.drawable.ic_tile_youtube_music)
        }
        if (media.durationMs > 0) Progress(look, media.positionMs, media.durationMs)
        Controls(
            look,
            playing = media.playRequested,
            onPrevious = { client.previous() },
            onNext = { client.next() },
            onToggle = { if (client.isPlayRequested) client.pause() else client.resume() }
        )
    }
}

@Composable
private fun EmptyHero(look: HomeLook, onOpenMusic: () -> Unit) {
    HeroCard(look) {
        HeroHeader(
            look,
            label = stringResource(R.string.home_v2_now_playing),
            title = stringResource(R.string.home_v2_nothing_playing),
            subtitle = stringResource(R.string.home_v2_nothing_playing_caption),
            onOpen = onOpenMusic
        ) { GlyphArt(look, PhoneHomeLayout.Item.MUSIC, R.drawable.ic_tile_youtube_music) }
    }
}

@Composable
private fun Progress(look: HomeLook, positionMs: Long, durationMs: Long) {
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(look.hairline, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(look.accent, RoundedCornerShape(4.dp)))
        }
        Row {
            Text(clock(positionMs), color = look.textTertiary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(clock(durationMs), color = look.textTertiary, fontSize = 11.sp)
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Previous / play-pause / next, centred; previous and next only when given. */
@Composable
private fun Controls(
    look: HomeLook,
    playing: Boolean,
    onToggle: () -> Unit,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null
) {
    val toggleDescription = stringResource(if (playing) R.string.radio_pause else R.string.radio_play)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (onPrevious != null) RoundGlyph(look, "⏮", stringResource(R.string.home_v2_previous), onPrevious)
        Surface(
            onClick = onToggle,
            shape = CircleShape,
            color = look.accent,
            modifier = Modifier.size(56.dp).semantics { contentDescription = toggleDescription }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (playing) "❚❚" else "▶", color = look.onAccent, fontSize = if (playing) 16.sp else 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (onNext != null) RoundGlyph(look, "⏭", stringResource(R.string.home_v2_next), onNext)
    }
}

@Composable
private fun RoundGlyph(look: HomeLook, glyph: String, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) { Text(glyph, color = look.text, fontSize = 20.sp) }
    }
}

/**
 * Classic and Compact's radio card: the station on the air (the mini player leaves radio to it)
 * or the one played last, with a round play/pause. Dashboard folds this into its hero.
 */
@Composable
fun HomeRadioCard(look: HomeLook, radio: ResumeRadioUi, onOpen: () -> Unit, onToggle: () -> Unit) {
    val toggleDescription = stringResource(if (radio.playing) R.string.radio_pause else R.string.radio_play)
    Surface(onClick = onOpen, color = Color.Transparent, shape = look.cardShape, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.homeCard(look).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(look.artShape), contentAlignment = Alignment.Center) {
                if (radio.logo != null) BitmapArt(radio.logo) else {
                    Box(
                        Modifier.fillMaxSize().background(Color(dev.autobridge.car.CarStationTile.colorFor(radio.station))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(dev.autobridge.car.CarStationTile.label(radio.station), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    stringResource(if (radio.live) R.string.home_radio_live else R.string.home_resume_radio),
                    color = Color(PhoneHomeLayout.Item.RADIO.hue(look.palette.isLight)),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(radio.station, color = look.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    radio.song ?: stringResource(if (radio.live) R.string.home_radio_open else R.string.home_resume_tap),
                    color = look.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                onClick = onToggle,
                shape = CircleShape,
                color = look.accent,
                modifier = Modifier.size(48.dp).semantics { contentDescription = toggleDescription }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (radio.playing) "❚❚" else "▶", color = look.onAccent, fontSize = if (radio.playing) 16.sp else 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
