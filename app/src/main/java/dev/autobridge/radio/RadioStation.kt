package dev.autobridge.radio

/**
 * One station the radio domain can show, select or play, independent of where it came from.
 *
 * For [RadioSourceType.INTERNET_RADIO] and [RadioSourceType.OFFLINE_AUDIO] the [id] equals the
 * [streamUrl]: a station is identified by what the player opens, which is also how favourites and
 * recents (keyed by URL in [dev.autobridge.iptv.IptvHistoryStore]) line up.
 *
 * [frequencyKHz] is always null on this target: there is no accessible hardware tuner (see
 * [HardwareFmProbe]), so no genuine FM frequency is ever synthesised. The field exists only so a
 * future, legitimately tuner-exposing target has a place to put a real frequency — it is never
 * invented for an internet station.
 */
data class RadioStation(
    val sourceType: RadioSourceType,
    val id: String,
    val title: String,
    val streamUrl: String,
    val logo: String = "",
    val subtitle: String = "",
    val frequencyKHz: Int? = null,
)
