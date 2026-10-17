package dev.autobridge.iptv

/**
 * Maps a browse-grid [IptvEntry] to the [IptvHistoryStore.Item] the playback queue is windowed from.
 *
 * It sets the full queue-relevant field set so [IptvChannelQueue.aroundItems] keeps the item (the
 * item is a playable radio stream): `sourceId = "radio-virtual"` — the same synthetic source the
 * tuner's favorites use — plus `kind = RADIO`, `type = LIVE`, `playback = STREAM`. It carries the
 * real [IptvEntry.logo] (matching [fakeItem]'s shape, which has none). ([IptvHistoryStore.Item] has
 * no subtitle/country field, so the composed subtitle is correctly dropped.)
 *
 * Lives here (not inside LibraryActivity) so it is reachable from a JVM unit test.
 */
fun IptvEntry.toHistoryItem(): IptvHistoryStore.Item = IptvHistoryStore.Item(
    sourceId = "radio-virtual",
    title = title,
    url = url,
    type = IptvEntryType.LIVE,
    kind = IptvKind.RADIO,
    playback = IptvPlayback.STREAM,
    logo = logo
)
