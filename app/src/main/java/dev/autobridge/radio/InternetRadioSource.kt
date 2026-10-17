package dev.autobridge.radio

import android.content.Context
import dev.autobridge.iptv.IptvEntry

/**
 * Internet radio as a source: a thin adapter over the existing streaming path
 * ([dev.autobridge.iptv.RadioBrowserApi]/[dev.autobridge.iptv.RadioStationMatcher],
 * [dev.autobridge.iptv.IptvCatalog], [dev.autobridge.iptv.IptvSourceStore],
 * [dev.autobridge.iptv.IptvChannelQueue]). It changes none of that logic; it only maps an
 * [IptvEntry] into the domain's [RadioStation] so the rest of the radio package speaks one type.
 *
 * Always available: streaming needs no special hardware, only a network, and connection state is
 * surfaced separately through the Media3 session.
 */
object InternetRadioSource : RadioSource {
    override val type: RadioSourceType = RadioSourceType.INTERNET_RADIO

    override fun isAvailable(context: Context): Boolean = true

    override fun unavailableReason(context: Context): String? = null

    /**
     * Maps a catalog/search entry to an internet [RadioStation]. The station's [RadioStation.id]
     * and [RadioStation.streamUrl] are the entry URL — the identity favourites/recents use — and no
     * frequency is ever synthesised for an internet station.
     */
    fun toStation(entry: IptvEntry): RadioStation = RadioStation(
        sourceType = RadioSourceType.INTERNET_RADIO,
        id = entry.url,
        title = entry.title,
        streamUrl = entry.url,
        logo = entry.logo,
        subtitle = entry.subtitle,
        frequencyKHz = null,
    )
}
